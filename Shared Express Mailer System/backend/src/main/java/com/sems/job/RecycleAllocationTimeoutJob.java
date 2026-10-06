package com.sems.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.config.SemsProperties;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.station.entity.AllocationOrder;
import com.sems.module.station.mapper.AllocationOrderMapper;
import com.sems.module.statemachine.OrderStatus;
import com.sems.push.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 回收 / 调拨超时扫描任务（每 15 分钟；见《系统设计说明书》7.5「定时任务设计」回收/调拨超时扫描行、
 * 3.5「关键时限与计数口径表」与 3.6；契约模块版 8.8）。
 *
 * <p>两步：
 * <ol>
 *   <li><b>回收在途 24 小时提醒</b>：订单 PENDING_CONFIRM（袋=回收在途）且 {@code return_mark_at} 早于
 *       now-24h 且 {@code return_remind_at} 为空 → 写 {@code return_remind_at} 并向投放站点推送回收待办
 *       （7.3 的 {@code station/{id}/recycle} 主题）；</li>
 *   <li><b>回收在途 72 小时 / 调拨出库 48 小时</b>：只读扫描并打 WARN 日志——异常看板清单由 {@code /alerts}
 *       （AlertService）实时查询，本任务不落库（契约模块版 8.8）。</li>
 * </ol>
 *
 * <p><b>幂等依据</b>（7.5 表「幂等依据」列：状态机幂等）：第一步更新的 WHERE 含
 * 「前态 PENDING_CONFIRM + {@code return_remind_at} IS NULL」双重条件，影响行数为 0 即说明已被并发确认
 * （回收确认）或提醒已发过，跳过推送——防重推完全依靠 {@code return_remind_at} 非空，无需额外锁。
 * 单机部署不加分布式锁（7.5）。
 *
 * <p><b>时限口径</b>：24h / 72h 未登记进 11.2 配置键清单，以本类私有常量固化并注明 3.5 出处；
 * 调拨 48h 读取 {@code sems.alloc.timeout-hours}（11.2 已登记，禁止写死）。
 *
 * <p><b>扫描方式</b>（7.5：分批 LIMIT 500 + 命中索引）：按主键游标分批，每批 {@value #BATCH_SIZE} 条、
 * 单轮至多 {@value #MAX_BATCHES_PER_RUN} 批；每步整体 try/catch，异常只记 ERROR，不终止调度线程。
 */
@Component
public class RecycleAllocationTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(RecycleAllocationTimeoutJob.class);

    /** 单批扫描条数上限（7.5：扫描类任务分批，每批最多 500 条）。 */
    private static final int BATCH_SIZE = 500;

    /** 单轮最多扫描批数：把执行时长收敛在 7.5 的 5 分钟超时口径内，剩余留待下一周期。 */
    private static final int MAX_BATCHES_PER_RUN = 20;

    /** 任务周期：每 15 分钟（7.5 表；fixedDelay 为上一轮结束后再计时）。 */
    private static final long SCAN_INTERVAL_MS = 900_000L;

    /** 回收在途提醒时限：标记归还后 24 小时（设计书 3.5「回收在途超时」；11.2 键清单无对应配置）。 */
    private static final Duration RECYCLE_REMIND_AFTER = Duration.ofHours(24);

    /** 回收在途异常时限：标记归还后 72 小时未确认（设计书 3.5「回收在途超时」）。 */
    private static final Duration RECYCLE_ABNORMAL_AFTER = Duration.ofHours(72);

    /** 调拨单在途状态码（alloc 模块无状态枚举，码值与 allocation_order.status 列注释一致）。 */
    private static final String ALLOCATION_STATUS_IN_TRANSIT = "IN_TRANSIT";

    /** WARN 日志携带的样本条数上限（告警清单以 /alerts 为准，日志保持有界）。 */
    private static final int LOG_SAMPLE_SIZE = 5;

    private final BagOrderMapper bagOrderMapper;

    private final AllocationOrderMapper allocationOrderMapper;

    private final PushService pushService;

    private final SemsProperties properties;

    public RecycleAllocationTimeoutJob(BagOrderMapper bagOrderMapper,
                                       AllocationOrderMapper allocationOrderMapper,
                                       PushService pushService,
                                       SemsProperties properties) {
        this.bagOrderMapper = bagOrderMapper;
        this.allocationOrderMapper = allocationOrderMapper;
        this.pushService = pushService;
        this.properties = properties;
    }

    /**
     * 每 15 分钟：回收在途 24h 提醒 → 回收在途 72h 异常扫描 → 调拨出库 48h 异常扫描。
     *
     * <p>三步各自 try/catch：任一步骤异常只影响该步骤本轮，其余照常，下一周期自动重来；
     * 本方法不向外抛异常，保证调度线程不被一次故障终止。
     */
    @Scheduled(fixedDelay = SCAN_INTERVAL_MS)
    public void scan() {
        try {
            remindReturningOver24h();
        } catch (Exception e) {
            log.error("回收在途 24 小时提醒任务执行失败，本步骤跳过，下一周期重试：{}", e.getMessage(), e);
        }
        try {
            warnReturningOver72h();
        } catch (Exception e) {
            log.error("回收在途 72 小时异常扫描任务执行失败，本步骤跳过，下一周期重试：{}", e.getMessage(), e);
        }
        try {
            warnAllocationDispatchTimeout();
        } catch (Exception e) {
            log.error("调拨出库 48 小时未核收扫描任务执行失败，本步骤跳过，下一周期重试：{}", e.getMessage(), e);
        }
    }

    /**
     * 第一步：PENDING_CONFIRM 且 return_mark_at 早于 now-24h 且 return_remind_at 为空
     * → 写 return_remind_at 并推送提醒站点（防重推靠 return_remind_at 非空）。
     */
    private void remindReturningOver24h() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minus(RECYCLE_REMIND_AFTER);
        long lastId = 0L;
        int reminded = 0;

        for (int round = 0; round < MAX_BATCHES_PER_RUN; round++) {
            List<BagOrder> batch = bagOrderMapper.selectPage(new Page<>(1, BATCH_SIZE, false),
                    new LambdaQueryWrapper<BagOrder>()
                            .select(BagOrder::getId, BagOrder::getOrderNo, BagOrder::getBagCode,
                                    BagOrder::getUserId, BagOrder::getReturnStationId, BagOrder::getStationId,
                                    BagOrder::getReturnMarkAt)
                            .eq(BagOrder::getStatus, OrderStatus.PENDING_CONFIRM.code())
                            .isNull(BagOrder::getReturnRemindAt)
                            .lt(BagOrder::getReturnMarkAt, cutoff)
                            .gt(BagOrder::getId, lastId)
                            .orderByAsc(BagOrder::getId)).getRecords();
            if (batch.isEmpty()) {
                break;
            }
            for (BagOrder order : batch) {
                try {
                    if (remindOne(order, now)) {
                        reminded++;
                    }
                } catch (Exception e) {
                    log.error("回收在途提醒单条订单失败：orderNo={}, reason={}",
                            order.getOrderNo(), e.getMessage(), e);
                }
            }
            lastId = batch.get(batch.size() - 1).getId();
            if (batch.size() < BATCH_SIZE) {
                break;
            }
            if (round == MAX_BATCHES_PER_RUN - 1) {
                log.warn("回收在途提醒扫描达到单轮批次上限（{}×{}），剩余订单留待下一周期",
                        MAX_BATCHES_PER_RUN, BATCH_SIZE);
            }
        }
        if (reminded > 0) {
            log.info("回收在途 24 小时提醒完成：本轮提醒 {} 单", reminded);
        }
    }

    /**
     * 提醒单笔订单；返回是否本次首次提醒（影响行数为 0 = 已被并发回收确认或提醒已发过，直接跳过）。
     *
     * <p>条件 UPDATE 的 WHERE 含「前态 PENDING_CONFIRM + return_remind_at IS NULL」：
     * 与站点回收确认并发时仅一方成功；提醒标记先落库再推送，即使推送失败也不会重推。
     */
    private boolean remindOne(BagOrder order, LocalDateTime now) {
        int rows = bagOrderMapper.update(null, new LambdaUpdateWrapper<BagOrder>()
                .eq(BagOrder::getId, order.getId())
                .eq(BagOrder::getStatus, OrderStatus.PENDING_CONFIRM.code())
                .isNull(BagOrder::getReturnRemindAt)
                .set(BagOrder::getReturnRemindAt, now));
        if (rows == 0) {
            log.debug("订单 {} 已被并发确认或已提醒过，跳过", order.getOrderNo());
            return false;
        }

        // 确认站点 = 用户标记的投放站点（return_station_id）；数据异常缺省时退回取袋站点并记 WARN
        Long stationId = order.getReturnStationId() != null ? order.getReturnStationId() : order.getStationId();
        if (stationId == null) {
            log.warn("订单 {} 缺投放站点与取袋站点，提醒无法推送（标记已写入，防重推成立）", order.getOrderNo());
            return true;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("bagCode", order.getBagCode());
        data.put("returnStationId", order.getReturnStationId());
        data.put("returnMarkAt", order.getReturnMarkAt());
        data.put("message", "用户已标记归还超过 24 小时，请尽快扫码确认回收");
        pushService.pushStationRecycle(stationId, data);

        log.info("回收在途 24 小时提醒已推送：orderNo={}, bagCode={}, stationId={}",
                order.getOrderNo(), order.getBagCode(), stationId);
        return true;
    }

    /**
     * 第二步（其一）：PENDING_CONFIRM 且 return_mark_at 早于 now-72h → 只打 WARN（纳入 /alerts 异常清单）。
     */
    private void warnReturningOver72h() {
        LocalDateTime cutoff = LocalDateTime.now().minus(RECYCLE_ABNORMAL_AFTER);
        long lastId = 0L;
        int total = 0;

        for (int round = 0; round < MAX_BATCHES_PER_RUN; round++) {
            List<BagOrder> batch = bagOrderMapper.selectPage(new Page<>(1, BATCH_SIZE, false),
                    new LambdaQueryWrapper<BagOrder>()
                            .select(BagOrder::getId, BagOrder::getOrderNo, BagOrder::getBagCode,
                                    BagOrder::getReturnMarkAt, BagOrder::getReturnStationId)
                            .eq(BagOrder::getStatus, OrderStatus.PENDING_CONFIRM.code())
                            .lt(BagOrder::getReturnMarkAt, cutoff)
                            .gt(BagOrder::getId, lastId)
                            .orderByAsc(BagOrder::getId)).getRecords();
            if (batch.isEmpty()) {
                break;
            }
            total += batch.size();
            log.warn("回收在途已超 72 小时未确认（异常看板经 /alerts 实时展示）：本批 {} 单，样本：{}",
                    batch.size(), sampleOrders(batch));
            lastId = batch.get(batch.size() - 1).getId();
            if (batch.size() < BATCH_SIZE) {
                break;
            }
            if (round == MAX_BATCHES_PER_RUN - 1) {
                log.warn("回收在途 72 小时扫描达到单轮批次上限（{}×{}），剩余订单留待下一周期",
                        MAX_BATCHES_PER_RUN, BATCH_SIZE);
            }
        }
        if (total > 0) {
            log.warn("回收在途超 72 小时未确认订单本轮共 {} 单", total);
        }
    }

    /**
     * 第二步（其二）：调拨单 IN_TRANSIT 且首只出库早于 now-{sems.alloc.timeout-hours}
     * → 只打 WARN（纳入 /alerts 异常清单，平台管理员据此走 #19 在途遗失确认）。
     */
    private void warnAllocationDispatchTimeout() {
        // 时限读配置（11.2 已登记 sems.alloc.timeout-hours，默认 48h），禁止写死
        LocalDateTime cutoff = LocalDateTime.now().minusHours(properties.getAlloc().getTimeoutHours());
        long lastId = 0L;
        int total = 0;

        for (int round = 0; round < MAX_BATCHES_PER_RUN; round++) {
            List<AllocationOrder> batch = allocationOrderMapper.selectPage(new Page<>(1, BATCH_SIZE, false),
                    new LambdaQueryWrapper<AllocationOrder>()
                            .select(AllocationOrder::getId, AllocationOrder::getAllocationNo,
                                    AllocationOrder::getFromStationId, AllocationOrder::getToStationId,
                                    AllocationOrder::getDispatchAt)
                            .eq(AllocationOrder::getStatus, ALLOCATION_STATUS_IN_TRANSIT)
                            .lt(AllocationOrder::getDispatchAt, cutoff)
                            .gt(AllocationOrder::getId, lastId)
                            .orderByAsc(AllocationOrder::getId)).getRecords();
            if (batch.isEmpty()) {
                break;
            }
            total += batch.size();
            log.warn("调拨出库已超 {} 小时未核收（异常看板经 /alerts 实时展示）：本批 {} 单，样本：{}",
                    properties.getAlloc().getTimeoutHours(), batch.size(), sampleAllocations(batch));
            lastId = batch.get(batch.size() - 1).getId();
            if (batch.size() < BATCH_SIZE) {
                break;
            }
            if (round == MAX_BATCHES_PER_RUN - 1) {
                log.warn("调拨超时扫描达到单轮批次上限（{}×{}），剩余单据留待下一周期",
                        MAX_BATCHES_PER_RUN, BATCH_SIZE);
            }
        }
        if (total > 0) {
            log.warn("调拨出库超时未核收单据本轮共 {} 单", total);
        }
    }

    /** 拼接订单日志样本（orderNo/bagCode），最多 {@value #LOG_SAMPLE_SIZE} 条。 */
    private static String sampleOrders(List<BagOrder> orders) {
        return orders.stream().limit(LOG_SAMPLE_SIZE)
                .map(o -> o.getOrderNo() + "/" + o.getBagCode())
                .collect(Collectors.joining("、"));
    }

    /** 拼接调拨单日志样本（allocationNo/源站-目标站），最多 {@value #LOG_SAMPLE_SIZE} 条。 */
    private static String sampleAllocations(List<AllocationOrder> orders) {
        return orders.stream().limit(LOG_SAMPLE_SIZE)
                .map(o -> o.getAllocationNo() + "(" + o.getFromStationId() + "→" + o.getToStationId() + ")")
                .collect(Collectors.joining("、"));
    }
}
