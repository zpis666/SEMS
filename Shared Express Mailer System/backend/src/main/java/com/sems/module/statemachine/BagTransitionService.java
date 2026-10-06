package com.sems.module.statemachine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.infra.IdempotentService;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.entity.BagTransferLog;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.bag.mapper.BagTransferLogMapper;
import com.sems.push.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 共享快递袋状态机：全系统唯一的袋状态流转入口
 * （见《系统设计说明书》3.2 状态转移表、3.3 状态机实现设计；接口契约见契约 8.4）。
 *
 * <p>一次 {@link #fire(TransitionCommand)} 在<b>单个事务</b>内按固定顺序完成七步：
 * <ol>
 *   <li>逐袋幂等（{@code clientRequestId + bagCode}，命中则回查袋当前状态按回放返回）；</li>
 *   <li>按袋码装载袋行（不存在报 4106）；</li>
 *   <li>在 {@link TransitionRegistry} 中解析目标态（未注册报 4102；{@code STATUS_CORRECT} 取命令指定态）；</li>
 *   <li>条件 UPDATE（前态 + 版本双重约束，影响行数为 0 报 4103）；</li>
 *   <li>写流转日志（只插入，全程可追溯）；</li>
 *   <li>执行事件的伴随动作（订单联动、Redis 增减、业务记录写入）；</li>
 *   <li>登记事务提交后的 WebSocket 推送。</li>
 * </ol>
 *
 * <p><b>为什么用 UpdateWrapper 手写条件 UPDATE，而不是只靠乐观锁插件：</b>
 * 乐观锁插件只在"带实体的 updateById / update(entity, wrapper)"上生效，且只能追加 {@code version=?}
 * 一个条件，无法把业务前置条件（前态 {@code status}）一并塞进同一条 SQL。防重复领取要求
 * "前态未被他人改变"与"版本未被他人推进"<b>同时</b>成立、由数据库一次原子判定
 * （见设计书 3.3 与 7.2），因此这里显式写 {@code WHERE bag_code=? AND status=前态 AND version=?}：
 * 任一条件不满足，影响行数即为 0，本笔流转立即终止（4103）。更新值全部经 MyBatis-Plus 参数占位符绑定，
 * 不含任何字符串拼接 SQL。站点不变时连 {@code station_id} 都不写，避免无谓列更新。
 *
 * <p><b>状态字段的唯一写入口：</b>业务代码不得自行 UPDATE {@code bag.status}——包括批量接口在内，
 * 所有流转都必须经本服务，否则流转日志、订单联动与库存推送会整体失真（审计口径见设计书 3.2 表头）。
 */
@Service
public class BagTransitionService {

    private static final Logger log = LoggerFactory.getLogger(BagTransitionService.class);

    /** {@code STATUS_CORRECT}（#21 状态修正）允许的目标态白名单（见设计书 3.2 #21）。 */
    private static final Set<BagStatus> STATUS_CORRECT_TARGETS =
            EnumSet.of(BagStatus.IDLE, BagStatus.REPAIRING, BagStatus.LOST, BagStatus.SCRAPPED);

    /**
     * 会把 {@code bag.station_id} 改写为"命令指定站点"的六个事件
     * （契约 8 站点归属变更规则 / 设计书 3.2；{@code RETURN_MARK} 只写订单归还站点，不在此列）。
     */
    private static final Set<BagEvent> STATION_CHANGE_EVENTS = EnumSet.of(
            BagEvent.RECOVER_UNUSED, BagEvent.RECOVER_CONFIRM, BagEvent.RECOVER_DIRECT,
            BagEvent.ALLOCATE_IN, BagEvent.FOUND, BagEvent.STATUS_CORRECT);

    private final BagMapper bagMapper;
    private final BagTransferLogMapper transferLogMapper;
    private final IdempotentService idempotentService;
    private final PushService pushService;

    /** 事件 → 伴随动作；没有实现的事件视为无伴随动作（不报错）。 */
    private final Map<BagEvent, TransitionAction> actionsByEvent;

    public BagTransitionService(BagMapper bagMapper,
                                BagTransferLogMapper transferLogMapper,
                                IdempotentService idempotentService,
                                PushService pushService,
                                List<TransitionAction> actions) {
        this.bagMapper = bagMapper;
        this.transferLogMapper = transferLogMapper;
        this.idempotentService = idempotentService;
        this.pushService = pushService;
        EnumMap<BagEvent, TransitionAction> table = new EnumMap<>(BagEvent.class);
        for (TransitionAction action : actions) {
            if (action.event() == null) {
                throw new IllegalStateException("伴随动作未声明事件（event() 返回 null）："
                        + action.getClass().getName());
            }
            // 同一事件只允许一个伴随动作：重复注册属配置错误，启动即失败，
            // 否则会随机执行其中一个（Spring 注入顺序不保证），副作用不可预期
            if (table.putIfAbsent(action.event(), action) != null) {
                throw new IllegalStateException("同一事件注册了多个伴随动作：" + action.event().code());
            }
        }
        this.actionsByEvent = Collections.unmodifiableMap(table);
    }

    /**
     * 执行一次状态流转（契约 8.4 / 设计书 3.3 的七步主流程）。
     *
     * <p>返回时事务尚未提交，推送与消息在提交后才发出；若任意一步抛异常，整笔回滚
     * （袋状态、流转日志、伴随动作的库内副作用一起撤销；Redis 侧的库存/计数增减由伴随动作
     * 按设计书 7.2 的回补路径处置）。
     *
     * @param cmd 流转命令（袋码、事件、操作人/站点、幂等 ID 等见 {@link TransitionCommand}）
     * @return 流转结果；幂等命中时 {@code replayed=true}
     */
    @Transactional(rollbackFor = Exception.class)
    public BagTransitionResult fire(TransitionCommand cmd) {
        requireCommand(cmd);
        String bagCode = cmd.getBagCode();

        // 1) 逐袋幂等：键 = clientRequestId + bagCode，批量接口逐袋独立、单只失败不影响其余（见 9.6）。
        //    命中时不执行任何写操作，只回查袋的当前状态按回放返回，保证重复请求的副作用恰好一次。
        //    未携带幂等 ID（内部调用/定时任务）时跳过去重，防重复由条件 UPDATE 兜底。
        //    Redis 异常时 IdempotentService.tryAcquireBag 内部按 7.2 降级矩阵放行（视作首次处理）——
        //    熔断期申领正是靠这一点才能继续走到「直走 DB 行级路径」，若此处上抛，降级路径会被打断而不可用。
        if (hasClientRequestId(cmd) && !idempotentService.tryAcquireBag(cmd.getClientRequestId(), bagCode)) {
            Bag current = loadBag(bagCode);
            BagStatus currentStatus = BagStatus.of(current.getStatus());
            if (!isTransitionLanded(cmd, currentStatus)) {
                // 逐袋幂等键只写占位符、失败尝试不释放（9.6）：键已占用但袋并没有达到本次事件的目标态，
                // 说明首次尝试在目标态解析（4102）/条件 UPDATE（4103）/伴随动作处失败、事务未落地。
                // 不能把未真正执行的流转当「成功回放」上报（调用方会据回放直接计数/回报成功），
                // 因此按未命中继续执行本次流转；重复执行由条件 UPDATE 独立兜底，不会双写。
                log.warn("逐袋幂等键已占用但目标态未达成，判定首次尝试未落地，按首次执行：袋={}，事件={}，当前状态={}",
                        bagCode, cmd.getEvent().code(), currentStatus.code());
            } else {
                log.debug("流转幂等命中：袋={}，事件={}，当前状态={}，按回放返回",
                        bagCode, cmd.getEvent().code(), currentStatus.code());
                return BagTransitionResult.builder()
                        .bagCode(bagCode)
                        // 首次流转的前态不落库、无法还原（见 BagTransitionResult 类注释）
                        .fromStatus(null)
                        .toStatus(currentStatus)
                        .replayed(true)
                        .stationId(current.getStationId())
                        .build();
            }
        }

        // 2) 装载袋行（袋码唯一索引，至多一行）；查不到即扫码识别失败 4106
        Bag bag = loadBag(bagCode);

        // 3) 解析目标态：注册表命中 → 目标态；STATUS_CORRECT → 命令指定（白名单校验）；其余 → 4102
        BagStatus from = BagStatus.of(bag.getStatus());
        BagStatus to = resolveTarget(cmd, from);

        // 4) 条件 UPDATE：一条 SQL 同时约束前态与版本，防并发重复领取的最后防线
        Long stationChangeId = resolveStationId(cmd);
        if (conditionalUpdate(bag, from, to, stationChangeId) == 0) {
            // 影响行数为 0 = 袋已被其他请求先一步流转（或版本被推进）：并发冲突，终止本笔
            throw new BizException(ErrorCode.DUPLICATE_BORROW, "快递袋状态已被其他请求变更，请刷新后重试");
        }

        // 5) 流转日志：每次成功转移写一条（只插入不修改，全程可追溯）
        insertTransferLog(cmd, bag, from, to);

        // 6) 伴随动作：同一事务内执行，动作抛异常则整笔回滚（不会出现"状态改了、订单没联动"的半截数据）
        TransitionAction action = actionsByEvent.get(cmd.getEvent());
        if (action != null) {
            action.apply(BagTransitionContext.builder()
                    .cmd(cmd)
                    .bag(bag)                    // 前态快照：动作据此区分同一事件的不同前态分支
                    .operatorId(cmd.getOperatorId())
                    .proxy(cmd.isProxy())
                    .build());
        }

        // 7) 事务提交后推送（回滚则不发）：只推可用库存真正发生增减的站点
        Long finalStationId = stationChangeId != null ? stationChangeId : bag.getStationId();
        registerStockPush(cmd, bag, from, to, finalStationId);

        log.debug("流转成功：袋={}，{} + {} → {}",
                bagCode, from.code(), cmd.getEvent().code(), to.code());
        return BagTransitionResult.builder()
                .bagCode(bagCode)
                .fromStatus(from)
                .toStatus(to)
                .replayed(false)
                .stationId(finalStationId)
                .build();
    }

    /** 命令必填项校验：缺袋码/事件无法定位流转对象，统一按 3001 拒绝。 */
    private void requireCommand(TransitionCommand cmd) {
        if (cmd == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "流转命令不能为空");
        }
        if (cmd.getBagCode() == null || cmd.getBagCode().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "袋码不能为空");
        }
        if (cmd.getEvent() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "触发事件不能为空");
        }
    }

    /** 按袋码装载袋行；不存在报 4106（扫码识别失败）。 */
    private Bag loadBag(String bagCode) {
        Bag bag = bagMapper.selectOne(new LambdaQueryWrapper<Bag>().eq(Bag::getBagCode, bagCode));
        if (bag == null) {
            throw new BizException(ErrorCode.BAG_NOT_FOUND, "袋码不存在：" + bagCode);
        }
        return bag;
    }

    /**
     * 解析目标态。
     *
     * <p>{@code STATUS_CORRECT}（#21）单独处理：注册表刻意不登记它，{@link TransitionRegistry#resolve}
     * 返回 null 不表示非法，而是转入"目标态由命令指定"分支——先拒绝终态（报废袋不可再修正），
     * 再校验白名单 {空闲, 维修中, 已遗失, 报废} 与必填的盘点差异单号、修正原因（设计书 3.2 #21 前置校验）。
     * 其余事件拿到的 null 即非法跳变，一律 4102。
     */
    private BagStatus resolveTarget(TransitionCommand cmd, BagStatus from) {
        if (cmd.getEvent() == BagEvent.STATUS_CORRECT) {
            if (from.isTerminal()) {
                throw new BizException(ErrorCode.BAG_ILLEGAL_TRANSITION, "报废为终态，不允许再发起状态修正");
            }
            BagStatus target = cmd.getTargetStatus();
            if (target == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "状态修正必须指定目标状态");
            }
            if (!STATUS_CORRECT_TARGETS.contains(target)) {
                throw new BizException(ErrorCode.BAG_ILLEGAL_TRANSITION,
                        "状态修正目标态不在白名单内：" + target.code());
            }
            if (cmd.getRefCheckNo() == null || cmd.getRefCheckNo().isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "状态修正必须填写盘点差异单号");
            }
            if (cmd.getRemark() == null || cmd.getRemark().isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "状态修正必须填写修正原因");
            }
            return target;
        }
        BagStatus to = TransitionRegistry.resolve(from, cmd.getEvent());
        if (to == null) {
            throw new BizException(ErrorCode.BAG_ILLEGAL_TRANSITION,
                    "不支持的流转：" + from.displayName() + " + " + cmd.getEvent().displayName());
        }
        return to;
    }

    /**
     * 解析流转后袋的站点归属（只对六个站点变更事件生效，其余事件保持原站点不写）。
     *
     * <p>目标站点缺省取命令的操作站点——站点管理员扫袋确认时"确认站点"就是其操作站点；
     * 跨站修正/调拨入库等由调用方显式给 {@code targetStationId}。两者都为空则返回 null，
     * 表示本次不动归属（STATUS_CORRECT 同站修正即属此种，对应 #21"仅同站且同态不动"的库存口径）。
     */
    private Long resolveStationId(TransitionCommand cmd) {
        if (!STATION_CHANGE_EVENTS.contains(cmd.getEvent())) {
            return null;
        }
        return cmd.getTargetStationId() != null ? cmd.getTargetStationId() : cmd.getStationId();
    }

    /**
     * 条件 UPDATE：{@code WHERE bag_code=? AND status=前态 AND version=?}，返回影响行数供冲突判定。
     *
     * <p>更新列仅 status 与 version（站点变更时加 station_id）：其余字段（use_count、订单联动、
     * 业务记录等）由伴随动作负责，避免状态机与业务动作双写同一列。
     */
    private int conditionalUpdate(Bag bag, BagStatus from, BagStatus to, Long newStationId) {
        UpdateWrapper<Bag> update = new UpdateWrapper<>();
        update.eq("bag_code", bag.getBagCode())
                .eq("status", from.code())
                .eq("version", bag.getVersion())
                .set("status", to.code())
                .set("version", bag.getVersion() + 1);
        if (newStationId != null) {
            update.set("station_id", newStationId);
        }
        return bagMapper.update(null, update);
    }

    /**
     * 写流转日志（只插入）：操作人、站点、代操作标记与 #21 的差异单号/备注一并落库，
     * 供生命周期追溯（{@code /bags/{bagCode}/trace}）与审计核查。
     */
    private void insertTransferLog(TransitionCommand cmd, Bag bag, BagStatus from, BagStatus to) {
        BagTransferLog transferLog = new BagTransferLog();
        transferLog.setBagCode(bag.getBagCode());
        transferLog.setFromStatus(from.code());
        transferLog.setToStatus(to.code());
        transferLog.setEvent(cmd.getEvent().code());
        transferLog.setOperatorId(cmd.getOperatorId());
        transferLog.setStationId(cmd.getStationId());
        transferLog.setProxyFlag(cmd.isProxy() ? 1 : 0);
        transferLog.setRefCheckNo(cmd.getRefCheckNo());
        transferLog.setRemark(cmd.getRemark());
        // "写入即发生时间"：库里虽有默认值，这里显式赋值，保证台账时间与流转时刻一致
        transferLog.setCreatedAt(LocalDateTime.now());
        transferLogMapper.insert(transferLog);
    }

    /**
     * 登记事务提交后的可用库存推送（见设计书 7.3 站点库存主题）。
     *
     * <p>可用库存的权威口径 = 站点 IDLE 袋数（3.1），所以只有"离开空闲"与"进入空闲"才推：
     * <ul>
     *   <li>离开：前态为空闲，且目标态不再空闲、或跨站 → 原站 −1；</li>
     *   <li>进入：目标态为空闲，且前态不是空闲、或跨站 → 目标站 +1；</li>
     * </ul>
     * 跨站且前后同为空闲（#21 跨站修正）按 3.2 #21 口径两站各推一条；同站且同态不动、不推。
     * 推送一律包在 {@link PushService#afterCommit} 中：事务回滚则消息作废，避免"消息已发、数据回滚"。
     */
    private void registerStockPush(TransitionCommand cmd, Bag bag, BagStatus from, BagStatus to, Long finalStationId) {
        Long oldStationId = bag.getStationId();
        boolean stationMoved = !Objects.equals(oldStationId, finalStationId);

        List<Runnable> pushes = new ArrayList<>(2);
        if (from.countsAsAvailable() && (!to.countsAsAvailable() || stationMoved)) {
            addStockPush(pushes, cmd, from, to, oldStationId, -1);
        }
        if (to.countsAsAvailable() && (!from.countsAsAvailable() || stationMoved)) {
            addStockPush(pushes, cmd, from, to, finalStationId, 1);
        }
        if (!pushes.isEmpty()) {
            pushService.afterCommit(() -> pushes.forEach(Runnable::run));
        }
    }

    /** 组装单条库存推送任务；站号为空说明数据异常，跳过推送但不影响流转本身（由对账任务纠正）。 */
    private void addStockPush(List<Runnable> pushes, TransitionCommand cmd, BagStatus from, BagStatus to,
                              Long stationId, int delta) {
        if (stationId == null) {
            log.warn("袋 {} 站点为空，跳过库存推送（事件={}）", cmd.getBagCode(), cmd.getEvent().code());
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bagCode", cmd.getBagCode());
        data.put("event", cmd.getEvent().code());
        data.put("fromStatus", from.code());
        data.put("toStatus", to.code());
        data.put("stationId", stationId);
        data.put("stockDelta", delta);
        pushes.add(() -> pushService.pushStationStock(stationId, data));
    }

    /** 是否携带幂等 ID：缺失时跳过逐袋幂等（不能凭空造 ID），防重复交给条件 UPDATE 兜底。 */
    private static boolean hasClientRequestId(TransitionCommand cmd) {
        return cmd.getClientRequestId() != null && !cmd.getClientRequestId().isBlank();
    }

    /**
     * 回放前校验「首次流转是否真的落地」（设计书 3.3 步骤 1「命中直接返回首次结果」的前提；9.6）。
     *
     * <p>逐袋幂等键只写占位符，且失败尝试不删键——首次尝试若在目标态解析（4102）、条件 UPDATE（4103）
     * 或伴随动作处失败，袋状态不会变化而键已被消费，同一 clientRequestId 的重试会被误判为「回放」。
     * 校验方式：只有袋当前状态恰为本次事件在注册表中的目标态（{@code STATUS_CORRECT} 为命令指定目标态）
     * 时，才能确认首次流转确实执行过、可以安全回放；否则按未命中继续执行。
     */
    private static boolean isTransitionLanded(TransitionCommand cmd, BagStatus currentStatus) {
        if (cmd.getEvent() == BagEvent.STATUS_CORRECT) {
            // #21 目标态由命令指定（白名单由 resolveTarget 校验），当前态与命令目标一致才算落地
            return cmd.getTargetStatus() != null && cmd.getTargetStatus() == currentStatus;
        }
        for (TransitionRule rule : TransitionRegistry.all()) {
            if (rule.event() == cmd.getEvent()) {
                return rule.to() == currentStatus;
            }
        }
        return false;
    }
}
