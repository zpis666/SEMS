package com.sems.module.station.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.common.event.AuditEvent;
import com.sems.common.util.JsonUtils;
import com.sems.config.SemsProperties;
import com.sems.infra.IdempotentService;
import com.sems.infra.RedisKeys;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.statemachine.BagTransitionResult;
import com.sems.module.statemachine.BagTransitionService;
import com.sems.module.statemachine.OrderStatus;
import com.sems.module.statemachine.TransitionCommand;
import com.sems.module.stats.service.StatsService;
import com.sems.module.station.dto.RecycleBinVo;
import com.sems.module.station.dto.RecycleConfirmDto;
import com.sems.module.station.dto.RecycleConfirmItemDto;
import com.sems.module.station.dto.RecycleConfirmVo;
import com.sems.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 回收箱清单与扫码回收确认服务（M3，见《系统设计说明书》3.2 状态转移表 #4/#5/#7/#8、
 * 3.4.2「归还—消毒—复用流程」、4.4 第④条与 5.3 的两个回收端点）。
 *
 * <p><b>站点校验对象：</b>回收确认校验的是「被投放的回收箱所属站点」（即操作人站点），
 * 确认前不校验袋的旧归属——这是跨站归还得以成立的关键（4.4 第④条；确认事务中
 * 状态机才把 {@code bag.station_id} 改为确认站点）。
 *
 * <p><b>路由规则（3.2 表后「#4 与 #5 的取用规则」，按绑定订单状态自动路由）：</b>
 * <ul>
 *   <li>已分配 +（订单为空/已取消）且未勾选「曾寄出」 → #4 RECOVER_UNUSED（袋回空闲、库存 +1）；</li>
 *   <li>已分配 +（订单待寄件，或勾选「曾寄出」） → #5 RECOVER_CONFIRM（袋到待消毒、订单转已取消）；</li>
 *   <li>使用中 → #7 RECOVER_DIRECT（袋到待消毒、订单转已完成、use_count+1，含未标记直投）；</li>
 *   <li>订单待回收确认（袋仍为使用中）→ #8 RECOVER_CONFIRM（同上）。</li>
 * </ul>
 *
 * <p><b>原子性与幂等：</b>批量入口做一次接口级幂等（键 = HTTP 方法 + 路径模板，TTL 10 分钟），
 * 循环内逐袋先做校验与风控判定、<b>校验通过后</b>才占用 {@code clientRequestId + bagCode} 幂等键
 * （校验失败的袋不落键，重试可正常复检，同 BagService 的既定约定）；每只袋的流转（袋状态、流转日志、
 * 订单联动、计数增减、流水）在状态机的单事务内原子完成，因此本方法本身<b>不包事务</b>——
 * 单只失败只回滚该袋自己的事务，其余袋继续（单只失败不影响其余，见 6.2/FR-M3-09）。
 *
 * <p><b>回收确认的三档审计 action 口径（7.6，供审计查询按动作筛出重点核查项）：</b>
 * <ul>
 *   <li><b>回收确认-跨站</b>：袋原站点 ≠ 操作人站点，或平台管理员代操作（proxy）；</li>
 *   <li><b>回收确认-未标记直投</b>：袋为使用中且绑定订单非「待回收确认」（未标记直投，走 #7）；</li>
 *   <li><b>回收确认-直投</b>：其余常规确认（#4/#5/#8）。</li>
 * </ul>
 * 各档命中数随批次结果（{@code proxy / crossStation / directWithoutMark}）返回，供审计/看板区分。
 */
@Service
public class RecycleService {

    private static final Logger log = LoggerFactory.getLogger(RecycleService.class);

    /** 接口级幂等键（HTTP 方法 + 路径模板，全站唯一，见契约第 4 节）。 */
    private static final String API_RECYCLE_CONFIRM = "POST /stations/{id}/recycle-confirm";

    /** 可进入回收确认流程的袋状态集（#4/#5/#7/#8 的前态）。 */
    private static final Set<BagStatus> RECOVERABLE =
            EnumSet.of(BagStatus.ALLOCATED, BagStatus.IN_USE);

