package com.sems.module.order.service.action;

import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.infra.CounterService;
import com.sems.infra.StockService;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.entity.LiabilityRecord;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.order.mapper.LiabilityRecordMapper;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagTransitionContext;
import com.sems.module.statemachine.OrderStatus;
import com.sems.module.statemachine.TransitionAction;
import com.sems.module.statemachine.TransitionCommand;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sems.push.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * module/order 负责的四个状态机伴随动作：{@code BORROW / SHIP / RETURN_MARK / LOST_CONFIRM}
 * （见《系统设计说明书》3.2 状态转移表「伴随动作」列 #2/#3/#6/#18；契约模块版第 2 节事件归属表）。
 *
 * <p>职责边界（契约模块版第 2 节）：状态机已完成「袋状态条件 UPDATE + 流转日志」，
 * 本类只做其余伴随动作——订单联动、Redis 计数增减、责任记录写入与推送；
 * <b>不得再改袋状态</b>，也不得新开事务（动作本身运行在状态机事务内，抛异常则整笔流转回滚）。
 *
 * <p>为什么用一个类承载四个动作：四段逻辑共享同一批依赖与「订单加载 + 版本守卫 + 推送」
 * 三个私有工具方法，集中在一处可避免四个实现各自复制、口径漂移。对外暴露则采用
 * 「一个事件一个内部类」——{@code BagTransitionService} 按 {@code event()} 注册动作，
 * 同一事件只能有一个实现，四个薄适配器各自声明一个事件并委托回 {@link #apply} 的 switch 分派。
 *
 * <p>推送纪律（契约模块版 11）：所有 WebSocket 推送必须包在 {@link PushService#afterCommit}
 * 中——动作在事务内执行，事务回滚时消息不得发出。
 */
@Component
public class OrderTransitionActions {

    private static final Logger log = LoggerFactory.getLogger(OrderTransitionActions.class);

    /** 责任记录业务类型：订单（见设计书 8.2 {@code liability_record.biz_type}）。 */
    private static final String LIABILITY_BIZ_TYPE_ORDER = "ORDER";

    /** 责任类型：遗失（设计书 8.2 的取值域：遗失/在途遗失/超时）。 */
    private static final String LIABILITY_TYPE_LOST = "遗失";

    /** 责任状态：已认定（确认遗失即出结论，见设计书 8.2）。 */
    private static final String LIABILITY_STATUS_CONFIRMED = "CONFIRMED";

    /** 今日统计 Hash 的取袋域（见设计书 8.3 {@code stats:today:{sid}} 写入点）。 */
    private static final String STATS_FIELD_BORROW = "borrow";

    private final BagOrderMapper bagOrderMapper;
    private final LiabilityRecordMapper liabilityRecordMapper;
    private final CounterService counterService;
    private final StockService stockService;
    private final PushService pushService;

    public OrderTransitionActions(BagOrderMapper bagOrderMapper,
                                  LiabilityRecordMapper liabilityRecordMapper,
                                  CounterService counterService,
                                  StockService stockService,
                                  PushService pushService) {
        this.bagOrderMapper = bagOrderMapper;
        this.liabilityRecordMapper = liabilityRecordMapper;
        this.counterService = counterService;
        this.stockService = stockService;
        this.pushService = pushService;
    }

    /**
     * 按事件分派伴随动作（由四个内部适配器调用）。
     *
     * @param event 本模块承接的事件之一
     * @param ctx   流转上下文（袋快照为前态；扩展参数在 {@code ctx.getCmd().getAttributes()}）
     */
    public void apply(BagEvent event, BagTransitionContext ctx) {
        switch (event) {
            case BORROW -> applyBorrow(ctx);
            case SHIP -> applyShip(ctx);
            case RETURN_MARK -> applyReturnMark(ctx);
            case LOST_CONFIRM -> applyLostConfirm(ctx);
            default -> throw new IllegalStateException("module/order 未承接的事件：" + event);
        }
    }

    // ==================== #2 扫码申领（BORROW） ====================

    /**
     * 申领伴随动作：插入 {@code bag_order}（status=PENDING_SEND）并计站点今日取袋数。
     *
     * <p><b>不动 Redis</b>：库存扣减与在持袋数 +1 已由阶段一 Lua 原子完成（契约模块版第 3 节口径表），
     * 动作里重复增减会造成双写。
     *
     * <p>幂等兜底（契约 9 注释）：订单落库以 {@code client_request_id} 唯一键防重，
     * 并发重放命中唯一键时按幂等回放处理（跳过插入），而不是抛 500。
     */
    private void applyBorrow(BagTransitionContext ctx) {
        TransitionCommand cmd = ctx.getCmd();
        String orderNo = attrString(cmd, "orderNo");
        Long userId = attrLong(cmd, "userId");
        Long stationId = attrLong(cmd, "stationId");
        String clientRequestId = cmd.getClientRequestId();
        if (orderNo == null || userId == null || stationId == null
                || clientRequestId == null || clientRequestId.isBlank()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "申领伴随动作缺少必要参数");
        }

        // 落库前回查（契约 9 注释「唯一键冲突（并发重放）时按幂等回放处理」）：
        // 同一 clientRequestId 已有订单说明本请求此前已成功落库，跳过插入与今日计数
        BagOrder existing = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getClientRequestId, clientRequestId));
        if (existing == null) {
            existing = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                    .eq(BagOrder::getOrderNo, orderNo));
        }
        if (existing != null) {
            log.warn("申领落库幂等命中：clientRequestId={} 已存在订单 {}（本次 orderNo={}），按回放处理不重复插入",
                    clientRequestId, existing.getOrderNo(), orderNo);
            pushOrderAndStock(userId, stationId, orderNo, cmd.getBagCode(), OrderStatus.PENDING_SEND.code());
            return;
        }

        BagOrder order = new BagOrder();
        order.setOrderNo(orderNo);
        order.setClientRequestId(clientRequestId);
        order.setUserId(userId);
        order.setBagCode(cmd.getBagCode());
        order.setStationId(stationId);
        order.setStatus(OrderStatus.PENDING_SEND.code());
        order.setTimeoutFlag(0);
        // 显式写建单时间：24h 超时取消（R2）以 created_at 为基准（设计书 3.5），不能依赖库默认值之外的时序
        order.setCreatedAt(LocalDateTime.now());
        try {
            bagOrderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 并发重放的最后一层兜底：唯一键（client_request_id）撞车时回查确认，确认已落库则按回放处理
            BagOrder replay = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                    .eq(BagOrder::getClientRequestId, clientRequestId));
            if (replay != null) {
                log.warn("申领落库唯一键冲突，回查确认已提交，按幂等回放处理：orderNo={}", replay.getOrderNo());
                pushOrderAndStock(userId, stationId, orderNo, cmd.getBagCode(), OrderStatus.PENDING_SEND.code());
                return;
            }
            // 唯一键不是 client_request_id（理论上不可能）：属数据异常，抛出交由 R0 判定是否补偿
            throw new BizException(ErrorCode.SYSTEM_ERROR, "订单落库唯一键冲突，请重试");
        }

        statsTodayIncrementQuietly(stationId);
        pushOrderAndStock(userId, stationId, orderNo, cmd.getBagCode(), OrderStatus.PENDING_SEND.code());
    }

    // ==================== #3 提交寄件（SHIP） ====================

    /**
     * 提交寄件伴随动作：订单转 IN_USE，写寄件信息与 shipped_at（事务内与袋状态一起提交）。
     *
     * <p>动作内重查订单状态并做版本守卫：防止「撤回/超时取消」与「提交寄件」并发时，
     * 已取消订单被本动作覆盖为使用中（服务层校验发生在事务外，挡不住该竞态）。
     */
    private void applyShip(BagTransitionContext ctx) {
        TransitionCommand cmd = ctx.getCmd();
        String orderNo = attrString(cmd, "orderNo");
        BagOrder order = loadOrder(orderNo);

        if (!OrderStatus.PENDING_SEND.code().equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "订单当前状态不允许提交寄件");
        }

        BagOrder update = new BagOrder();
        update.setId(order.getId());
        update.setStatus(OrderStatus.IN_USE.code());
        update.setExpressCompany(attrString(cmd, "expressCompany"));
        update.setTrackingNo(attrString(cmd, "trackingNo"));
        update.setRecipientPhoneMask(attrString(cmd, "recipientPhoneMask"));
        update.setRecipientPhoneHash(attrString(cmd, "recipientPhoneHash"));
        update.setShippedAt(LocalDateTime.now());
        updateOrderGuarded(update, order.getVersion());

        pushOrderAndStock(order.getUserId(), order.getStationId(),
                orderNo, order.getBagCode(), OrderStatus.IN_USE.code());
    }

    // ==================== #6 标记归还（RETURN_MARK） ====================

    /**
     * 标记归还伴随动作：订单转 PENDING_CONFIRM，写 return_mark_at 与 return_station_id。
     *
     * <p><b>不改袋归属</b>：{@code bag.station_id} 由站点回收确认时改写（设计书 3.2 站点归属变更规则），
     * 此处只记录订单上的归还站点，供站点待办与跨站归还追溯。
     */
    private void applyReturnMark(BagTransitionContext ctx) {
        TransitionCommand cmd = ctx.getCmd();
        String orderNo = attrString(cmd, "orderNo");
        Long returnStationId = attrLong(cmd, "returnStationId");
        BagOrder order = loadOrder(orderNo);

        if (!OrderStatus.IN_USE.code().equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "订单当前状态不允许标记归还");
        }

        BagOrder update = new BagOrder();
        update.setId(order.getId());
        update.setStatus(OrderStatus.PENDING_CONFIRM.code());
        update.setReturnStationId(returnStationId);
        update.setReturnMarkAt(LocalDateTime.now());
        updateOrderGuarded(update, order.getVersion());

        pushOrderAndStock(order.getUserId(), returnStationId,
                orderNo, order.getBagCode(), OrderStatus.PENDING_CONFIRM.code());
    }

    // ==================== #18 确认遗失（LOST_CONFIRM） ====================

    /**
     * 确认遗失伴随动作（设计书 3.2 #18）：
     * 订单转 LOST 并写 lost_at（<b>含已取消订单的终态覆盖</b>）；在持袋数 −1；写责任记录；推送。
     *
     * <p>在持袋数用 {@link StockService#decrementOpen(Long)}（契约模块版第 3 节：袋离开三态时
     * 计数 −1，内部已做下限截断与截断续期）；撤回/超时取消留下的已取消订单同样参与本次 −1，
     * 因为其袋直到此刻才离开三态。走 StockService 而非直接操作 CounterService 的原因（7.2）：
     * 熔断期不写 Redis 计数（由恢复重建与每日对账纠偏），Redis 异常也只记 WARN 不阻断本笔流转
     * ——否则平台管理员在 Redis 故障时根本无法确认遗失/修正状态。
     */
    private void applyLostConfirm(BagTransitionContext ctx) {
        TransitionCommand cmd = ctx.getCmd();
        String orderNo = attrString(cmd, "orderNo");
        BagOrder order = loadOrder(orderNo);
        LocalDateTime now = LocalDateTime.now();

        BagOrder update = new BagOrder();
        update.setId(order.getId());
        update.setStatus(OrderStatus.LOST.code());
        update.setLostAt(now);
        updateOrderGuarded(update, order.getVersion());

        // 责任认定（设计书 3.2 表后说明）：结论/裁决人/裁决时间在此落库；
        // responsibleUserId 可空 = 责任待定，责任站点仅调拨类业务使用（biz_type=ALLOCATION）
        LiabilityRecord liability = new LiabilityRecord();
        liability.setBizType(LIABILITY_BIZ_TYPE_ORDER);
        liability.setBizNo(orderNo);
        liability.setType(LIABILITY_TYPE_LOST);
        liability.setResponsibleUserId(attrLong(cmd, "responsibleUserId"));
        liability.setConclusion(attrString(cmd, "conclusion"));
        liability.setDecidedBy(ctx.getOperatorId());
        liability.setDecidedAt(now);
        liability.setStatus(LIABILITY_STATUS_CONFIRMED);
        liability.setNote(attrString(cmd, "note"));
        liability.setCreatedAt(now);
        liabilityRecordMapper.insert(liability);

        // 袋离开三态（已分配/使用中/回收在途）→ 在持袋数 −1（3.5 口径）
        stockService.decrementOpen(order.getUserId());

        pushOrderAndStock(order.getUserId(), cmd.getStationId(),
                orderNo, order.getBagCode(), OrderStatus.LOST.code());
    }

    // ==================== 私有工具 ====================

    /**
     * 站点今日取袋统计 +1（8.3 写入点），按 7.2 降级纪律静默处理：
     * 熔断期不写 Redis 计数（恢复动作只重建 stock/borrow 计数，恢复当日的今日统计自然偏少，
     * 属降级期以 DB 为准的既定口径）；Redis 异常也只记 WARN——统计是展示数据、不是业务权威，
     * 绝不能把已落库的申领事务拖回滚。
     */
    private void statsTodayIncrementQuietly(Long stationId) {
        if (stockService.isDegraded()) {
            log.debug("库存熔断打开，跳过站点今日取袋统计（stationId={}）", stationId);
            return;
        }
        try {
            counterService.statsTodayIncrement(stationId, STATS_FIELD_BORROW);
        } catch (RuntimeException e) {
            log.warn("站点今日取袋统计写入失败（stationId={}），本次跳过：{}", stationId, e.getMessage());
        }
    }

    /** 按订单号装载订单；缺失属数据异常（服务层已校验存在性），抛 5000 由全局处理器兜底。 */
    private BagOrder loadOrder(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "伴随动作缺少订单号");
        }
        BagOrder order = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "伴随动作关联的订单不存在：" + orderNo);
        }
        return order;
    }

    /**
     * 版本守卫的订单更新：写入 {@code version} 触发 MyBatis-Plus 乐观锁
     * （{@code WHERE id=? AND version=?}，见设计书 3.6「订单表 version 乐观锁 + 条件更新」）；
     * 影响行数为 0 说明订单已被并发处理，抛 4105 终止本笔流转（整笔事务回滚）。
     */
    private void updateOrderGuarded(BagOrder update, Integer expectedVersion) {
        update.setVersion(expectedVersion);
        int rows = bagOrderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "订单状态已被其他请求变更，请刷新后重试");
        }
    }

    /**
     * 推送订单变更（用户主题）与站点库存主题；统一包在 {@code afterCommit} 中，
     * 保证消息只在事务提交后发出（见设计书 7.3）。
     */
    private void pushOrderAndStock(Long userId, Long stationId, String orderNo, String bagCode, String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", orderNo);
        data.put("bagCode", bagCode);
        data.put("status", status);
        pushService.afterCommit(() -> {
            if (userId != null) {
                pushService.pushUserOrder(userId, data);
            }
            if (stationId != null) {
                pushService.pushStationStock(stationId, data);
            }
        });
    }

    /** 从命令扩展参数取字符串值（缺省 null）。 */
    private static String attrString(TransitionCommand cmd, String key) {
        Object value = cmd.getAttributes() == null ? null : cmd.getAttributes().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 从命令扩展参数取 Long 值（兼容 Number/String 两种装入形式，缺省 null）。 */
    private static Long attrLong(TransitionCommand cmd, String key) {
        Object value = cmd.getAttributes() == null ? null : cmd.getAttributes().get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 事件适配器（一个事件一个 Bean，委托给上面的 switch 分派） ====================

    /** BORROW（扫码申领 #2）动作适配器。 */
    @Component
    static class BorrowAction implements TransitionAction {

        private final OrderTransitionActions delegate;

        BorrowAction(OrderTransitionActions delegate) {
            this.delegate = delegate;
        }

        @Override
        public BagEvent event() {
            return BagEvent.BORROW;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            delegate.apply(BagEvent.BORROW, ctx);
        }
    }

    /** SHIP（提交寄件 #3）动作适配器。 */
    @Component
    static class ShipAction implements TransitionAction {

        private final OrderTransitionActions delegate;

        ShipAction(OrderTransitionActions delegate) {
            this.delegate = delegate;
        }

        @Override
        public BagEvent event() {
            return BagEvent.SHIP;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            delegate.apply(BagEvent.SHIP, ctx);
        }
    }

    /** RETURN_MARK（标记归还 #6）动作适配器。 */
    @Component
    static class ReturnMarkAction implements TransitionAction {

        private final OrderTransitionActions delegate;

        ReturnMarkAction(OrderTransitionActions delegate) {
            this.delegate = delegate;
        }

        @Override
        public BagEvent event() {
            return BagEvent.RETURN_MARK;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            delegate.apply(BagEvent.RETURN_MARK, ctx);
        }
    }

    /** LOST_CONFIRM（确认遗失 #18）动作适配器。 */
    @Component
    static class LostConfirmAction implements TransitionAction {

        private final OrderTransitionActions delegate;

        LostConfirmAction(OrderTransitionActions delegate) {
            this.delegate = delegate;
        }

        @Override
        public BagEvent event() {
            return BagEvent.LOST_CONFIRM;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            delegate.apply(BagEvent.LOST_CONFIRM, ctx);
        }
    }
}
