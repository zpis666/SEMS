package com.sems.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.infra.StockService;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.statemachine.BagStatus;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 超时订单扫描任务（见《系统设计说明书》7.5「定时任务设计」超时订单扫描行、7.2 的 R2 回补路径与
 * 3.5「关键时限与计数口径表」；契约模块版 8.8）。
 *
 * <p>每 5 分钟执行三步（时限口径唯一出处为设计书 3.5；24h / 7 天 / 48h 未登记进 11.2 配置键清单，
 * 故以本类私有常量固化并逐条注明出处，杜绝散落的魔法数字）：
 * <ol>
 *   <li><b>R2：申领后 24 小时未寄件自动取消</b>——订单 PENDING_SEND 且建单超 24h → 置已取消 +
 *       {@code cancelled_at}；<b>不释放在持袋数额度</b>（袋仍在用户手上，额度由站点 #4/#5 闭环时释放），
 *       仅调 {@code stockService.deleteHold} 删除预扣凭据（不回补库存）并推送用户；</li>
 *   <li><b>寄件后 7 天超时提醒</b>——订单 IN_USE 且 {@code shipped_at} 超 7 天且 {@code timeout_flag=0}
 *       → 置 {@code timeout_flag=1} + {@code timeout_time} 并推送提醒；<b>不改订单状态</b>
 *       （超时是标记字段而非订单状态，见 3.5/3.6，直至归还或遗失确认）；</li>
 *   <li><b>取消后 48 小时袋仍为已分配</b>——只读扫描并打 WARN 日志；异常看板清单由
 *       {@code /alerts}（AlertService）实时查询，本任务不落库（契约模块版 8.8；7.5 表的该行）。</li>
 * </ol>
 *
 * <p><b>幂等依据</b>（7.5 表「幂等依据」列）：订单条件 UPDATE（取消幂等 / 标记幂等）——
 * 每步更新都以「当前状态 + 标记列」为 WHERE 条件，影响行数为 0 即说明已被并发流转（撤回/寄件/
 * 回收确认）或本任务已处理过，一律跳过，不删凭据、不重复推送。单机部署无需分布式锁（7.5）。
 *
 * <p><b>扫描方式</b>（7.5 统一约定：分批 LIMIT 500 + 命中索引，禁止全表扫描）：按主键游标分批，
 * 每批 {@value #BATCH_SIZE} 条、单轮至多 {@value #MAX_BATCHES_PER_RUN} 批；WHERE 命中
 * {@code idx_order_status_created_at} 等已有索引。每条记录独立 try/catch，单条异常不拖垮同批其余记录；
 * 每个步骤整体 try/catch，异常只记 ERROR 并转入下一步骤 / 下一周期，绝不终止调度线程。
 */
@Component
public class TimeoutOrderJob {

    private static final Logger log = LoggerFactory.getLogger(TimeoutOrderJob.class);

    /** 单批扫描条数上限（7.5：扫描类任务分批，每批最多 500 条）。 */
    private static final int BATCH_SIZE = 500;

    /** 单轮最多扫描批数：500×20 = 1 万条/轮，把执行时长收敛在 7.5 的 5 分钟超时口径内，剩余留待下一周期。 */
    private static final int MAX_BATCHES_PER_RUN = 20;

    /** 任务周期：每 5 分钟（7.5 表；fixedDelay 为上一轮结束后再计时，避免慢轮次堆叠）。 */
    private static final long SCAN_INTERVAL_MS = 300_000L;

    /** R2 自动取消时限：申领（建单）后 24 小时（设计书 3.5「申领后未寄件自动取消」；11.2 键清单无对应配置）。 */
    private static final Duration AUTO_CANCEL_AFTER = Duration.ofHours(24);

    /** 寄件超时提醒时限：寄件后 7 天（设计书 3.5「寄件超时提醒」）。 */
    private static final Duration TIMEOUT_REMIND_AFTER = Duration.ofDays(7);

    /** 取消订单未处理升级时限：订单取消后 48 小时袋仍为已分配（设计书 3.5「取消订单未处理升级」）。 */
    private static final Duration CANCEL_UPGRADE_AFTER = Duration.ofHours(48);

    /** WARN 日志携带的样本条数上限：批次内异常逐条打日志会放大日志量，告警清单以 /alerts 为准。 */
    private static final int LOG_SAMPLE_SIZE = 5;

    private final BagOrderMapper bagOrderMapper;

    private final BagMapper bagMapper;

    private final StockService stockService;

    private final PushService pushService;

    public TimeoutOrderJob(BagOrderMapper bagOrderMapper, BagMapper bagMapper,
                           StockService stockService, PushService pushService) {
        this.bagOrderMapper = bagOrderMapper;
        this.bagMapper = bagMapper;
        this.stockService = stockService;
        this.pushService = pushService;
    }

    /**
     * 每 5 分钟：R2 自动取消 → 7 天超时标记 → 取消后 48h 异常扫描。
     *
     * <p>三步各自 try/catch：任一步骤异常只影响该步骤本轮执行，其余步骤照常，下一周期自动重来。
     * 本方法自身不向外抛异常，保证调度线程不被一次故障终止（7.5 统一约定）。
     */
    @Scheduled(fixedDelay = SCAN_INTERVAL_MS)
    public void scan() {
        try {
            cancelUnshippedOrders();
        } catch (Exception e) {
            log.error("R2 24 小时未寄件自动取消任务执行失败，本步骤跳过，下一周期重试：{}", e.getMessage(), e);
        }
        try {
            markTimeoutAfterSevenDays();
        } catch (Exception e) {
            log.error("寄件后 7 天超时标记任务执行失败，本步骤跳过，下一周期重试：{}", e.getMessage(), e);
        }
        try {
            warnCancelledButBagStillAllocated();
        } catch (Exception e) {
            log.error("取消后 48 小时袋仍为已分配扫描任务执行失败，本步骤跳过，下一周期重试：{}", e.getMessage(), e);
        }
    }

    /**
     * 第一步（R2）：PENDING_SEND 且建单早于 now-24h → 置 CANCELLED 并写 cancelled_at。
     *
     * <p>与设计书 3.2 表后「订单级操作说明」一致：只改订单并删预扣凭据，不改变袋状态、
     * 不释放在持袋数额度（袋保持已分配，等待站点 #4/#5 闭环）。
     */
    private void cancelUnshippedOrders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minus(AUTO_CANCEL_AFTER);
        long lastId = 0L;
        int cancelled = 0;

        for (int round = 0; round < MAX_BATCHES_PER_RUN; round++) {
            // 主键游标分批 + 命中 idx_order_status_created_at（status 前缀）：已处理的订单状态改变后
            // 自然离开结果集，游标只保证扫描位置单调前移，不会重复处理
            List<BagOrder> batch = bagOrderMapper.selectPage(new Page<>(1, BATCH_SIZE, false),
                    new LambdaQueryWrapper<BagOrder>()
                            .select(BagOrder::getId, BagOrder::getOrderNo, BagOrder::getBagCode, BagOrder::getUserId)
                            .eq(BagOrder::getStatus, OrderStatus.PENDING_SEND.code())
                            .lt(BagOrder::getCreatedAt, cutoff)
                            .gt(BagOrder::getId, lastId)
                            .orderByAsc(BagOrder::getId)).getRecords();
            if (batch.isEmpty()) {
                break;
            }
            for (BagOrder order : batch) {
                try {
                    if (cancelOne(order, now)) {
                        cancelled++;
                    }
                } catch (Exception e) {
                    // 单条失败不影响其余：订单仍为 PENDING_SEND，下一周期会被重新扫到（幂等）
                    log.error("24 小时超时取消单条订单失败：orderNo={}, reason={}",
                            order.getOrderNo(), e.getMessage(), e);
                }
            }
            lastId = batch.get(batch.size() - 1).getId();
            if (batch.size() < BATCH_SIZE) {
                break;
            }
            if (round == MAX_BATCHES_PER_RUN - 1) {
                log.warn("24 小时未寄件扫描达到单轮批次上限（{}×{}），剩余订单留待下一周期",
                        MAX_BATCHES_PER_RUN, BATCH_SIZE);
            }
        }
        if (cancelled > 0) {
            log.info("R2 超时取消完成：本轮自动取消 {} 单（不释放在持额度，袋待站点回收闭环）", cancelled);
        }
    }

    /**
     * 取消单笔订单；返回是否真的取消（影响行数为 0 = 已被并发流转或本任务先前已处理，直接跳过）。
     *
     * <p>条件 UPDATE 的 WHERE 含前态 PENDING_SEND，是并发安全与幂等的唯一依据：
     * 与用户撤回（R1）/提交寄件/站点回收确认并发时，仅一方能把 PENDING_SEND 改走。
     */
    private boolean cancelOne(BagOrder order, LocalDateTime now) {
        int rows = bagOrderMapper.update(null, new LambdaUpdateWrapper<BagOrder>()
                .eq(BagOrder::getId, order.getId())
                .eq(BagOrder::getStatus, OrderStatus.PENDING_SEND.code())
                .set(BagOrder::getStatus, OrderStatus.CANCELLED.code())
                .set(BagOrder::getCancelledAt, now));
        if (rows == 0) {
            log.debug("订单 {} 已被并发流转，跳过 R2 超时取消", order.getOrderNo());
            return false;
        }

        // R2：只删预扣凭据（不存在则忽略），不回补库存、不释放在持额度（7.2 R2 与 3.5）
        stockService.deleteHold(order.getOrderNo());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("bagCode", order.getBagCode());
        data.put("status", OrderStatus.CANCELLED.code());
        data.put("reason", "AUTO_CANCEL_24H");
        data.put("message", "申领后 24 小时未提交寄件，订单已自动取消；快递袋仍在您处，请尽快归还至站点回收箱");
        pushService.pushUserOrder(order.getUserId(), data);

        log.info("R2 超时取消订单：orderNo={}, bagCode={}, userId={}",
                order.getOrderNo(), order.getBagCode(), order.getUserId());
        return true;
    }

    /**
     * 第二步：IN_USE 且寄件早于 now-7 天且 timeout_flag=0 → 置标记并推送。
     *
     * <p>{@code timeout_flag=0} 同时出现在 WHERE 中，是「提醒恰好一次」的幂等依据：
     * 重复运行只会匹配到 0 行，不会重复推送；已置位订单在归还/遗失确认前始终留在 IN_USE（3.6）。
     */
    private void markTimeoutAfterSevenDays() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minus(TIMEOUT_REMIND_AFTER);
        long lastId = 0L;
        int flagged = 0;

        for (int round = 0; round < MAX_BATCHES_PER_RUN; round++) {
            List<BagOrder> batch = bagOrderMapper.selectPage(new Page<>(1, BATCH_SIZE, false),
                    new LambdaQueryWrapper<BagOrder>()
                            .select(BagOrder::getId, BagOrder::getOrderNo, BagOrder::getBagCode,
                                    BagOrder::getUserId, BagOrder::getShippedAt)
                            .eq(BagOrder::getStatus, OrderStatus.IN_USE.code())
                            .eq(BagOrder::getTimeoutFlag, 0)
                            .lt(BagOrder::getShippedAt, cutoff)
                            .gt(BagOrder::getId, lastId)
                            .orderByAsc(BagOrder::getId)).getRecords();
            if (batch.isEmpty()) {
                break;
            }
            for (BagOrder order : batch) {
                try {
                    if (markTimeoutOne(order, now)) {
                        flagged++;
                    }
                } catch (Exception e) {
                    log.error("寄件超时标记单条订单失败：orderNo={}, reason={}",
                            order.getOrderNo(), e.getMessage(), e);
                }
            }
            lastId = batch.get(batch.size() - 1).getId();
            if (batch.size() < BATCH_SIZE) {
                break;
            }
            if (round == MAX_BATCHES_PER_RUN - 1) {
                log.warn("寄件超时扫描达到单轮批次上限（{}×{}），剩余订单留待下一周期",
                        MAX_BATCHES_PER_RUN, BATCH_SIZE);
            }
        }
        if (flagged > 0) {
            log.info("寄件超时标记完成：本轮置位 {} 单（不改订单状态，仅提醒）", flagged);
        }
    }

    /** 置位单笔超时标记；返回是否本次首次置位（影响行数为 0 即已置位或已被并发流转）。 */
    private boolean markTimeoutOne(BagOrder order, LocalDateTime now) {
        int rows = bagOrderMapper.update(null, new LambdaUpdateWrapper<BagOrder>()
                .eq(BagOrder::getId, order.getId())
                .eq(BagOrder::getStatus, OrderStatus.IN_USE.code())
                .eq(BagOrder::getTimeoutFlag, 0)
                .set(BagOrder::getTimeoutFlag, 1)
                .set(BagOrder::getTimeoutTime, now));
        if (rows == 0) {
            log.debug("订单 {} 超时标记已置位或已被并发流转，跳过", order.getOrderNo());
            return false;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("bagCode", order.getBagCode());
        data.put("status", OrderStatus.IN_USE.code());
        data.put("timeoutFlag", true);
        data.put("message", "寄件后已超过 7 天未归还，请尽快归还至站点回收箱，避免责任认定");
        pushService.pushUserOrder(order.getUserId(), data);

        log.info("寄件超时置位：orderNo={}, bagCode={}, shippedAt={}",
                order.getOrderNo(), order.getBagCode(), order.getShippedAt());
        return true;
    }

    /**
     * 第三步：CANCELLED 且取消早于 now-48h、其袋仍为 ALLOCATED 的订单，只打 WARN。
     *
     * <p>本步骤<b>不写库</b>：异常看板清单由 /alerts 实时查询（契约模块版 8.8），
     * 任务只负责把「升级异常」写进运行日志供运维巡查；批次内汇总为一条 WARN（附少量样本），
     * 避免同一异常每 5 分钟刷出数百行日志。
     */
    private void warnCancelledButBagStillAllocated() {
        LocalDateTime cutoff = LocalDateTime.now().minus(CANCEL_UPGRADE_AFTER);
        long lastId = 0L;
        int total = 0;

        for (int round = 0; round < MAX_BATCHES_PER_RUN; round++) {
            List<BagOrder> batch = bagOrderMapper.selectPage(new Page<>(1, BATCH_SIZE, false),
                    new LambdaQueryWrapper<BagOrder>()
                            .select(BagOrder::getId, BagOrder::getOrderNo, BagOrder::getBagCode,
                                    BagOrder::getUserId, BagOrder::getCancelledAt)
                            .eq(BagOrder::getStatus, OrderStatus.CANCELLED.code())
                            .lt(BagOrder::getCancelledAt, cutoff)
                            .gt(BagOrder::getId, lastId)
                            .orderByAsc(BagOrder::getId)).getRecords();
            if (batch.isEmpty()) {
                break;
            }
            List<BagOrder> stuck = filterBagStillAllocated(batch);
            if (!stuck.isEmpty()) {
                total += stuck.size();
                log.warn("订单取消后 48 小时袋仍为已分配（异常看板经 /alerts 实时展示）：本批 {} 单，样本：{}",
                        stuck.size(), sample(stuck));
            }
            lastId = batch.get(batch.size() - 1).getId();
            if (batch.size() < BATCH_SIZE) {
                break;
            }
            if (round == MAX_BATCHES_PER_RUN - 1) {
                log.warn("取消未处理扫描达到单轮批次上限（{}×{}），剩余订单留待下一周期",
                        MAX_BATCHES_PER_RUN, BATCH_SIZE);
            }
        }
        if (total > 0) {
            log.warn("取消后 48 小时未回收订单本轮共 {} 单（袋仍为已分配，提醒站点尽快处理）", total);
        }
    }

    /** 过滤出「绑定袋当前仍为 ALLOCATED」的订单：一次 IN 查询取回本批涉及袋中的已分配袋码，内存比对。 */
    private List<BagOrder> filterBagStillAllocated(List<BagOrder> batch) {
        List<String> bagCodes = batch.stream().map(BagOrder::getBagCode).distinct().toList();
        Set<String> allocated = bagMapper.selectList(new LambdaQueryWrapper<Bag>()
                        .select(Bag::getBagCode)
                        .in(Bag::getBagCode, bagCodes)
                        .eq(Bag::getStatus, BagStatus.ALLOCATED.code()))
                .stream().map(Bag::getBagCode).collect(Collectors.toSet());
        return batch.stream().filter(o -> allocated.contains(o.getBagCode())).toList();
    }

    /** 拼接日志样本（orderNo/bagCode），最多 {@value #LOG_SAMPLE_SIZE} 条。 */
    private static String sample(List<BagOrder> orders) {
        return orders.stream().limit(LOG_SAMPLE_SIZE)
                .map(o -> o.getOrderNo() + "/" + o.getBagCode())
                .collect(Collectors.joining("、"));
    }
}