    /**
     * 风控告警阈值：同站点同操作人 10 分钟内确认类操作 ≥20 次 → 触发 5101（设计书 3.5「风控告警阈值」；
     * 该值未列入 11.2 配置键清单，按契约第 11 节以具名常量登记，避免散落魔法数字）。
     * /alerts 的第七类由 AlertService 读同一 Redis 键（{@link RedisKeys#auditRisk}），阈值同为 20。
     */
    private static final int RISK_CONFIRM_LIMIT = 20;

    /** 风控告警计数窗口（同 3.5：10 分钟）。 */
    private static final Duration RISK_WINDOW = Duration.ofMinutes(10);

    private final StationService stationService;
    private final BagMapper bagMapper;
    private final BagOrderMapper bagOrderMapper;
    private final BagTransitionService transitionService;
    private final IdempotentService idempotentService;
    private final SemsProperties properties;
    private final StringRedisTemplate redis;
    /** 审计事件发布器：发布方只依赖 common 包的 {@code AuditEvent}，不反向依赖 module/audit。 */
    private final ApplicationEventPublisher eventPublisher;
    /** 今日归还统计的写入点（5.6/8.3：回收确认成功后 stats:today 的 return 域 +1）。 */
    private final StatsService statsService;

    public RecycleService(StationService stationService,
                          BagMapper bagMapper,
                          BagOrderMapper bagOrderMapper,
                          BagTransitionService transitionService,
                          IdempotentService idempotentService,
                          SemsProperties properties,
                          StringRedisTemplate redis,
                          ApplicationEventPublisher eventPublisher,
                          StatsService statsService) {
        this.stationService = stationService;
        this.bagMapper = bagMapper;
        this.bagOrderMapper = bagOrderMapper;
        this.transitionService = transitionService;
        this.idempotentService = idempotentService;
        this.properties = properties;
        this.redis = redis;
        this.eventPublisher = eventPublisher;
        this.statsService = statsService;
    }

    // ==================== 回收箱待确认清单 ====================

    /**
     * 回收箱待确认清单（GET /stations/{id}/recycle-bin，本站站点管理员 / 平台管理员；见 5.3 与 FR-M3-07）：
     * ① 已标记投放本站且订单为待回收确认的使用中袋（订单 return_station_id=本站）；
     * ② 未标记但状态为使用中、袋台账在本站的袋（用户未标记即投放的候选清单）。
     * 清单仅作清点辅助：跨站未标记直投的袋仍可由扫码确认接口直接确认（4.4 第④条）。
     */
    public List<RecycleBinVo> recycleBin(Long stationId) {
        stationService.requireStation(stationId);
        stationService.assertStationAccess(stationId);

        // ① 已标记投放本站：订单归还站点=本站 且状态=待回收确认（#6 标记归还后的辅助路径）
        List<BagOrder> markedOrders = bagOrderMapper.selectList(new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getReturnStationId, stationId)
                .eq(BagOrder::getStatus, OrderStatus.PENDING_CONFIRM.code())
                .orderByAsc(BagOrder::getBagCode));
        // ② 未标记但使用中：袋台账在本站且状态=使用中
        List<Bag> inUseBags = bagMapper.selectList(new LambdaQueryWrapper<Bag>()
                .eq(Bag::getStationId, stationId)
                .eq(Bag::getStatus, BagStatus.IN_USE.code())
                .orderByAsc(Bag::getBagCode));

        // ① 的袋行批量装载（校验袋仍处于使用中）
        Map<String, Bag> bagsByCode = new HashMap<>();
        if (!markedOrders.isEmpty()) {
            List<String> codes = markedOrders.stream().map(BagOrder::getBagCode).distinct().toList();
            for (Bag bag : bagMapper.selectList(new LambdaQueryWrapper<Bag>().in(Bag::getBagCode, codes))) {
                bagsByCode.put(bag.getBagCode(), bag);
            }
        }
        // ② 的最新绑定订单：一次 IN 查询后在内存取每袋首条（已按 created_at 倒序），避免 N+1
        Map<String, BagOrder> latestOrders = latestOrdersByBag(
                inUseBags.stream().map(Bag::getBagCode).toList());

