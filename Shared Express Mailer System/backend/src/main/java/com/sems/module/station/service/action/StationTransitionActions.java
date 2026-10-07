package com.sems.module.station.service.action;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.infra.CounterService;
import com.sems.infra.StockService;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.entity.StockRecord;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.bag.mapper.StockRecordMapper;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.statemachine.BagTransitionContext;
import com.sems.module.statemachine.OrderStatus;
import com.sems.module.statemachine.TransitionAction;
import com.sems.push.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站点模块状态机伴随动作（见《系统设计说明书》3.2 状态转移表 #4/#5/#7/#8 的「伴随动作」列、
 * 3.4.2「归还—消毒—复用流程」与 7.2 的 Redis 增减时机表）。
 *
 * <p><b>职责边界（契约第 2 节）：</b>状态机已完成袋状态的条件 UPDATE 与流转日志写入；
 * 本类只做其余伴随动作——订单联动（bag_order 状态与时间戳）、Redis 计数增减、业务记录写入
 * （#4 写 stock_record(IN)）与事务提交后推送；<b>不改袋状态、不写流转日志</b>。
 * #5/#7/#8 的「写回收记录」由状态机写入的流转日志（bag_transfer_log）承载（库中无独立回收表），
 * 本类不重复写流水。
 *
 * <p><b>为什么用「共享分派器 + 每事件一个静态内部类」：</b>状态机的 {@link TransitionAction}
 * 一个实现只声明一个 {@code event()}（一事件一 Bean，重复注册会启动失败），而三个回收事件
 * 的伴随动作高度同构，因此由外部类 {@link StationTransitionActions}（@Component）集中依赖与
 * {@code switch(event)} 分派，三个内部类只做事件声明与转发。
 *
 * <p><b>回收确认的三档审计 action 口径（7.6；审计切面对接口统一记「回收确认」，
 * 本模块按上下文返回计数供审计/看板区分）：</b>
 * <ul>
 *   <li><b>回收确认-跨站</b>：袋原站点 ≠ 操作人站点，或平台管理员代操作（proxy）；</li>
 *   <li><b>回收确认-未标记直投</b>：袋为使用中且绑定订单非「待回收确认」（用户未标记归还即投放，走 #7）；</li>
 *   <li><b>回收确认-直投</b>：其余常规确认（#4 未使用回收、#5 未寄件回收、#8 已标记在途回收）。</li>
 * </ul>
 */
@Component
public class StationTransitionActions {

    private static final Logger log = LoggerFactory.getLogger(StationTransitionActions.class);

    /** 出入库流水类型：入库（stock_record.type，值域见建表脚本注释 IN/OUT/SCRAP_OUT）。 */
    private static final String STOCK_RECORD_TYPE_IN = "IN";

    private final StockService stockService;
    private final CounterService counterService;
    private final StockRecordMapper stockRecordMapper;
    private final BagMapper bagMapper;
    private final BagOrderMapper bagOrderMapper;
    private final PushService pushService;

    public StationTransitionActions(StockService stockService,
                                    CounterService counterService,
                                    StockRecordMapper stockRecordMapper,
                                    BagMapper bagMapper,
                                    BagOrderMapper bagOrderMapper,
                                    PushService pushService) {
        this.stockService = stockService;
        this.counterService = counterService;
        this.stockRecordMapper = stockRecordMapper;
        this.bagMapper = bagMapper;
        this.bagOrderMapper = bagOrderMapper;
        this.pushService = pushService;
    }

    /**
     * 按事件分派伴随动作（本类实现 RECOVER_UNUSED / RECOVER_CONFIRM / RECOVER_DIRECT 三个事件；
     * 其余事件属编码错误，直接抛错让流转事务回滚并暴露问题）。
     */
    private void apply(BagEvent event, BagTransitionContext ctx) {
        switch (event) {
            case RECOVER_UNUSED -> handleRecoverUnused(ctx);
            case RECOVER_CONFIRM -> handleRecoverConfirm(ctx);
            case RECOVER_DIRECT -> handleRecoverDirect(ctx);
            default -> throw new IllegalStateException("站点模块不支持的流转事件：" + event.code());
        }
    }

