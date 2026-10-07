package com.sems.module.stats.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.common.PageResult;
import com.sems.config.SemsProperties;
import com.sems.module.audit.entity.SysMetric;
import com.sems.module.audit.mapper.SysMetricMapper;
import com.sems.module.audit.service.AuditLogService;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.statemachine.OrderStatus;
import com.sems.module.stats.dto.AlertQuery;
import com.sems.module.stats.dto.AlertVo;
import com.sems.module.stats.mapper.StatsQueryMapper;
import com.sems.module.station.entity.AllocationOrder;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.AllocationOrderMapper;
import com.sems.module.station.mapper.StationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 异常与风控告警服务（M6，设计书 5.6「异常与风控告警清单」；时限口径唯一出处为 3.5）。
 *
 * <p>覆盖七类告警（5.6 与契约模块版 8.8）：
 * <ol>
 *   <li>{@code CANCELLED_NOT_RECOVERED} 订单取消后满 48 小时袋仍为已分配（3.5）；</li>
 *   <li>{@code RETURNING_TIMEOUT} 回收在途满 72 小时未确认（3.5；24h 为提醒、72h 纳入异常）；</li>
 *   <li>{@code ALLOCATION_TIMEOUT} 调拨出库满 48 小时未核收（3.5；阈值读 {@code sems.alloc.timeout-hours}）；</li>
 *   <li>{@code SHIP_TIMEOUT} 寄件后满 7 天超时未归还（3.5；订单 {@code timeout_flag=1}）；</li>
 *   <li>{@code AUDIT_DROP} 审计队列丢弃（7.6；读 {@code sys_metric.audit_drop_count} 当日累计）；</li>
 *   <li>{@code STOCK_RECONCILE_DIFF} 库存对账差异（7.2 R4；读 {@code sys_metric.stock_reconcile_diff_count} 当日累计）；</li>
 *   <li>{@code RISK_CONFIRM_FREQUENCY} 同站点同操作人 10 分钟内确认类操作 ≥20 次的风控线索（3.5）。</li>
 * </ol>
 *
 * <p><b>为什么全部实时查询、不新建表</b>：契约模块版 8.8 明确「不要新建表」——前四类异常由
 * 定时任务扫描产生（7.5），任务只负责推送提醒与打标记，不写告警表；⑤⑥读系统指标表、⑦读风控计数键，
 * 由本服务在查询时按「来源 → 告警项」实时组装。好处是告警口径永远与业务底账一致，不存在
 * 「告警表与事实不一致」的第二份真相。
 *
 * <p><b>分页实现</b>：多来源告警无法用单条 SQL 分页，故先按类型（未指定类型时逐类）组装、
 * 统一按 {@code occurredAt} 倒序，再在内存中切片——毕设规模（站点 ≤100、日活 ≤5000）下单次告警
 * 总量可控，且每类扫描均设上限（{@link #MAX_SCAN_PER_SOURCE}，对齐 7.5 分批口径）。
 *
 * <p><b>权限</b>：仅平台管理员可见（5.6），垂直越权由 {@code AlertController} 上的
 * {@code @RequireRole("PLATFORM_ADMIN")} 拦截；告警清单无「本站/本人」维度，不需要水平越权校验。
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    // ------------------------------ 七类告警类型 ------------------------------

    /** ① 取消后未回收：订单取消后满 48 小时袋仍为已分配（3.5）。 */
    public static final String TYPE_CANCELLED_NOT_RECOVERED = "CANCELLED_NOT_RECOVERED";
    /** ② 回收在途超时：标记归还后满 72 小时未确认（3.5）。 */
    public static final String TYPE_RETURNING_TIMEOUT = "RETURNING_TIMEOUT";
    /** ③ 调拨在途超时：出库后满 48 小时未核收（3.5）。 */
    public static final String TYPE_ALLOCATION_TIMEOUT = "ALLOCATION_TIMEOUT";
    /** ④ 寄件超时未归还：寄件后满 7 天（3.5，订单 timeout_flag）。 */
    public static final String TYPE_SHIP_TIMEOUT = "SHIP_TIMEOUT";
    /** ⑤ 审计队列丢弃（7.6）。 */
    public static final String TYPE_AUDIT_DROP = "AUDIT_DROP";
    /** ⑥ 库存对账差异（7.2 R4）。 */
    public static final String TYPE_STOCK_RECONCILE_DIFF = "STOCK_RECONCILE_DIFF";
    /** ⑦ 高频确认风控线索（3.5）。 */
    public static final String TYPE_RISK_CONFIRM_FREQUENCY = "RISK_CONFIRM_FREQUENCY";

    /** 类型码 → 中文标签（顺序即类型清单顺序；与前端 ALERT_TYPE_META 的键值口径一致）。 */
    private static final Map<String, String> TYPE_LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(TYPE_CANCELLED_NOT_RECOVERED, "取消后未回收");
        labels.put(TYPE_RETURNING_TIMEOUT, "回收在途超时");
        labels.put(TYPE_ALLOCATION_TIMEOUT, "调拨未核收");
        labels.put(TYPE_SHIP_TIMEOUT, "寄件超时未归还");
        labels.put(TYPE_AUDIT_DROP, "审计队列丢弃");
        labels.put(TYPE_STOCK_RECONCILE_DIFF, "库存对账差异");
        labels.put(TYPE_RISK_CONFIRM_FREQUENCY, "高频确认风控");
        TYPE_LABELS = Collections.unmodifiableMap(labels);
    }

    // ------------------------------ 时限与阈值（3.5） ------------------------------
    // 3.5 口径表中 48h/72h/7 天/阈值的数值未进 SemsProperties（11.2），
    // 按契约要求在模块内用具名常量登记并注明出处，禁止散落魔法数字。

    /** ① 取消订单未处理升级时限：订单取消后 48 小时（设计书 3.5）。 */
    private static final Duration CANCELLED_NOT_RECOVERED_AGE = Duration.ofHours(48);

    /** ② 回收在途超时：标记归还后 72 小时未确认（设计书 3.5）。 */
    private static final Duration RETURNING_TIMEOUT_AGE = Duration.ofHours(72);

    /** ④ 寄件超时提醒天数：寄件后 7 天未归还（设计书 3.5，展示用）。 */
    private static final int SHIP_TIMEOUT_DAYS = 7;

    /** ⑦ 风控告警阈值：同站点同操作人 10 分钟内确认类操作 ≥20 次（设计书 3.5；10 分钟窗口由写入方键 TTL 维护，见 8.3）。 */
    private static final long RISK_CONFIRM_THRESHOLD = 20L;

    // ------------------------------ 其它常量 ------------------------------

    /** 单类告警实时扫描上限：对齐 7.5「扫描类任务分批（LIMIT 500）+ 命中索引」的规模口径。 */
    private static final int MAX_SCAN_PER_SOURCE = 500;

    /** 调拨单「在途」状态码（allocation_order.status 取值，见 8.2 与 DDL 注释）。 */
    private static final String ALLOCATION_STATUS_IN_TRANSIT = "IN_TRANSIT";

    /**
     * 风控计数键前缀（8.3 的 {@code risk:confirm:{stationId}:{uid}}，由 {@code RedisKeys.auditRisk} 生成）。
     * 扫描需要模式而不是单键，故此处以常量登记前缀。
     */
    private static final String RISK_CONFIRM_KEY_PATTERN = "risk:confirm:*";

    /** 告警级别码（与前端 LEVEL_META 一致：HIGH→高、MEDIUM→中）。 */
    private static final String LEVEL_HIGH = "HIGH";
    private static final String LEVEL_MEDIUM = "MEDIUM";

    /** 前端处置跳转路径（hash 路由；与 Alerts.vue 各类型的处置入口一致）。 */
    private static final String ACTION_ORDER_CANCELLED = "/platform/orders?status=CANCELLED";
    private static final String ACTION_ORDER_PENDING_CONFIRM = "/platform/orders?status=PENDING_CONFIRM";
    private static final String ACTION_ORDER_TIMEOUT = "/platform/orders?tab=timeout";
    private static final String ACTION_ALLOCATIONS = "/platform/allocations";
    private static final String ACTION_DASHBOARD = "/platform/dashboard";

    /** 分页默认值与上限（6.1：默认 10、最大 100）。 */
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final StatsQueryMapper statsQueryMapper;
    private final BagMapper bagMapper;
    private final BagOrderMapper bagOrderMapper;
    private final AllocationOrderMapper allocationOrderMapper;
    private final StationMapper stationMapper;
    private final SysMetricMapper sysMetricMapper;
    private final AuditLogService auditLogService;
    private final StringRedisTemplate redisTemplate;
    private final SemsProperties properties;

    public AlertService(StatsQueryMapper statsQueryMapper,
                        BagMapper bagMapper,
                        BagOrderMapper bagOrderMapper,
                        AllocationOrderMapper allocationOrderMapper,
                        StationMapper stationMapper,
                        SysMetricMapper sysMetricMapper,
                        AuditLogService auditLogService,
                        StringRedisTemplate redisTemplate,
                        SemsProperties properties) {
        this.statsQueryMapper = statsQueryMapper;
        this.bagMapper = bagMapper;
        this.bagOrderMapper = bagOrderMapper;
        this.allocationOrderMapper = allocationOrderMapper;
        this.stationMapper = stationMapper;
        this.sysMetricMapper = sysMetricMapper;
        this.auditLogService = auditLogService;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 告警清单分页查询（5.6：按类型/时间筛选）。
     *
     * @param query 类型（可选）、时间区间（可选，闭区间，作用于 occurredAt）、分页参数
     * @return 按发生时间倒序的告警分页
     */
    @Transactional(readOnly = true)
    public PageResult<AlertVo> pageAlerts(AlertQuery query) {
        String type = normalizeType(query.getType());
        int pageNum = normalizePageNum(query.getPageNum());
        int pageSize = normalizePageSize(query.getPageSize());
        LocalDateTime begin = query.getBeginTime();
        LocalDateTime end = query.getEndTime();

        // 站点名映射一次加载（校园规模站点 ≤100，逐条回表取名字不划算）
        Map<Long, String> stationNames = stationNames();

        List<AlertVo> alerts = new ArrayList<>();
        // 指定类型时只扫描对应来源，避免为展示一类而做七类全表扫描
        if (type == null || TYPE_CANCELLED_NOT_RECOVERED.equals(type)) {
            alerts.addAll(cancelledNotRecovered(stationNames));
        }
        if (type == null || TYPE_RETURNING_TIMEOUT.equals(type)) {
            alerts.addAll(returningTimeout(stationNames));
        }
        if (type == null || TYPE_ALLOCATION_TIMEOUT.equals(type)) {
            alerts.addAll(allocationTimeout(stationNames));
        }
        if (type == null || TYPE_SHIP_TIMEOUT.equals(type)) {
            alerts.addAll(shipTimeout(stationNames));
        }
        if (type == null || TYPE_AUDIT_DROP.equals(type)) {
            alerts.addAll(auditDrop());
        }
        if (type == null || TYPE_STOCK_RECONCILE_DIFF.equals(type)) {
            alerts.addAll(stockReconcileDiff());
        }
        if (type == null || TYPE_RISK_CONFIRM_FREQUENCY.equals(type)) {
            alerts.addAll(riskConfirmFrequency(stationNames));
        }

        // 时间筛选：区间为闭区间（含边界），作用于各告警的 occurredAt（口径见 AlertVo 注释）
        if (begin != null || end != null) {
            alerts.removeIf(alert -> {
                LocalDateTime occurredAt = alert.getOccurredAt();
                if (occurredAt == null) {
                    // 无时间字段的告警在时间筛选下无法定位区间，宁可不显示也不误显示
                    return true;
                }
                return (begin != null && occurredAt.isBefore(begin))
                        || (end != null && occurredAt.isAfter(end));
            });
        }

        // 最近发生的排前；occurredAt 理论上不会为空，nullsLast 仅作防御（排序稳定性由 List.sort 保证）
        alerts.sort(Comparator.comparing(AlertVo::getOccurredAt,
                Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())));

        long total = alerts.size();
        // 页码可能被恶意放大，乘法用 long 防 int 溢出
        long fromIndex = (long) (pageNum - 1) * pageSize;
        List<AlertVo> records = List.of();
        if (fromIndex < total) {
            int from = (int) fromIndex;
            int to = (int) Math.min(fromIndex + pageSize, total);
            records = new ArrayList<>(alerts.subList(from, to));
        }
        return PageResult.of(records, total, pageSize);
    }

    // ------------------------------------------------------------------
    // 各类型告警来源
    // ------------------------------------------------------------------

    /**
     * ① 取消后未回收（3.5：订单取消后 48 小时袋仍为已分配）。
     *
     * <p>扫描条件与「最新订单」防误报逻辑见 {@link StatsQueryMapper#selectCancelledNotRecovered}；
     * 完成时点取 {@code cancelled_at}，处置入口为订单管理的「已取消」列表。
     */
    private List<AlertVo> cancelledNotRecovered(Map<Long, String> stationNames) {
        LocalDateTime deadline = LocalDateTime.now().minus(CANCELLED_NOT_RECOVERED_AGE);
        List<BagOrder> orders = statsQueryMapper.selectCancelledNotRecovered(
                new Page<BagOrder>(1, MAX_SCAN_PER_SOURCE, false), deadline);
        List<AlertVo> alerts = new ArrayList<>(orders.size());
        for (BagOrder order : orders) {
            AlertVo alert = new AlertVo();
            alert.setType(TYPE_CANCELLED_NOT_RECOVERED);
            alert.setTypeLabel(TYPE_LABELS.get(TYPE_CANCELLED_NOT_RECOVERED));
            alert.setTitle("订单 " + order.getOrderNo() + " 取消已超 " + CANCELLED_NOT_RECOVERED_AGE.toHours()
                    + " 小时，袋 " + order.getBagCode() + " 仍为已分配未回收");
            alert.setTargetNo(order.getOrderNo());
            alert.setStationId(order.getStationId());
            alert.setStationName(stationNames.get(order.getStationId()));
            alert.setOccurredAt(order.getCancelledAt());
            alert.setLevel(LEVEL_MEDIUM);
            alert.setActionUrl(ACTION_ORDER_CANCELLED);
            alerts.add(alert);
        }
        return alerts;
    }

    /**
     * ② 回收在途超时（3.5：标记归还后 72 小时未确认）。
     *
     * <p>袋保持 IN_USE 且对应订单为待回收确认时，其
     * {@code return_mark_at} 早于 now − 72h；每个袋码只取最新一单，避免历史单重复计入。
     * 关联站点取订单的 {@code return_station_id}（投放站点，更贴近处置现场），缺失时回落取袋站点。
     */
    private List<AlertVo> returningTimeout(Map<Long, String> stationNames) {
        LocalDateTime deadline = LocalDateTime.now().minus(RETURNING_TIMEOUT_AGE);
        List<Bag> returningBags = bagMapper.selectPage(new Page<Bag>(1, MAX_SCAN_PER_SOURCE, false),
                Wrappers.lambdaQuery(Bag.class)
                        .eq(Bag::getStatus, BagStatus.IN_USE.code())).getRecords();
        if (returningBags.isEmpty()) {
            return List.of();
        }
        Set<String> bagCodes = new HashSet<>();
        for (Bag bag : returningBags) {
            bagCodes.add(bag.getBagCode());
        }
        List<BagOrder> orders = bagOrderMapper.selectList(Wrappers.lambdaQuery(BagOrder.class)
                .in(BagOrder::getBagCode, bagCodes)
                .eq(BagOrder::getStatus, OrderStatus.PENDING_CONFIRM.code())
                .isNotNull(BagOrder::getReturnMarkAt)
                .le(BagOrder::getReturnMarkAt, deadline)
                .orderByDesc(BagOrder::getId));
        Map<String, BagOrder> latestOrderByBag = new HashMap<>();
        for (BagOrder order : orders) {
            latestOrderByBag.putIfAbsent(order.getBagCode(), order);
        }
        List<AlertVo> alerts = new ArrayList<>(latestOrderByBag.size());
        for (BagOrder order : latestOrderByBag.values()) {
            Long stationId = order.getReturnStationId() != null
                    ? order.getReturnStationId()
                    : order.getStationId();
            AlertVo alert = new AlertVo();
            alert.setType(TYPE_RETURNING_TIMEOUT);
            alert.setTypeLabel(TYPE_LABELS.get(TYPE_RETURNING_TIMEOUT));
            alert.setTitle("袋 " + order.getBagCode() + " 标记归还已超 " + RETURNING_TIMEOUT_AGE.toHours()
                    + " 小时未确认（订单 " + order.getOrderNo() + "）");
            alert.setTargetNo(order.getOrderNo());
            alert.setStationId(stationId);
            alert.setStationName(stationNames.get(stationId));
            alert.setOccurredAt(order.getReturnMarkAt());
            alert.setLevel(LEVEL_HIGH);
            alert.setActionUrl(ACTION_ORDER_PENDING_CONFIRM);
            alerts.add(alert);
        }
        return alerts;
    }

    /**
     * ③ 调拨在途超时（3.5：出库后 48 小时未核收）。
     *
     * <p>时限读 {@code sems.alloc.timeout-hours}（默认 48h，11.2 的可配置项），不写死数值；
     * 关联站点取目标站（出库单的目的地尚未核收）；完成时点取 {@code dispatch_at}。
     */
    private List<AlertVo> allocationTimeout(Map<Long, String> stationNames) {
        int timeoutHours = properties.getAlloc().getTimeoutHours();
        LocalDateTime deadline = LocalDateTime.now().minusHours(timeoutHours);
        List<AllocationOrder> inTransit = allocationOrderMapper.selectPage(
                        new Page<AllocationOrder>(1, MAX_SCAN_PER_SOURCE, false),
                        Wrappers.lambdaQuery(AllocationOrder.class)
                                .eq(AllocationOrder::getStatus, ALLOCATION_STATUS_IN_TRANSIT)
                                .isNotNull(AllocationOrder::getDispatchAt)
                                .le(AllocationOrder::getDispatchAt, deadline)
                                .orderByAsc(AllocationOrder::getDispatchAt))
                .getRecords();
        List<AlertVo> alerts = new ArrayList<>(inTransit.size());
        for (AllocationOrder allocation : inTransit) {
            AlertVo alert = new AlertVo();
            alert.setType(TYPE_ALLOCATION_TIMEOUT);
            alert.setTypeLabel(TYPE_LABELS.get(TYPE_ALLOCATION_TIMEOUT));
            alert.setTitle("调拨单 " + allocation.getAllocationNo() + " 出库已超 " + timeoutHours
                    + " 小时未核收（源站 → 目标站）");
            alert.setTargetNo(allocation.getAllocationNo());
            alert.setStationId(allocation.getToStationId());
            alert.setStationName(stationNames.get(allocation.getToStationId()));
            alert.setOccurredAt(allocation.getDispatchAt());
            alert.setLevel(LEVEL_HIGH);
            alert.setActionUrl(ACTION_ALLOCATIONS);
            alerts.add(alert);
        }
        return alerts;
    }

    /**
     * ④ 寄件超时未归还（3.5：寄件后 7 天置 {@code timeout_flag} 并推送，5.6 第四类）。
     *
     * <p>{@code timeout_flag=1} 是定时任务置的标记（7.5）；仅在订单仍为「使用中」时告警——
     * 已归还（FINISHED）/已取消/已遗失的订单即使保留历史标记也不再属于「超时未归还」，
     * 不在扫描范围可避免已闭环订单长期占据告警清单。
     * 发生时间优先取 {@code timeout_time}，缺失时回落寄件时间、建单时间。
     */
    private List<AlertVo> shipTimeout(Map<Long, String> stationNames) {
        List<BagOrder> timedOut = bagOrderMapper.selectPage(new Page<BagOrder>(1, MAX_SCAN_PER_SOURCE, false),
                Wrappers.lambdaQuery(BagOrder.class)
                        .eq(BagOrder::getTimeoutFlag, 1)
                        .eq(BagOrder::getStatus, OrderStatus.IN_USE.code())
                        .orderByAsc(BagOrder::getTimeoutTime)).getRecords();
        List<AlertVo> alerts = new ArrayList<>(timedOut.size());
        for (BagOrder order : timedOut) {
            LocalDateTime occurredAt = order.getTimeoutTime() != null
                    ? order.getTimeoutTime()
                    : (order.getShippedAt() != null ? order.getShippedAt() : order.getCreatedAt());
            AlertVo alert = new AlertVo();
            alert.setType(TYPE_SHIP_TIMEOUT);
            alert.setTypeLabel(TYPE_LABELS.get(TYPE_SHIP_TIMEOUT));
            alert.setTitle("订单 " + order.getOrderNo() + " 寄件后已超 " + SHIP_TIMEOUT_DAYS
                    + " 天未归还（系统已置超时标记）");
            alert.setTargetNo(order.getOrderNo());
            alert.setStationId(order.getStationId());
            alert.setStationName(stationNames.get(order.getStationId()));
            alert.setOccurredAt(occurredAt);
            alert.setLevel(LEVEL_MEDIUM);
            alert.setActionUrl(ACTION_ORDER_TIMEOUT);
            alerts.add(alert);
        }
        return alerts;
    }

    /**
     * ⑤ 审计队列丢弃（7.6：队列饱和时事件降级写入应急文件并累加丢弃计数，需人工核查）。
     *
     * <p>读 {@link AuditLogService#getAuditDropCount()}（当日累计）；数值为「当日累计」，
     * 无精确发生时刻，{@code occurredAt} 取检测时刻。
     */
    private List<AlertVo> auditDrop() {
        long dropped = auditLogService.getAuditDropCount();
        if (dropped <= 0L) {
            return List.of();
        }
        AlertVo alert = new AlertVo();
        alert.setType(TYPE_AUDIT_DROP);
        alert.setTypeLabel(TYPE_LABELS.get(TYPE_AUDIT_DROP));
        alert.setTitle("审计队列当日已丢弃 " + dropped
                + " 条事件（队列饱和，事件已降级写入应急文件，需人工核查）");
        alert.setOccurredAt(LocalDateTime.now());
        alert.setLevel(LEVEL_HIGH);
        alert.setActionUrl(ACTION_DASHBOARD);
        return List.of(alert);
    }

    /**
     * ⑥ 库存对账差异（7.2 R4：逐站点对账发现 Redis 计数与 DB 口径不一致时累加差异计数）。
     *
     * <p>读 {@code sys_metric.stock_reconcile_diff_count} 的当日累计（键名与看板共用
     * {@link StatsService#METRIC_STOCK_RECONCILE_DIFF_COUNT}）；指标行存在时发生时间取其最后更新时刻。
     */
    private List<AlertVo> stockReconcileDiff() {
        SysMetric metric = sysMetricMapper.selectOne(Wrappers.lambdaQuery(SysMetric.class)
                .eq(SysMetric::getMetricKey, StatsService.METRIC_STOCK_RECONCILE_DIFF_COUNT)
                .eq(SysMetric::getStatDate, LocalDate.now()));
        if (metric == null || metric.getMetricValue() == null || metric.getMetricValue() <= 0L) {
            return List.of();
        }
        AlertVo alert = new AlertVo();
        alert.setType(TYPE_STOCK_RECONCILE_DIFF);
        alert.setTypeLabel(TYPE_LABELS.get(TYPE_STOCK_RECONCILE_DIFF));
        alert.setTitle("当日检测到库存对账差异 " + metric.getMetricValue()
                + " 项（Redis 计数与数据库口径不一致，R4 对账告警）");
        alert.setOccurredAt(metric.getUpdatedAt() != null
                ? metric.getUpdatedAt()
                : LocalDate.now().atStartOfDay());
        alert.setLevel(LEVEL_HIGH);
        alert.setActionUrl(ACTION_DASHBOARD);
        return List.of(alert);
    }

    /**
     * ⑦ 高频确认风控线索（3.5：同站点同操作人 10 分钟内确认类操作 ≥20 次，触发 5101 并列入 /alerts）。
     *
     * <p>扫描 {@code risk:confirm:*} 键空间（由确认类操作写入，键 TTL 10 分钟即窗口）。
     * 为什么用 {@code StringRedisTemplate.keys}：生产环境大键空间下 KEYS 可能阻塞 Redis，
     * 但毕设规模（站点 ≤100、日活 ≤5000、单键 ≤1KB、键空间 &lt;50MB，见 8.3 内存估算）键量很小，
     * 可接受；后续若扩容可改为 SCAN 游标渐进扫描。
     * 风控键是 10 分钟滚动窗口计数、无绝对时点，{@code occurredAt} 取检测时刻。
     */
    private List<AlertVo> riskConfirmFrequency(Map<Long, String> stationNames) {
        Set<String> keys = redisTemplate.keys(RISK_CONFIRM_KEY_PATTERN);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<AlertVo> alerts = new ArrayList<>();
        for (String key : keys) {
            Long count = toLong(redisTemplate.opsForValue().get(key));
            if (count == null || count < RISK_CONFIRM_THRESHOLD) {
                continue;
            }
            // 键格式：risk:confirm:{stationId}:{uid}（RedisKeys.auditRisk，8.3）
            String[] parts = key.split(":");
            if (parts.length < 4) {
                // 键空间被外部写入脏键时跳过并留痕，不让单条脏数据打断整个清单
                log.warn("风控计数键格式非法，已跳过：key={}", key);
                continue;
            }
            Long stationId = toLong(parts[2]);
            Long operatorId = toLong(parts[3]);
            if (stationId == null || operatorId == null) {
                log.warn("风控计数键站点/操作人段非数字，已跳过：key={}", key);
                continue;
            }
            String stationName = stationNames.get(stationId);
            AlertVo alert = new AlertVo();
            alert.setType(TYPE_RISK_CONFIRM_FREQUENCY);
            alert.setTypeLabel(TYPE_LABELS.get(TYPE_RISK_CONFIRM_FREQUENCY));
            alert.setTitle("站点「" + (stationName != null ? stationName : stationId)
                    + "」操作人 ID " + operatorId + " 在 10 分钟内确认类操作 " + count
                    + " 次（达风控阈值 " + RISK_CONFIRM_THRESHOLD + " 次）");
            alert.setTargetNo(String.valueOf(operatorId));
            alert.setStationId(stationId);
            alert.setStationName(stationName);
            alert.setOccurredAt(LocalDateTime.now());
            alert.setLevel(LEVEL_MEDIUM);
            alert.setActionUrl("/platform/audit-logs?operatorId=" + operatorId);
            alerts.add(alert);
        }
        return alerts;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 站点 ID → 名称（一次查询）；站点名缺失时相关字段返回 null（non_null 序列化下字段省略）。 */
    private Map<Long, String> stationNames() {
        List<Station> stations = stationMapper.selectList(Wrappers.lambdaQuery(Station.class));
        Map<Long, String> names = new HashMap<>();
        for (Station station : stations) {
            names.put(station.getId(), station.getName());
        }
        return names;
    }

    /** 类型归一：空白视为「全部」；非七类白名单值抛 3001（fail-closed，防止前端传错时静默返回空表）。 */
    private static String normalizeType(String type) {
        if (!StringUtils.hasText(type)) {
            return null;
        }
        String value = type.trim();
        if (!TYPE_LABELS.containsKey(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "type 取值非法，允许值：" + String.join("、", TYPE_LABELS.keySet()));
        }
        return value;
    }

    /** 页码归一：小于 1 归 1（与基础层查询接口口径一致）。 */
    private static int normalizePageNum(int pageNum) {
        return pageNum < 1 ? 1 : pageNum;
    }

    /** 每页条数归一：小于 1 取默认值，超过 100 截为 100（6.1）。 */
    private static int normalizePageSize(int pageSize) {
        if (pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /** 计数串转 Long：值缺失或非数字（脏键）返回 null，由调用方跳过。 */
    private static Long toLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
