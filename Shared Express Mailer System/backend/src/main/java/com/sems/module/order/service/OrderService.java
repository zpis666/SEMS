package com.sems.module.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.common.PageResult;
import com.sems.common.util.JsonUtils;
import com.sems.infra.IdempotentService;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.dto.LostConfirmDto;
import com.sems.module.order.dto.OrderDetailVo;
import com.sems.module.order.dto.OrderQuery;
import com.sems.module.order.dto.OrderVo;
import com.sems.module.order.dto.ReturnMarkDto;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.statemachine.BagTransitionResult;
import com.sems.module.statemachine.BagTransitionService;
import com.sems.module.statemachine.OrderStatus;
import com.sems.module.statemachine.TransitionCommand;
import com.sems.push.PushService;
import com.sems.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 快递订单服务：订单自助查询、标记归还、管理端全量查询与遗失确认
 * （见《系统设计说明书》5.2「共享快递袋服务模块」、5.5「快递订单管理模块（M5）」、
 * 3.2 #6/#18 与 3.6「订单状态设计」）。
 *
 * <p><b>数据权限</b>（设计书 4.4；契约模块版第 6 节）：本人接口一律
 * {@code WHERE user_id = 当前 uid}，且 DTO 刻意不提供 userId 入参，杜绝水平越权；
 * 查到记录后仍做归属二次比对兜底。订单不存在与非本人统一按 2002 返回——
 * 不向调用方泄露"他人订单是否存在"（防枚举）。
 *
 * <p><b>订单状态的唯一写入口</b>：订单联动一律经状态机伴随动作完成（见 OrderTransitionActions），
 * 本服务不直接改订单状态，标记归还/遗失确认都通过 {@code BagTransitionService.fire} 触发。
 */
@Service
public class OrderService {

    /** 接口级幂等键：HTTP 方法 + 路径模板（契约模块版第 4 节）。 */
    private static final String API_RETURN_MARK = "POST /orders/{orderNo}/return-mark";
    private static final String API_LOST_CONFIRM = "POST /orders/{orderNo}/lost-confirm";

    /** #18 遗失认定时限（设计书 3.2 #18、3.5）：使用中 → 寄件提交后 14 天。 */
    private static final Duration LOST_WINDOW_AFTER_SHIP = Duration.ofDays(14);

    /** #18 遗失认定时限：回收在途 → 标记归还后 72 小时。 */
    private static final Duration LOST_WINDOW_IN_RETURN = Duration.ofHours(72);

    /** #18 遗失认定时限：已分配 → 订单取消后 7 天（须人工核验后确认，见 3.5）。 */
    private static final Duration LOST_WINDOW_AFTER_CANCEL = Duration.ofDays(7);

    /** 排序白名单缺省字段：按建单时间倒序。 */
    private static final String DEFAULT_SORT_FIELD = "createdAt";

    private final BagOrderMapper bagOrderMapper;
    private final BagMapper bagMapper;
    private final StationMapper stationMapper;
    private final IdempotentService idempotentService;
    private final BagTransitionService bagTransitionService;
    private final PushService pushService;

    public OrderService(BagOrderMapper bagOrderMapper,
                        BagMapper bagMapper,
                        StationMapper stationMapper,
                        IdempotentService idempotentService,
                        BagTransitionService bagTransitionService,
                        PushService pushService) {
        this.bagOrderMapper = bagOrderMapper;
        this.bagMapper = bagMapper;
        this.stationMapper = stationMapper;
        this.idempotentService = idempotentService;
        this.bagTransitionService = bagTransitionService;
        this.pushService = pushService;
    }

    // ==================== 查询（用户侧） ====================

    /**
     * 本人订单分页查询（设计书 5.2；强制 {@code user_id = 当前 uid}，禁止前端传 userId）。
     *
     * @param query 分页与状态筛选条件
     * @return 订单分页结果
     */
    public PageResult<OrderVo> pageMine(OrderQuery query) {
        Long uid = AuthContext.userId();
        validateStatusFilter(query.getStatus());

        LambdaQueryWrapper<BagOrder> wrapper = new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getUserId, uid);                  // 数据权限：本人
        if (hasText(query.getStatus())) {
            wrapper.eq(BagOrder::getStatus, query.getStatus());
        }
        applyOrderBy(wrapper, query.getOrderBy());