    // ==================== #4 未使用回收（已分配 → 空闲） ====================

    /**
     * #4 伴随动作（3.2 表 #4）：在持袋数 −1、Redis 库存 +1、写入库记录；
     * 订单已取消或不存在，仅做闭环核对（无需改状态）。袋归属已由状态机改为确认站点。
     */
    private void handleRecoverUnused(BagTransitionContext ctx) {
        BagStatus from = BagStatus.of(ctx.getBag().getStatus());
        if (from != BagStatus.ALLOCATED) {
            throw new IllegalStateException("RECOVER_UNUSED 不支持的袋前态：" + from.code());
        }
        Long stationId = confirmStation(ctx);
        BagOrder order = resolveOrder(ctx);

        // Redis 库存 +1（7.2 增减时机表：#4 库存 +1；异常已在 StockService 内吞掉并交 R4 对账）
        if (stationId != null) {
            stockService.increaseAvailable(stationId, 1);
            // 入库流水（#4「写入库记录」）
            StockRecord record = new StockRecord();
            record.setStationId(stationId);
            record.setBagCode(ctx.getBag().getBagCode());
            record.setType(STOCK_RECORD_TYPE_IN);
            record.setOperatorId(ctx.getOperatorId());
            record.setRemark("未使用回收（#4）入库");
            record.setCreatedAt(LocalDateTime.now());
            stockRecordMapper.insert(record);
        } else {
            log.warn("袋 {} 未使用回收时确认站点为空，跳过库存计数与入库流水（由对账纠正）", ctx.getBag().getBagCode());
        }

        // 在持袋数 −1（订单用户；#4 请求可能无订单，此时无法定位用户，记日志由每日开单计数对账纠偏）
        decrementOpenQuietly(order == null ? null : order.getUserId(), ctx.getBag().getBagCode());
        // 订单为已取消/不存在：无状态变化，不推送
    }

    // ==================== #5 / #8 回收确认（共用 RECOVER_CONFIRM） ====================

    /**
     * RECOVER_CONFIRM 伴随动作：按前态区分 #5（已分配，订单→已取消）与 #8（使用中且订单待回收确认，订单→已完成、use_count+1）；
     * 两者都做在持袋数 −1、库存不变、推送订单变更。迟寄补救（shippedBefore=true）在 #5 路径计 use_count。
     */
    private void handleRecoverConfirm(BagTransitionContext ctx) {
        BagStatus from = BagStatus.of(ctx.getBag().getStatus());
        BagOrder order = resolveOrder(ctx);
        boolean shippedBefore = Boolean.TRUE.equals(attr(ctx, "shippedBefore"));
        Long stationId = confirmStation(ctx);
        OrderStatus changedTo = null;

        if (from == BagStatus.ALLOCATED) {
            // #5：订单→已取消（取消-回收闭环）；订单已取消时保持原状
            if (order != null && !OrderStatus.CANCELLED.code().equals(order.getStatus())) {
                updateOrderStatus(order, OrderStatus.CANCELLED, LocalDateTime.now(), null);
                changedTo = OrderStatus.CANCELLED;
            }
            // 「曾寄出」标注命中：迟寄补救，计 use_count（3.2 #4 表后说明）
            if (shippedBefore) {
                incrementUseCount(ctx.getBag());
            }
        } else if (from == BagStatus.IN_USE) {
            // #8：订单→已完成、use_count+1（与 #7 相同的伴随动作）
            if (order != null && !OrderStatus.FINISHED.code().equals(order.getStatus())) {
                updateOrderStatus(order, OrderStatus.FINISHED, null, LocalDateTime.now());
                changedTo = OrderStatus.FINISHED;
            }
            incrementUseCount(ctx.getBag());
        } else {
            // 注册表保证 RECOVER_CONFIRM 仅从已分配/使用中进入（3.2 #5/#8），其余属编码错误
            throw new IllegalStateException("RECOVER_CONFIRM 不支持的袋前态：" + from.code());
        }

        // 在持袋数 −1（#5/#8 同口径；库存不变）
        decrementOpenQuietly(order == null ? null : order.getUserId(), ctx.getBag().getBagCode());
        // 订单状态发生变更时推送本人（事务提交后）
        if (order != null && changedTo != null) {
            pushOrderChange(order, changedTo, ctx, stationId);
        }
    }