        List<RecycleBinVo> result = new ArrayList<>(markedOrders.size() + inUseBags.size());
        Set<String> added = new HashSet<>();
        for (BagOrder order : markedOrders) {
            Bag bag = bagsByCode.get(order.getBagCode());
            if (bag == null || !BagStatus.IN_USE.code().equals(bag.getStatus())) {
                // 订单仍待确认但袋已不在使用中：属数据漂移，记录日志但不混入待确认清单
                log.warn("回收箱清单跳过数据不一致的袋：bagCode={}, orderNo={}, bagStatus={}",
                        order.getBagCode(), order.getOrderNo(), bag == null ? "missing" : bag.getStatus());
                continue;
            }
            if (added.add(bag.getBagCode())) {
                result.add(toBinVo(bag, order, true));
            }
        }
        for (Bag bag : inUseBags) {
            if (added.add(bag.getBagCode())) {
                result.add(toBinVo(bag, latestOrders.get(bag.getBagCode()), false));
            }
        }
        return result;
    }

    // ==================== 扫码回收确认 ====================

    /**
     * 扫码回收确认（POST /stations/{id}/recycle-confirm，本站站点管理员 / 平台管理员；见 5.3、3.2、FR-M3-08）。
     *
     * <p>单批上限读 {@code sems.batch.max-size}（默认 500），超限抛 3001；
     * 逐袋独立执行并返回逐袋明细，单只失败不影响其余；
     * 本方法不包事务（理由见类注释），每袋的原子性由状态机事务保证。
     */
    public RecycleConfirmVo recycleConfirm(Long stationId, RecycleConfirmDto dto, String clientRequestId) {
        // 站点存在性与「本站」校验：回收箱所属站点 = 操作人站点（4.4 第④条），确认前不校验袋旧归属
        stationService.requireStation(stationId);
        stationService.assertStationAccess(stationId);

        List<RecycleConfirmItemDto> items = dto == null ? null : dto.getBags();
        if (items == null || items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "回收确认清单不能为空");
        }
        int maxSize = properties.getBatch().getMaxSize();
        if (items.size() > maxSize) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "单批回收确认不能超过 " + maxSize + " 只，当前 " + items.size() + " 只");
        }
        // 空元素（{"bags":[null]} 类畸形清单）：JSR-380 不校验 List 的 null 元素，
        // 下拉到循环里取值会变成 NPE → 500；按 6.6「参数问题一律 400+3001」在入口拒绝
        for (RecycleConfirmItemDto item : items) {
            if (item == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "回收确认清单存在空元素，请检查后重试");
            }
        }

        // 接口级幂等（契约第 4 节范式）：命中已完成直接回放首次结果；失败立即删占位
        String cached = idempotentService.findCompleted(API_RECYCLE_CONFIRM, clientRequestId);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            return JsonUtils.parse(cached, RecycleConfirmVo.class);
        }
        idempotentService.markProcessing(API_RECYCLE_CONFIRM, clientRequestId);
        try {
            RecycleConfirmVo vo = doConfirm(stationId, items, clientRequestId);
            idempotentService.markCompleted(API_RECYCLE_CONFIRM, clientRequestId, JsonUtils.toJson(vo));
            return vo;
        } catch (RuntimeException e) {
            idempotentService.clear(API_RECYCLE_CONFIRM, clientRequestId);
            throw e;
        }
    }

    /** 批量主循环：逐袋确认并汇总批次上下文计数（逐袋成败计数统一由批量信封汇总）。 */
    private RecycleConfirmVo doConfirm(Long stationId, List<RecycleConfirmItemDto> items, String clientRequestId) {
        Long operatorId = AuthContext.userId();
        String operatorRole = AuthContext.role();
        boolean proxy = AuthContext.isPlatformAdmin();

        List<RecycleConfirmItemDto> results = new ArrayList<>(items.size());
        int proxyCount = 0;
        int crossStationCount = 0;
        int directWithoutMarkCount = 0;
        // 三档审计动作各自的袋码清单（7.6 要求「按上下文写不同 action」且可筛出，
        // 光有计数筛不出来，必须把袋码一并落到审计行的 params_masked 里）。
        // 三档是**并列的审计类别**而非互斥划分：一只跨站且未标记直投的袋会同时落进两档，
        // 这正是我们想要的——审计关注的是「哪些袋属于这一类」，不是做一次划分。
        List<String> crossStationBags = new ArrayList<>();
        List<String> directWithoutMarkBags = new ArrayList<>();
        List<String> plainBags = new ArrayList<>();

        // 批内袋码重复守卫（与调拨出库/消毒登记同口径）：重复条目只判该条失败，先出现的一条照常处理。
        // 不能依赖逐袋幂等键兜底——键被占用后会把重复条目反推成「已确认完成」，未确认的袋被计入成功数（9.6）。
        Set<String> seenBagCodes = new HashSet<>();
        for (RecycleConfirmItemDto item : items) {
            String bagCode = item.getBagCode() == null ? "" : item.getBagCode().trim();
            if (!seenBagCodes.add(bagCode)) {
                results.add(fail(item, bagCode, ErrorCode.PARAM_INVALID, "袋码在本次请求中重复").item());
                continue;
            }
            Outcome outcome = confirmOne(stationId, item, clientRequestId, operatorId, operatorRole, proxy);
            results.add(outcome.item());
            if (outcome.item().isSuccess()) {
                // 三项为审计上下文计数，只统计确认成功的袋（失败袋不计入任何一档）
                boolean isCross = false;
                boolean isDirectWithoutMark = false;
                if (outcome.proxy()) {
                    proxyCount++;
                }
                if (outcome.crossStation()) {
                    crossStationCount++;
                    isCross = true;
                    crossStationBags.add(outcome.item().getBagCode());
                }
                if (outcome.directWithoutMark()) {
                    directWithoutMarkCount++;
                    isDirectWithoutMark = true;
                    directWithoutMarkBags.add(outcome.item().getBagCode());
                }
                if (!isCross && !isDirectWithoutMark) {
                    plainBags.add(outcome.item().getBagCode());
                }
                // 今日归还统计（5.6/8.3「回收确认 INCR return」）：只统计**真正完成归还**的两条路径，
                // 即 #5/#7/#8（RECOVER_CONFIRM / RECOVER_DIRECT）；#4 未使用回收的袋直接回空闲、
                // 并未产生"归还"，计入会让看板的"今日还"虚高。
                // 逐袋调用一次是 StatsService 明确定义的契约（命中幂等回放的成功项不会走到这里）。
                BagEvent routedEvent = BagEvent.of(outcome.item().getEvent());
                if (routedEvent == BagEvent.RECOVER_CONFIRM || routedEvent == BagEvent.RECOVER_DIRECT) {
                    statsService.incrementTodayReturn(stationId);
                }
            }
        }

        // 统一信封口径（设计书 6.2）：total/success/failed/items 由 BatchResult.of 按逐袋
        // 元素 success 标志汇总，不在业务侧再手写一套计数，避免两处口径漂移
        RecycleConfirmVo vo = RecycleConfirmVo.of(results);
        vo.setProxy(proxyCount);
        vo.setCrossStation(crossStationCount);
        vo.setDirectWithoutMark(directWithoutMarkCount);
        log.info("回收确认完成：stationId={}, operatorId={}, proxy={}, total={}, success={}, failed={}, crossStation={}, directWithoutMark={}",
                stationId, operatorId, proxy, vo.getTotal(), vo.getSuccess(), vo.getFailed(),
                crossStationCount, directWithoutMarkCount);

        publishRecycleAudit(stationId, operatorId, operatorRole, proxy,
                crossStationBags, directWithoutMarkBags, plainBags);
        return vo;
    }

    /**
     * 按上下文投递三档审计动作（见《系统设计说明书》7.6：回收确认按直投/跨站/未标记直投写不同 action）。
     *
     * <p>为什么不让 Controller 上的 {@code @AuditLog} 直接写这三个动作：注解的 action 是**静态字符串**，
     * 而这一批到底算哪一档要跑完才知道——一批里可能三种都有。因此 Controller 仍写一行汇总的
     * 「回收确认」，这里再按实际命中的档位补投明细行；查询侧用
     * {@code GET /audit-logs?action=回收确认-跨站} 即可直接筛出重点核查项。
     *
     * <p>只对本批实际出现的档位投递，不产生空记录；投递失败不影响业务（监听端也不抛）。
     */
    private void publishRecycleAudit(Long stationId, Long operatorId, String operatorRole, boolean proxy,
                                     List<String> crossStationBags, List<String> directWithoutMarkBags,
                                     List<String> plainBags) {
        publishOneAuditAction(stationId, operatorId, operatorRole, proxy,
                "回收确认-跨站", crossStationBags);
        publishOneAuditAction(stationId, operatorId, operatorRole, proxy,
                "回收确认-未标记直投", directWithoutMarkBags);
        publishOneAuditAction(stationId, operatorId, operatorRole, proxy,
                "回收确认-直投", plainBags);
    }

    private void publishOneAuditAction(Long stationId, Long operatorId, String operatorRole, boolean proxy,
                                       String action, List<String> bagCodes) {
        if (bagCodes.isEmpty()) {
            return;
        }
        try {
            AuditEvent event = new AuditEvent();
            event.setOperatorId(operatorId);
            event.setOperatorRole(operatorRole);
            event.setModule("站点管理");
            event.setAction(action);
            event.setRequestUri("/api/v1/stations/" + stationId + "/recycle-confirm");
            event.setParamsMasked("袋数=" + bagCodes.size()
                    + "，袋码=" + String.join(",", bagCodes)
                    + "，代操作=" + (proxy ? "是" : "否"));
            event.setResult(1);
            event.setErrorCode(0);
            event.setCostMs(0);
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.error("回收确认审计动作投递失败（不影响业务）：action={}", action, e);
        }
    }

    /**
     * 逐袋状态机拒绝（4102）审计（设计书 7.6：「状态机拒绝（4102）必须入审计」且需可按动作筛出）。
     *
     * <p>为什么要单独投递：批量接口把单袋 4102 收敛为逐袋 item 结果（单只失败不影响其余），
     * 异常冒泡不到 Controller，{@code @AuditLog} 切面只看到整批成功——审计里既查不到哪只袋被拒、
     * 也看不到原因（切面的「状态机拒绝」动作归一只能作用于冒泡到切面的异常）。
     * 投递走 common 包的 AuditEvent（发布方不依赖 module/audit，依赖方向见契约第 1 节），
     * 订阅端为 AuditLogService；投递失败只记日志，不影响拒绝结果本身。
     */
    private void publishStateMachineRejection(Long stationId, Long operatorId, String operatorRole,
                                              String bagCode, BagEvent event, String reason) {
        try {
            AuditEvent auditEvent = new AuditEvent();
            auditEvent.setOperatorId(operatorId);
            auditEvent.setOperatorRole(operatorRole);
            auditEvent.setModule("站点管理");
            auditEvent.setAction("状态机拒绝");
            auditEvent.setRequestUri("/api/v1/stations/" + stationId + "/recycle-confirm");
            auditEvent.setParamsMasked("袋码=" + bagCode
                    + "，事件=" + (event == null ? "-" : event.code())
                    + "，原因=" + reason);
            auditEvent.setResult(0);
            auditEvent.setErrorCode(ErrorCode.BAG_ILLEGAL_TRANSITION.getCode());
            auditEvent.setCostMs(0);
            eventPublisher.publishEvent(auditEvent);
        } catch (Exception e) {
            log.error("状态机拒绝审计投递失败（不影响业务）：bagCode={}", bagCode, e);
        }
    }

    /** 单袋确认：查袋 → 状态校验 → 取最新订单 → 路由 → 风控判定 → 逐袋幂等 → 状态机流转。 */
    private Outcome confirmOne(Long stationId, RecycleConfirmItemDto item, String clientRequestId,
                               Long operatorId, String operatorRole, boolean proxy) {
        String bagCode = item.getBagCode() == null ? "" : item.getBagCode().trim();

        // 1) 资源存在性：袋码不存在 → 4106（先存在性、后状态，见契约 6 校验顺序）
        Bag bag = bagMapper.selectOne(new LambdaQueryWrapper<Bag>().eq(Bag::getBagCode, bagCode));
        if (bag == null) {
            return fail(item, bagCode, ErrorCode.BAG_NOT_FOUND, "袋码不存在：" + bagCode);
        }

        // 2) 状态校验：仅 {已分配, 使用中} 可进入回收流程，其余（空闲/待消毒/已确认等）报 4102。
        //    校验通过前不占用逐袋幂等键：校验失败的袋不落键、重试可正常复检（同 BagService 既定约定），
        //    否则失败袋的键会被重复条目/重试反推为「已确认完成」，未确认的袋被计入成功数（9.6）
        BagStatus from = BagStatus.of(bag.getStatus());
        if (!RECOVERABLE.contains(from)) {
            String reason = "袋当前状态为「" + from.displayName() + "」，不在回收流程中";
            // 4102 是审计必审事件，但异常在此被收敛为逐袋结果、冒泡不到切面，需主动补投（7.6）
            publishStateMachineRejection(stationId, operatorId, operatorRole, bagCode, null, reason);
            return fail(item, bagCode, ErrorCode.BAG_ILLEGAL_TRANSITION, reason);
        }

        // 3) 绑定订单：该袋最新一笔订单（created_at 倒序取一条，同 3.2 表后取用规则）
        BagOrder order = latestOrder(bagCode);

        // 4) 按绑定订单状态自动路由 #4/#5/#7/#8
        boolean shippedBefore = Boolean.TRUE.equals(item.getShippedBefore());
        BagEvent event;
        if (from == BagStatus.ALLOCATED) {
            if (shippedBefore) {
                // 「曾寄出」迟寄补救：按 #5 待消毒路径处理
                event = BagEvent.RECOVER_CONFIRM;
            } else if (order == null || OrderStatus.CANCELLED.code().equals(order.getStatus())) {
                event = BagEvent.RECOVER_UNUSED;
            } else if (OrderStatus.PENDING_SEND.code().equals(order.getStatus())) {
                event = BagEvent.RECOVER_CONFIRM;
            } else {
                return fail(item, bagCode, ErrorCode.ORDER_STATE_NOT_ALLOWED,
                        "绑定订单状态为「" + OrderStatus.of(order.getStatus()).displayName() + "」，与袋状态不匹配，不允许回收确认");
            }
        } else if (from == BagStatus.IN_USE) {
            event = order != null && OrderStatus.PENDING_CONFIRM.code().equals(order.getStatus())
                    ? BagEvent.RECOVER_CONFIRM : BagEvent.RECOVER_DIRECT;
        } else {
            event = BagEvent.RECOVER_CONFIRM;
        }

        // 5) 风控判定（3.5）：同站点同操作人 10 分钟窗口内确认类操作 ≥20 次即触发 5101。
        //    刻意前置到流转之前：若像旧实现那样只在成功后记 WARN，风控不阻断；若放到成功后抛 5101，
        //    又会「袋已确认、响应却报失败」。计数按袋累计（口径见 recordRisk）。
        RiskGate risk = recordRisk(stationId, operatorId);
        if (!risk.allowed()) {
            return fail(item, bagCode, ErrorCode.RATE_LIMITED,
                    "同站点确认类操作频次异常（10 分钟内已达 " + risk.count() + " 次，阈值 "
                            + RISK_CONFIRM_LIMIT + "），已触发风控，请稍后重试");
        }

        // 6) 逐袋幂等：键 = clientRequestId + bagCode，校验全部通过后才占用（校验失败不落键）
        if (!idempotentService.tryAcquireBag(clientRequestId, bagCode)) {
            return idempotentSkip(item, bag, bagCode);
        }

        // 7) 状态机流转：站点归属改为确认站点（#4 同口径）；逐袋幂等键已由本层取得，
        //    不再把 clientRequestId 交给状态机，避免同键被状态机误判为「重复请求」而按回放跳过。
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (order != null) {
            attributes.put("orderId", order.getId());
            attributes.put("orderNo", order.getOrderNo());
        }
        attributes.put("shippedBefore", shippedBefore);

        BagTransitionResult result;
        try {
            result = transitionService.fire(TransitionCommand.builder()
                    .bagCode(bagCode)
                    .event(event)
                    .operatorId(operatorId)
                    .operatorRole(operatorRole)
                    .stationId(stationId)
                    .targetStationId(stationId)
                    .proxy(proxy)
                    .attributes(attributes)
                    .build());
        } catch (BizException e) {
            // 单只失败不影响其余：流转事务已随异常回滚（袋状态、订单、计数一起撤销）。
            // 状态机拒绝（4102）被收敛为逐袋结果、冒泡不到切面，需在这里补投「状态机拒绝」审计（7.6）
            if (e.getErrorCode() == ErrorCode.BAG_ILLEGAL_TRANSITION) {
                publishStateMachineRejection(stationId, operatorId, operatorRole, bagCode, event, e.getMessage());
            }
            return fail(item, bagCode, e.getErrorCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("回收确认单袋执行异常（stationId={}, bagCode={}）", stationId, bagCode, e);
            return fail(item, bagCode, ErrorCode.SYSTEM_ERROR, "系统异常，该袋确认失败");
        }

        // 8) 逐袋明细与批次上下文计数
        boolean crossStation = !stationId.equals(bag.getStationId());
        boolean directWithoutMark = event == BagEvent.RECOVER_DIRECT
                && (order == null || !OrderStatus.PENDING_CONFIRM.code().equals(order.getStatus()));
        item.setSuccess(true);
        item.setCode(ErrorCode.SUCCESS.getCode());
        item.setEvent(event.code());
        item.setToStatus(result.getToStatus() == null ? null : result.getToStatus().code());
        item.setMessage("回收确认成功（" + event.displayName() + "）："
                + from.displayName() + " → "
                + (result.getToStatus() == null ? "?" : result.getToStatus().displayName()));
        return new Outcome(item, proxy, crossStation, directWithoutMark);
    }

    /**
     * 逐袋幂等命中处理（前次同 clientRequestId 的同袋请求未真正提交完成时的续跑保护）：
     * 能走到这里说明本请求内的校验已通过（袋仍处已分配/使用中），即前次的幂等键已消费但流转未生效
     * （失败或中断——Redis 键不随 DB 事务回滚），本次不再重复执行，提示更换 clientRequestId 重试。
     *
     * <p>为什么不再用「袋已离开已分配/使用中 ⇒ 视为前次成功」反推成功（旧实现）：幂等键现在只在
     * 校验通过后才占用，校验失败的袋不落键；继续把「不在这两种状态」当作前次成功的证据，
     * 会把本就不可回收的袋或重复条目的第二次出现报成「已确认完成」，
     * 未确认的袋被计入成功数并提示已完成（违背 9.6 的幂等语义与 6.2 的部分失败口径）。
     */
    private Outcome idempotentSkip(RecycleConfirmItemDto item, Bag bag, String bagCode) {
        BagStatus current = BagStatus.of(bag.getStatus());
        return fail(item, bagCode, ErrorCode.ORDER_STATE_NOT_ALLOWED,
                "该袋在当前幂等窗口内已被处理但未生效（当前状态：" + current.displayName()
                        + "），如需重新确认请更换 clientRequestId");
    }

    /**
     * 风控计数与判定（设计书 3.5：同站点同操作人 10 分钟内确认类操作 ≥20 次 → 触发 5101 并列入 /alerts）。
     *
     * <p>固定窗口：窗口内首次计数时设置 TTL，之后只增不减、到期自动清零；
     * 计数口径为「每进入流转的确认操作记一次」——批量确认按袋累计，避免整批只记一次而漏掉高频自动化滥用。
     * /alerts 的第七类由 AlertService 读同一 Redis 键（阈值同为 20）。
     *
     * @return 是否放行本次操作与当前窗口计数；Redis 故障时降级放行（风控是旁路能力，不阻断业务）
     */
    private RiskGate recordRisk(Long stationId, Long operatorId) {
        if (stationId == null || operatorId == null) {
            return new RiskGate(true, 0);
        }
        try {
            String key = RedisKeys.auditRisk(String.valueOf(stationId), String.valueOf(operatorId));
            Long count = redis.opsForValue().increment(key);
            redis.expire(key, RISK_WINDOW);
            if (count != null && count >= RISK_CONFIRM_LIMIT) {
                // 只在本窗口首次达阈值时告警一次，避免批量接口逐袋刷 WARN；后续拒绝仍逐袋返回 5101
                if (count == RISK_CONFIRM_LIMIT) {
                    log.warn("风控告警：站点 {} 操作人 {} 在 {} 分钟内确认类操作已达 {} 次（阈值 {}，见设计书 3.5），已按 5101 拒绝后续操作",
                            stationId, operatorId, RISK_WINDOW.toMinutes(), count, RISK_CONFIRM_LIMIT);
                }
                return new RiskGate(false, count);
            }
            return new RiskGate(true, count == null ? 0 : count);
        } catch (Exception e) {
            // 风控计数是旁路能力：失败只记日志、本次放行，不影响回收确认主链路
            log.warn("风控计数写入失败（stationId={}, operatorId={}），本次放行：{}", stationId, operatorId, e.getMessage());
            return new RiskGate(true, 0);
        }
    }

    /** 该袋最新一笔订单（created_at 倒序、同刻按 id 倒序保证确定性）。 */
    private BagOrder latestOrder(String bagCode) {
        List<BagOrder> orders = bagOrderMapper.selectList(new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getBagCode, bagCode)
                .orderByDesc(BagOrder::getCreatedAt)
                .orderByDesc(BagOrder::getId));
        return orders.isEmpty() ? null : orders.get(0);
    }

    /** 批量取各袋最新订单：一次 IN 查询 + 内存去重（列表已按时间倒序，每袋首条即最新）。 */
    private Map<String, BagOrder> latestOrdersByBag(List<String> bagCodes) {
        if (bagCodes == null || bagCodes.isEmpty()) {
            return Map.of();
        }
        List<BagOrder> orders = bagOrderMapper.selectList(new LambdaQueryWrapper<BagOrder>()
                .in(BagOrder::getBagCode, bagCodes)
                .orderByDesc(BagOrder::getCreatedAt)
                .orderByDesc(BagOrder::getId));
        Map<String, BagOrder> latest = new HashMap<>();
        for (BagOrder order : orders) {
            latest.putIfAbsent(order.getBagCode(), order);
        }
        return latest;
    }

    /** 组装回收箱清单条目。 */
    private static RecycleBinVo toBinVo(Bag bag, BagOrder order, boolean marked) {
        RecycleBinVo vo = new RecycleBinVo();
        vo.setBagCode(bag.getBagCode());
        vo.setStatus(bag.getStatus());
        vo.setStatusName(BagStatus.of(bag.getStatus()).displayName());
        vo.setMarked(marked);
        if (order != null) {
            vo.setOrderNo(order.getOrderNo());
            vo.setOrderStatus(order.getStatus());
            vo.setOrderStatusName(OrderStatus.of(order.getStatus()).displayName());
            vo.setReturnStationId(order.getReturnStationId());
            vo.setReturnMarkAt(order.getReturnMarkAt());
            vo.setShippedAt(order.getShippedAt());
        }
        return vo;
    }

    /** 逐袋明细写入失败结果；回参字段名固定为 bagCode/success/code/message。 */
    private static Outcome fail(RecycleConfirmItemDto item, String bagCode, ErrorCode code, String message) {
        item.setBagCode(bagCode);
        item.setSuccess(false);
        item.setCode(code.getCode());
        item.setMessage(message);
        return new Outcome(item, false, false, false);
    }

    /** 单袋执行结果与审计上下文标记（仅批内使用）。 */
    private record Outcome(RecycleConfirmItemDto item, boolean proxy, boolean crossStation, boolean directWithoutMark) {
    }

    /** 风控判定结果：是否放行 + 当前窗口计数（0 表示未取到计数）。 */
    private record RiskGate(boolean allowed, long count) {
    }
}