        Page<BagOrder> page = new Page<>(query.pageNumOrDefault(), query.pageSizeOrDefault());
        return toPageResult(bagOrderMapper.selectPage(page, wrapper), query.pageSizeOrDefault());
    }

    /**
     * 本人订单详情（设计书 5.2：校验归属，非本人报 2002）。
     *
     * @param orderNo 订单号
     * @return 订单详情（含袋当前状态）
     */
    public OrderDetailVo detail(String orderNo) {
        Long uid = AuthContext.userId();
        BagOrder order = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getOrderNo, orderNo));
        // 先资源存在性、后归属；两者都归 2002，避免把他人的订单存在性暴露给枚举者
        if (order == null || !Objects.equals(order.getUserId(), uid)) {
            throw new BizException(ErrorCode.NO_DATA_PERMISSION, "订单不存在或无权查看");
        }
        return toDetailVo(order);
    }

    // ==================== 查询（管理侧，平台管理员） ====================

    /**
     * 全量订单分页查询（设计书 5.5：状态/站点/时间筛选）。
     *
     * @param query 筛选与分页条件
     * @return 订单分页结果
     */
    public PageResult<OrderVo> pageAll(OrderQuery query) {
        validateStatusFilter(query.getStatus());
        LambdaQueryWrapper<BagOrder> wrapper = new LambdaQueryWrapper<>();
        applyCommonFilters(wrapper, query);
        applyOrderBy(wrapper, query.getOrderBy());

        Page<BagOrder> page = new Page<>(query.pageNumOrDefault(), query.pageSizeOrDefault());
        return toPageResult(bagOrderMapper.selectPage(page, wrapper), query.pageSizeOrDefault());
    }

    /**
     * 超时未归还订单列表（设计书 5.5：{@code timeout_flag=1}；置位口径见 3.5——寄件后 7 天未归还）。
     *
     * @param query 筛选与分页条件
     * @return 超时订单分页结果
     */
    public PageResult<OrderVo> pageTimeout(OrderQuery query) {
        validateStatusFilter(query.getStatus());
        LambdaQueryWrapper<BagOrder> wrapper = new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getTimeoutFlag, 1);
        applyCommonFilters(wrapper, query);
        applyOrderBy(wrapper, query.getOrderBy());

        Page<BagOrder> page = new Page<>(query.pageNumOrDefault(), query.pageSizeOrDefault());
        return toPageResult(bagOrderMapper.selectPage(page, wrapper), query.pageSizeOrDefault());
    }

    // ==================== 标记归还（POST /orders/{orderNo}/return-mark，#6） ====================

    /**
     * 标记"已投放回收箱"（设计书 5.2、3.2 #6、3.4.2 辅助路径）。
     *
     * <p>写订单的 {@code return_station_id}（不改袋归属），触发 {@code RETURN_MARK}：
     * 订单→待回收确认、袋→回收在途；并向投放站点推送回收待办（7.3 {@code station/{id}/recycle}）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void returnMark(String orderNo, ReturnMarkDto dto, String clientRequestId) {
        String crid = requireClientRequestId(clientRequestId);
        String cached = idempotentService.findCompleted(API_RETURN_MARK, crid);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            return;
        }
        idempotentService.markProcessing(API_RETURN_MARK, crid);
        try {
            Long uid = AuthContext.userId();
            String role = AuthContext.role();

            BagOrder order = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                    .eq(BagOrder::getOrderNo, orderNo));
            if (order == null || !Objects.equals(order.getUserId(), uid)) {
                throw new BizException(ErrorCode.NO_DATA_PERMISSION, "订单不存在或无权操作");
            }
            if (!OrderStatus.IN_USE.code().equals(order.getStatus())) {
                throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "订单当前状态不允许标记归还");
            }

            // 投放站点必须存在且启用（停用站不接收投放，见设计书 5.3 站点停用后果）
            Station station = stationMapper.selectById(dto.getStationId());
            if (station == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "投放站点不存在");
            }
            if (!Constants.STATUS_ENABLED.equals(station.getStatus())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "投放站点已停用，请选择其他站点");
            }

            Map<String, Object> attributes = new HashMap<>();
            attributes.put("orderNo", orderNo);
            attributes.put("returnStationId", dto.getStationId());
            attributes.put("userId", uid);

            BagTransitionResult result = bagTransitionService.fire(TransitionCommand.builder()
                    .bagCode(order.getBagCode())
                    .event(BagEvent.RETURN_MARK)
                    .operatorId(uid)
                    .operatorRole(role)
                    .stationId(dto.getStationId())
                    .clientRequestId(crid)
                    .attributes(attributes)
                    .build());

            // 逐袋幂等命中：订单已离开使用中 = 标记此前已生效（回放）；仍是使用中 =
            // 同 ID 的历史失败尝试占用了幂等键，本次未执行，提示稍后重试（不把未执行报成成功）
            if (result.isReplayed()) {
                BagOrder current = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                        .eq(BagOrder::getOrderNo, orderNo));
                if (current == null || OrderStatus.IN_USE.code().equals(current.getStatus())) {
                    throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
                }
            }

            // 站点回收提醒：事务提交后推送，回滚则不发（7.3）
            Map<String, Object> notice = new LinkedHashMap<>();
            notice.put("orderNo", orderNo);
            notice.put("bagCode", order.getBagCode());
            notice.put("returnStationId", dto.getStationId());
            notice.put("returnStationName", station.getName());
            notice.put("status", OrderStatus.PENDING_CONFIRM.code());
            notice.put("message", "用户已标记归还并投放至本站，请及时回收确认");
            pushService.afterCommit(() -> pushService.pushStationRecycle(dto.getStationId(), notice));

            idempotentService.markCompleted(API_RETURN_MARK, crid,
                    JsonUtils.toJson(Map.of("orderNo", orderNo, "status", OrderStatus.PENDING_CONFIRM.code())));
        } catch (RuntimeException e) {
            idempotentService.clear(API_RETURN_MARK, crid);
            throw e;
        }
    }

    // ==================== 确认遗失（POST /orders/{orderNo}/lost-confirm，#18） ====================

    /**
     * 确认遗失（设计书 5.5、3.2 #18；平台管理员）。
     *
     * <p>触发 {@code LOST_CONFIRM}：袋→已遗失（前态须为已分配/使用中/回收在途，由状态机校验），
     * 订单→已遗失（含已取消订单的终态覆盖），写 {@code liability_record}（
     * {@code biz_type=ORDER}、{@code type=遗失}、责任用户可空、结论/裁决人/裁决时间），
     * 并在持袋数 −1（由伴随动作完成）。
     *
     * <p>时限前置校验（3.2 #18 / 3.5）：使用中 → 寄件后满 14 天；回收在途 → 标记归还后满 72h；
     * 已分配 → 订单已取消且满 7 天。三者按袋当前状态取其一，未届满一律拒绝——
     * 防止责任认定被提前触发（袋进终态后不可回退）；定时任务扫描（7.5）只生成告警候选，
     * 具体结论仍由平台管理员核验后填写并随责任记录留痕。
     */
    @Transactional(rollbackFor = Exception.class)
    public void lostConfirm(String orderNo, LostConfirmDto dto, String clientRequestId) {
        String crid = requireClientRequestId(clientRequestId);
        String cached = idempotentService.findCompleted(API_LOST_CONFIRM, crid);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            return;
        }
        idempotentService.markProcessing(API_LOST_CONFIRM, crid);
        try {
            Long adminId = AuthContext.userId();
            String role = AuthContext.role();

            BagOrder order = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                    .eq(BagOrder::getOrderNo, orderNo));
            if (order == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "订单不存在：" + orderNo);
            }
            if (OrderStatus.LOST.code().equals(order.getStatus())) {
                throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "订单已确认遗失，请勿重复操作");
            }
            if (OrderStatus.FINISHED.code().equals(order.getStatus())) {
                throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "订单已完成回收，无法确认遗失");
            }

            // 时限前置校验（3.2 #18 / 3.5）：按袋当前状态核对认定时限，未届满不进入状态机
            Bag bag = bagMapper.selectOne(new LambdaQueryWrapper<Bag>()
                    .eq(Bag::getBagCode, order.getBagCode()));
            if (bag != null) {
                assertLostConfirmWindow(order, BagStatus.of(bag.getStatus()));
            }

            Map<String, Object> attributes = new HashMap<>();
            attributes.put("orderNo", orderNo);
            attributes.put("userId", order.getUserId());
            attributes.put("responsibleUserId", dto.getResponsibleUserId());
            attributes.put("conclusion", dto.getConclusion());
            attributes.put("note", dto.getNote());

            BagTransitionResult result = bagTransitionService.fire(TransitionCommand.builder()
                    .bagCode(order.getBagCode())
                    .event(BagEvent.LOST_CONFIRM)
                    .operatorId(adminId)
                    .operatorRole(role)
                    .stationId(order.getStationId())
                    .clientRequestId(crid)
                    .attributes(attributes)
                    .build());

            // 逐袋幂等命中：订单已为已遗失 = 遗失确认此前已生效（回放）；否则说明
            // 同 ID 的历史失败尝试占用了幂等键，本次未执行，提示稍后重试（不把未执行报成成功）
            if (result.isReplayed()) {
                BagOrder current = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                        .eq(BagOrder::getOrderNo, orderNo));
                if (current == null || !OrderStatus.LOST.code().equals(current.getStatus())) {
                    throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
                }
            }

            idempotentService.markCompleted(API_LOST_CONFIRM, crid,
                    JsonUtils.toJson(Map.of("orderNo", orderNo, "status", OrderStatus.LOST.code())));
        } catch (RuntimeException e) {
            idempotentService.clear(API_LOST_CONFIRM, crid);
            throw e;
        }
    }

    // ==================== 私有工具 ====================

    /**
     * #18 确认遗失的时限前置校验（设计书 3.2 #18、3.5）：
     * 使用中 → 寄件后 ≥14 天；回收在途 → 标记归还后 ≥72h；已分配 → 订单已取消且 ≥7 天。
     *
     * <p>其余袋状态（含终态、非三态）不在 #18 的源态内，交由状态机按 4102 拒绝，
     * 此处不抢先改判错误码（设计书 3.2 表头「袋状态机拒绝一律 4102」）。
     */
    private static void assertLostConfirmWindow(BagOrder order, BagStatus bagStatus) {
        LocalDateTime now = LocalDateTime.now();
        switch (bagStatus) {
            case IN_USE -> assertWindowElapsed(order.getShippedAt(), now,
                    LOST_WINDOW_AFTER_SHIP, "寄件提交后满 14 天");
            case RETURNING -> assertWindowElapsed(order.getReturnMarkAt(), now,
                    LOST_WINDOW_IN_RETURN, "回收在途满 72 小时");
            case ALLOCATED -> {
                // 3.2 #18：已分配须「订单已取消 ≥7 天未回收」；R2 超时取消后订单最长停留 24h
                if (!OrderStatus.CANCELLED.code().equals(order.getStatus())) {
                    throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                            "订单尚未取消，未达到遗失认定的前置条件（已分配须订单取消满 7 天）");
                }
                assertWindowElapsed(order.getCancelledAt(), now,
                        LOST_WINDOW_AFTER_CANCEL, "订单取消后满 7 天");
            }
            default -> {
                // 其余状态不满足 #18 的源态，交由状态机按 4102 拒绝
            }
        }
    }

    /** 时间窗校验：起点缺失（数据异常）或未届满一律 4105，文案携带设计书口径便于排障。 */
    private static void assertWindowElapsed(LocalDateTime from, LocalDateTime now, Duration window, String label) {
        if (from == null || now.isBefore(from.plus(window))) {
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                    "未达到遗失认定的时限要求：" + label + "（设计书 3.2 #18）");
        }
    }

    /** 管理端公共筛选：状态/站点/袋码/建单时间区间（设计书 5.5）。 */
    private static void applyCommonFilters(LambdaQueryWrapper<BagOrder> wrapper, OrderQuery query) {
        if (hasText(query.getStatus())) {
            wrapper.eq(BagOrder::getStatus, query.getStatus());
        }
        if (query.getStationId() != null) {
            wrapper.eq(BagOrder::getStationId, query.getStationId());
        }
        if (hasText(query.getBagCode())) {
            wrapper.eq(BagOrder::getBagCode, query.getBagCode());
        }
        if (query.getBeginTime() != null) {
            wrapper.ge(BagOrder::getCreatedAt, query.getBeginTime());
        }
        if (query.getEndTime() != null) {
            wrapper.le(BagOrder::getCreatedAt, query.getEndTime());
        }
    }

    /**
     * 排序白名单（契约 0：排序字段必须走白名单，禁止把请求参数拼进 SQL）：
     * 只接受下列实体列名 + {@code ,asc|,desc}；白名单外的字段/方向一律 3001。
     */
    private static void applyOrderBy(LambdaQueryWrapper<BagOrder> wrapper, String orderBy) {
        String field = DEFAULT_SORT_FIELD;
        boolean asc = false;
        if (hasText(orderBy)) {
            String[] parts = orderBy.split(",");
            field = parts[0].trim();
            if (parts.length > 1) {
                String direction = parts[1].trim().toLowerCase(Locale.ROOT);
                if ("asc".equals(direction)) {
                    asc = true;
                } else if (!"desc".equals(direction)) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "不支持的排序方向：" + parts[1].trim());
                }
            }
        }
        switch (field) {
            case "orderNo" -> {
                if (asc) {
                    wrapper.orderByAsc(BagOrder::getOrderNo);
                } else {
                    wrapper.orderByDesc(BagOrder::getOrderNo);
                }
            }
            case "createdAt" -> {
                if (asc) {
                    wrapper.orderByAsc(BagOrder::getCreatedAt);
                } else {
                    wrapper.orderByDesc(BagOrder::getCreatedAt);
                }
            }
            case "shippedAt" -> {
                if (asc) {
                    wrapper.orderByAsc(BagOrder::getShippedAt);
                } else {
                    wrapper.orderByDesc(BagOrder::getShippedAt);
                }
            }
            case "returnMarkAt" -> {
                if (asc) {
                    wrapper.orderByAsc(BagOrder::getReturnMarkAt);
                } else {
                    wrapper.orderByDesc(BagOrder::getReturnMarkAt);
                }
            }
            case "confirmedAt" -> {
                if (asc) {
                    wrapper.orderByAsc(BagOrder::getConfirmedAt);
                } else {
                    wrapper.orderByDesc(BagOrder::getConfirmedAt);
                }
            }
            case "cancelledAt" -> {
                if (asc) {
                    wrapper.orderByAsc(BagOrder::getCancelledAt);
                } else {
                    wrapper.orderByDesc(BagOrder::getCancelledAt);
                }
            }
            case "lostAt" -> {
                if (asc) {
                    wrapper.orderByAsc(BagOrder::getLostAt);
                } else {
                    wrapper.orderByDesc(BagOrder::getLostAt);
                }
            }
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "不支持的排序字段：" + field);
        }
    }

    /** 状态筛选合法性：非法码值 3001 快速失败，避免把脏值带进查询。 */
    private static void validateStatusFilter(String status) {
        if (!hasText(status)) {
            return;
        }
        try {
            OrderStatus.of(status);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "订单状态不合法：" + status);
        }
    }

    /** 分页结果装配：补齐站点名称后包成统一 PageResult。 */
    private PageResult<OrderVo> toPageResult(Page<BagOrder> page, int size) {
        List<BagOrder> records = page.getRecords();
        Map<Long, String> stationNames = loadStationNames(records);
        List<OrderVo> vos = records.stream().map(order -> toVo(order, stationNames)).toList();
        return PageResult.of(vos, page.getTotal(), size);
    }

    /** 订单行 → 列表项（状态中文名与站点名称一并装配）。 */
    private static OrderVo toVo(BagOrder order, Map<Long, String> stationNames) {
        OrderVo vo = new OrderVo();
        vo.setOrderNo(order.getOrderNo());
        vo.setBagCode(order.getBagCode());
        vo.setStatus(order.getStatus());
        vo.setStatusName(safeOrderStatusName(order.getStatus()));
        vo.setStationId(order.getStationId());
        vo.setStationName(stationName(stationNames, order.getStationId()));
        vo.setReturnStationId(order.getReturnStationId());
        vo.setReturnStationName(stationName(stationNames, order.getReturnStationId()));
        vo.setExpressCompany(order.getExpressCompany());
        vo.setTrackingNo(order.getTrackingNo());
        vo.setRecipientPhoneMask(order.getRecipientPhoneMask());
        vo.setTimeoutFlag(order.getTimeoutFlag());
        vo.setShippedAt(order.getShippedAt());
        vo.setReturnMarkAt(order.getReturnMarkAt());
        vo.setConfirmedAt(order.getConfirmedAt());
        vo.setCancelledAt(order.getCancelledAt());
        vo.setLostAt(order.getLostAt());
        vo.setCreatedAt(order.getCreatedAt());
        return vo;
    }

    /** 订单行 → 详情（在列表字段之外补超时/提醒/更新时间与袋当前状态）。 */
    private OrderDetailVo toDetailVo(BagOrder order) {
        OrderDetailVo vo = new OrderDetailVo();
        vo.setOrderNo(order.getOrderNo());
        vo.setBagCode(order.getBagCode());
        vo.setStatus(order.getStatus());
        vo.setStatusName(safeOrderStatusName(order.getStatus()));
        vo.setStationId(order.getStationId());
        vo.setReturnStationId(order.getReturnStationId());
        vo.setExpressCompany(order.getExpressCompany());
        vo.setTrackingNo(order.getTrackingNo());
        vo.setRecipientPhoneMask(order.getRecipientPhoneMask());
        vo.setTimeoutFlag(order.getTimeoutFlag());
        vo.setTimeoutTime(order.getTimeoutTime());
        vo.setShippedAt(order.getShippedAt());
        vo.setReturnMarkAt(order.getReturnMarkAt());
        vo.setReturnRemindAt(order.getReturnRemindAt());
        vo.setConfirmedAt(order.getConfirmedAt());
        vo.setCancelledAt(order.getCancelledAt());
        vo.setLostAt(order.getLostAt());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setUpdatedAt(order.getUpdatedAt());

        Bag bag = bagMapper.selectOne(new LambdaQueryWrapper<Bag>().eq(Bag::getBagCode, order.getBagCode()));
        if (bag != null) {
            vo.setBagStatus(bag.getStatus());
            vo.setBagStatusName(safeBagStatusName(bag.getStatus()));
        }

        Map<Long, String> stationNames = loadStationNames(List.of(order));
        vo.setStationName(stationName(stationNames, order.getStationId()));
        vo.setReturnStationName(stationName(stationNames, order.getReturnStationId()));
        return vo;
    }

    /** 批量装载站点名称（一页内 stationId/returnStationId 去重后一次查回，避免 N+1）。 */
    private Map<Long, String> loadStationNames(List<BagOrder> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = new HashSet<>();
        for (BagOrder order : orders) {
            if (order.getStationId() != null) {
                ids.add(order.getStationId());
            }
            if (order.getReturnStationId() != null) {
                ids.add(order.getReturnStationId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Station station : stationMapper.selectByIds(ids)) {
            names.put(station.getId(), station.getName());
        }
        return names;
    }

    /** 站点名查表（缺省 null，不因站点被删而中断订单展示）。 */
    private static String stationName(Map<Long, String> stationNames, Long stationId) {
        return stationId == null ? null : stationNames.get(stationId);
    }

    /** 订单状态中文名；脏码值原样返回，不为展示文案把整页查询打断。 */
    private static String safeOrderStatusName(String code) {
        try {
            return OrderStatus.of(code).displayName();
        } catch (IllegalArgumentException e) {
            return code;
        }
    }

    /** 袋状态中文名；同上，脏码值原样返回。 */
    private static String safeBagStatusName(String code) {
        try {
            return BagStatus.of(code).displayName();
        } catch (IllegalArgumentException e) {
            return code;
        }
    }

    /** 取幂等请求头并校验 UUID 格式；缺失或格式错 → 3001（契约模块版第 4 节）。 */
    private static String requireClientRequestId(String clientRequestId) {
        if (clientRequestId == null || !clientRequestId.matches(Constants.UUID_REGEX)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少或非法的 clientRequestId 请求头");
        }
        return clientRequestId;
    }

    /** 轻量非空判断（不引第三方工具，语义同 StringUtils.hasText）。 */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