    // ==================== #7 回收确认（直投，使用中 → 待消毒） ====================

    /**
     * #7 伴随动作：订单→已完成、use_count+1、在持袋数 −1、库存不变、推送订单变更
     * （用户未标记归还直接投放时不得拒绝回收，见 3.4.2 与 FR-M3-10）。
     */
    private void handleRecoverDirect(BagTransitionContext ctx) {
        BagStatus from = BagStatus.of(ctx.getBag().getStatus());
        if (from != BagStatus.IN_USE) {
            throw new IllegalStateException("RECOVER_DIRECT 不支持的袋前态：" + from.code());
        }
        BagOrder order = resolveOrder(ctx);
        Long stationId = confirmStation(ctx);
        OrderStatus changedTo = null;

        if (order != null && !OrderStatus.FINISHED.code().equals(order.getStatus())) {
            updateOrderStatus(order, OrderStatus.FINISHED, null, LocalDateTime.now());
            changedTo = OrderStatus.FINISHED;
        }
        incrementUseCount(ctx.getBag());
        decrementOpenQuietly(order == null ? null : order.getUserId(), ctx.getBag().getBagCode());
        if (order != null && changedTo != null) {
            pushOrderChange(order, changedTo, ctx, stationId);
        }
    }

    // ==================== 共用实现 ====================

