package com.sems.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.infra.RedisKeys;
import com.sems.infra.StockService;
import com.sems.module.audit.mapper.SysMetricMapper;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.statemachine.BagStatus;
import com.sems.common.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 库存对账任务 R4（每 30 分钟；见《系统设计说明书》7.2「R4 定时对账」、7.5「定时任务设计」
 * 库存对账行与 8.3 键空间；契约模块版 8.8）。
 *
 * <p>对每个 ENABLED 站点执行：
 * <ol>
 *   <li>SETNX {@code stock:reconcile:{sid}}（TTL 120 秒，8.3）抢占互斥标记，抢占失败直接跳过
 *       （该站点已有对账在途，Lua 命中标记返回 -2 → 5103，客户端退避重试，7.2）；</li>
 *   <li>校验 {@code stock:sentinel:{sid}}（7.2「哨兵键校验」）：缺失即视为未初始化，
 *       调 {@code stockService.rebuildStationStock} 按 DB 权威口径重建并结束本站点本轮；</li>
 *   <li>间隔 1 秒连续两次聚合 DB {@code COUNT(IDLE)}（{@code idx_bag_station_status} 命中，
 *       两次一致才继续——降低与「已过 Lua、尚未提交 DB」的在途扣减相撞的概率，7.2）；</li>
 *   <li>两次一致 → 与 Redis 原值比对，不同则把差异计入 {@code sys_metric}
 *       （metric_key={@value #METRIC_STOCK_RECONCILE_DIFF_COUNT}，按天累加，供 /alerts「对账差异」与
 *       看板 {@code reconcileDiffCount} 读取）并打 WARN，随后把 DB 权威值 SET 写回
 *       {@code stock:available:{sid}}（不设 TTL）；不一致 → 放弃本轮、删标记留待下一轮重试；</li>
 *   <li>无论成败，最后删除标记（finally 兜底；标记本身 120 秒自愈）。</li>
 * </ol>
 *
 * <p><b>幂等依据</b>（7.5 表「幂等依据」列）：{@code stock:reconcile} 互斥标记。
 * 单机部署无需分布式锁（7.5）；本任务持有的标记同时是 Lua 的「对账中」信号源。
 *
 * <p><b>执行时长</b>：站点逐个串行处理（每站点约 1 秒双采样），单轮时长约为「站点数 × 1s」；
 * 站点表分批遍历（每批 {@value #BATCH_SIZE} 条，命中 {@code idx_station_status}），
 * 每个站点独立 try/catch，单站失败不影响其余站点（7.5 的失败告警：记 ERROR + 下一轮重试）。
 */
@Component
public class StockReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(StockReconcileJob.class);

    /** 对账差异计数指标键（sys_metric.metric_key；看板字段 reconcileDiffCount，见 5.6）。 */
    public static final String METRIC_STOCK_RECONCILE_DIFF_COUNT = "stock_reconcile_diff_count";

    /** 单批遍历站点数上限（7.5：扫描类任务分批，每批最多 500 条）。 */
    private static final int BATCH_SIZE = 500;

    /** 单轮最多遍历批数（站点量小，主要用于防御性兜底与满足 7.5 的分批口径）。 */
    private static final int MAX_BATCHES_PER_RUN = 20;

    /** 任务周期：每 30 分钟（7.5 表）；初始延迟 5 分钟，错开启动期 R3 的全站重建，避免重复写同一批键。 */
    private static final long RECONCILE_INTERVAL_MS = 1_800_000L;

    private static final long RECONCILE_INITIAL_DELAY_MS = 300_000L;

    /** 对账互斥标记 TTL：120 秒（设计书 8.3 键表；进程崩溃时标记自愈，不长期阻塞申领）。 */
    private static final Duration RECONCILE_MARKER_TTL = Duration.ofSeconds(120);

    /** 两次 COUNT(IDLE) 的间隔：1 秒（设计书 7.2 R4 固定值）。 */
    private static final long COUNT_GAP_MILLIS = 1_000L;

    private final StationMapper stationMapper;

    private final BagMapper bagMapper;

    private final StockService stockService;

    private final StringRedisTemplate redisTemplate;

    private final SysMetricMapper sysMetricMapper;

    public StockReconcileJob(StationMapper stationMapper, BagMapper bagMapper,
                             StockService stockService, StringRedisTemplate redisTemplate,
                             SysMetricMapper sysMetricMapper) {
        this.stationMapper = stationMapper;
        this.bagMapper = bagMapper;
        this.stockService = stockService;
        this.redisTemplate = redisTemplate;
        this.sysMetricMapper = sysMetricMapper;
    }

    /**
     * 每 30 分钟对全部 ENABLED 站点执行 R4 对账。
     *
     * <p>整体 try/catch 兜底：异常只记 ERROR，下一周期自动重来，绝不终止调度线程。
     * Redis 熔断（降级）期内直接跳过——降级期不写 Redis 计数，恢复流程会整体重建（7.2）。
     */
    @Scheduled(fixedDelay = RECONCILE_INTERVAL_MS, initialDelay = RECONCILE_INITIAL_DELAY_MS)
    public void reconcile() {
        if (stockService.isDegraded()) {
            log.warn("Redis 熔断期内跳过库存对账（恢复后由重建流程与下一轮对账收敛）");
            return;
        }
        try {
            long lastStationId = 0L;
            int checked = 0;
            for (int round = 0; round < MAX_BATCHES_PER_RUN; round++) {
                List<Station> batch = stationMapper.selectPage(new Page<>(1, BATCH_SIZE, false),
                        new LambdaQueryWrapper<Station>()
                                .select(Station::getId)
                                .eq(Station::getStatus, Constants.STATUS_ENABLED)
                                .gt(Station::getId, lastStationId)
                                .orderByAsc(Station::getId)).getRecords();
                if (batch.isEmpty()) {
                    break;
                }
                for (Station station : batch) {
                    try {
                        reconcileStation(station.getId());
                        checked++;
                    } catch (Exception e) {
                        // 单站失败不影响其余站点；该站留待下一轮（标记已在 finally 中删除）
                        log.error("站点 {} 库存对账失败，留待下一轮重试：{}", station.getId(), e.getMessage(), e);
                    }
                }
                lastStationId = batch.get(batch.size() - 1).getId();
                if (batch.size() < BATCH_SIZE) {
                    break;
                }
                if (round == MAX_BATCHES_PER_RUN - 1) {
                    log.warn("库存对账达到单轮批次上限（{}×{}），剩余站点留待下一周期",
                            MAX_BATCHES_PER_RUN, BATCH_SIZE);
                }
            }
            log.info("库存对账完成：本轮检查 {} 个启用站点", checked);
        } catch (Exception e) {
            log.error("R4 库存对账任务执行异常，本轮到此处终止，下一周期重试：{}", e.getMessage(), e);
        }
    }

    /**
     * 单站点对账：抢占标记 → 哨兵校验 → 1 秒间隔双采样 → 差异计数与写回 → finally 删标记。
     */
    private void reconcileStation(Long stationId) {
        String markerKey = RedisKeys.stockReconcile(stationId);
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(markerKey, "1", RECONCILE_MARKER_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("站点 {} 已有对账在途（stock:reconcile 已存在），本任务跳过", stationId);
            return;
        }
        try {
            // 1) 哨兵键校验（7.2）：缺失即视为「未初始化/Redis 被清空」，触发重建并结束本轮
            if (!stockService.isStationReady(stationId)) {
                log.warn("站点 {} 计数哨兵缺失，触发库存计数重建（7.2 哨兵键校验）", stationId);
                stockService.rebuildStationStock(stationId);
                return;
            }

            // 2) 间隔 1 秒连续两次 COUNT(IDLE)：两次一致才写回，降低与在途扣减相撞的概率（7.2）
            Long first = countIdleBags(stationId);
            try {
                Thread.sleep(COUNT_GAP_MILLIS);
            } catch (InterruptedException e) {
                // 调度关停等中断：恢复中断标记并放弃本站点（finally 仍会删标记，不长期阻塞申领）
                Thread.currentThread().interrupt();
                log.warn("站点 {} 对账等待被中断，本轮跳过", stationId);
                return;
            }
            Long second = countIdleBags(stationId);
            if (first == null || second == null) {
                log.warn("站点 {} 两次库存统计失败，放弃本轮写回", stationId);
                return;
            }
            if (!first.equals(second)) {
                // 两次不一致说明有在途变动，不写回（避免把瞬时值固化）；删标记留待下一轮
                log.info("站点 {} 两次 COUNT(IDLE) 不一致（{} vs {}），放弃本轮写回，下一轮重试",
                        stationId, first, second);
                return;
            }
            long dbCount = second;

            // 3) 与 Redis 原值比对：不同则计入对账差异指标（按天累加，/alerts 第⑥类）并打 WARN
            String availableKey = RedisKeys.stockAvailable(stationId);
            String oldRaw = redisTemplate.opsForValue().get(availableKey);
            Integer oldValue = parseInteger(oldRaw);
            if (oldValue == null || oldValue.longValue() != dbCount) {
                log.warn("库存对账差异：stationId={}，Redis={}，DB(IDLE)={}；按 DB 口径写回并计入 {}",
                        stationId, oldRaw == null ? "(键缺失)" : oldRaw, dbCount,
                        METRIC_STOCK_RECONCILE_DIFF_COUNT);
                bumpDiffMetric(stationId);
            }

            // 4) 写回 DB 权威值（不设 TTL——8.3：stock:available 无 TTL，由对账维护）
            redisTemplate.opsForValue().set(availableKey, String.valueOf(dbCount));
            log.debug("站点 {} 库存对账完成：Redis {} → DB {}", stationId, oldRaw, dbCount);
        } finally {
            // 5) 最后删除标记（成功、不一致放弃、异常路径统一走这里；删除失败有 120 秒 TTL 自愈）
            deleteMarkerQuietly(markerKey, stationId);
        }
    }

    /** DB 权威口径：站点当前 IDLE 袋数（{@code idx_bag_station_status} 命中；查询异常返回 null 按失败处理）。 */
    private Long countIdleBags(Long stationId) {
        return bagMapper.selectCount(new LambdaQueryWrapper<Bag>()
                .eq(Bag::getStationId, stationId)
                .eq(Bag::getStatus, BagStatus.IDLE.code()));
    }

    /**
     * 累加当日对账差异计数（metric_value 按天累加）：
     * 使用 {@code SysMetricMapper.increment} 的 {@code INSERT ... ON DUPLICATE KEY UPDATE} 原子累加，
     * 每次「站点·轮次」命中差异计 1，差异幅度（Redis 与 DB 各为多少）记于 WARN 日志。
     * 指标写入失败只记 ERROR：写回 DB 口径是主动作，不能因旁路计数失败而放弃纠偏。
     */
    private void bumpDiffMetric(Long stationId) {
        try {
            sysMetricMapper.increment(METRIC_STOCK_RECONCILE_DIFF_COUNT, LocalDate.now());
        } catch (Exception e) {
            log.error("站点 {} 库存对账差异指标累加失败（不影响写回）：{}", stationId, e.getMessage(), e);
        }
    }

    /** 删除对账标记；失败只记 WARN（标记 120 秒后自然过期，不阻塞后续轮次）。 */
    private void deleteMarkerQuietly(String markerKey, Long stationId) {
        try {
            redisTemplate.delete(markerKey);
        } catch (Exception e) {
            log.warn("站点 {} 对账标记删除失败（{} 秒后自愈）：{}",
                    stationId, RECONCILE_MARKER_TTL.toSeconds(), e.getMessage());
        }
    }

    /** 解析 Redis 中的库存计数；缺失或非整数返回 null（调用方按「与 DB 不一致」处理并写回）。 */
    private static Integer parseInteger(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