    /**
     * 订单状态联动更新（3.6：订单状态跟随袋状态联动，不允许单独改订单状态——
     * 本方法只在回收确认的伴随动作中被调用）。
     *
     * <p>用条件 UPDATE（{@code WHERE id=? AND version=?}）推进版本，影响行数为 0 说明订单被
     * 并发变更，抛 4105 让整笔流转回滚，避免「袋已回收、订单没闭环」的半截数据。
     */
    private void updateOrderStatus(BagOrder order, OrderStatus target, LocalDateTime cancelledAt, LocalDateTime confirmedAt) {
        UpdateWrapper<BagOrder> update = new UpdateWrapper<>();
        update.eq("id", order.getId())
                .eq("version", order.getVersion())
                .set("status", target.code())
                .set("version", order.getVersion() + 1);
        if (cancelledAt != null) {
            update.set("cancelled_at", cancelledAt);
        }
        if (confirmedAt != null) {
            update.set("confirmed_at", confirmedAt);
        }
        int rows = bagOrderMapper.update(null, update);
        if (rows == 0) {
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "订单状态已被其他操作变更，请刷新后重试");
        }
    }

    /**
     * use_count 原子 +1（3.5：唯一递增点 = 回收确认 #7/#8，及 #5 的「曾寄出」迟寄补救）。
     *
     * <p>同事务内袋行刚被状态机条件 UPDATE 串行化，前态快照的 use_count 不会与并发写冲突，
     * 以前态 +1 写回可避免读改写竞态；本方法不改袋状态（状态由状态机唯一写入口负责）。
     */
    private void incrementUseCount(Bag bag) {
        int next = (bag.getUseCount() == null ? 0 : bag.getUseCount()) + 1;
        UpdateWrapper<Bag> update = new UpdateWrapper<>();
        update.eq("bag_code", bag.getBagCode()).set("use_count", next);
        bagMapper.update(null, update);
    }

    /**
     * 在持袋数 −1（7.2 增减时机表：#4/#5/#8 与 #7 一致，袋离开已分配/使用中两态即 −1）。
     * 计数字段由 Redis 承载：写入失败只记 WARN 不阻断流转（DB 才是权威），
     * 由每日开单计数对账纠偏（7.5「开单计数对账」）。
     */
    private void decrementOpenQuietly(Long userId, String bagCode) {
        if (userId == null) {
            log.warn("袋 {} 回收确认时无法定位绑定用户，跳过在持袋数递减（由每日开单计数对账纠偏）", bagCode);
            return;
        }
        try {
            counterService.decrementOpenFloor0(userId);
        } catch (Exception e) {
            log.warn("在持袋数递减失败（userId={}, bagCode={}），本次跳过，由每日开单计数对账纠偏：{}",
                    userId, bagCode, e.getMessage());
        }
    }

    /** 订单状态变更推送（7.3 user/{uid}/order 主题；必须包在 afterCommit 中，回滚则不发）。 */
    private void pushOrderChange(BagOrder order, OrderStatus changedTo, BagTransitionContext ctx, Long stationId) {
        if (order.getUserId() == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("bagCode", ctx.getBag().getBagCode());
        data.put("orderStatus", changedTo.code());
        data.put("orderStatusName", changedTo.displayName());
        data.put("event", ctx.getCmd().getEvent().code());
        data.put("stationId", stationId);
        Long userId = order.getUserId();
        pushService.afterCommit(() -> pushService.pushUserOrder(userId, data));
    }

    /**
     * 取本次流转绑定的订单：优先用命令 attributes 中的 orderId（回收确认入口携带）；
     * 缺省时按「该袋最新一笔订单（created_at 倒序）」兜底，与 3.2 表后 #4/#5 取用规则同口径
     * （兼容袋管理模块的未使用回收接口等未携带订单参数的调用方）。
     */
    private BagOrder resolveOrder(BagTransitionContext ctx) {
        Object orderId = attr(ctx, "orderId");
        if (orderId instanceof Number number) {
            return bagOrderMapper.selectById(number.longValue());
        }
        return latestOrder(ctx.getBag().getBagCode());
    }

    /** 该袋最新一笔订单（created_at 倒序、同刻按 id 倒序保证确定性）。 */
    private BagOrder latestOrder(String bagCode) {
        List<BagOrder> orders = bagOrderMapper.selectList(new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getBagCode, bagCode)
                .orderByDesc(BagOrder::getCreatedAt)
                .orderByDesc(BagOrder::getId));
        return orders.isEmpty() ? null : orders.get(0);
    }

    /** 本次流转的确认站点：目标站点优先（跨站修正/调拨等显式指定），缺省取操作站点。 */
    private static Long confirmStation(BagTransitionContext ctx) {
        Long target = ctx.getCmd().getTargetStationId();
        return target != null ? target : ctx.getCmd().getStationId();
    }

    /** 读命令扩展参数（可能为空 Map）。 */
    private static Object attr(BagTransitionContext ctx, String name) {
        Map<String, Object> attributes = ctx.getCmd().getAttributes();
        return attributes == null ? null : attributes.get(name);
    }

    // ==================== 每事件一个适配内部类（TransitionAction 一事件一 Bean） ====================

    /** #4 未使用回收（RECOVER_UNUSED）适配器。 */
    @Component
    public static class RecoverUnusedAction implements TransitionAction {

        private final StationTransitionActions dispatcher;

        public RecoverUnusedAction(StationTransitionActions dispatcher) {
            this.dispatcher = dispatcher;
        }

        @Override
        public BagEvent event() {
            return BagEvent.RECOVER_UNUSED;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            dispatcher.apply(event(), ctx);
        }
    }

    /** #5 / #8 回收确认（RECOVER_CONFIRM）适配器。 */
    @Component
    public static class RecoverConfirmAction implements TransitionAction {

        private final StationTransitionActions dispatcher;

        public RecoverConfirmAction(StationTransitionActions dispatcher) {
            this.dispatcher = dispatcher;
        }

        @Override
        public BagEvent event() {
            return BagEvent.RECOVER_CONFIRM;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            dispatcher.apply(event(), ctx);
        }
    }

    /** #7 回收确认（直投，RECOVER_DIRECT）适配器。 */
    @Component
    public static class RecoverDirectAction implements TransitionAction {

        private final StationTransitionActions dispatcher;

        public RecoverDirectAction(StationTransitionActions dispatcher) {
            this.dispatcher = dispatcher;
        }

        @Override
        public BagEvent event() {
            return BagEvent.RECOVER_DIRECT;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            dispatcher.apply(event(), ctx);
        }
    }
}
