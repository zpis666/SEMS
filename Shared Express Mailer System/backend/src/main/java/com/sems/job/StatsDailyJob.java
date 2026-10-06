package com.sems.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sems.common.util.JsonUtils;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.stats.entity.StatsDaily;
import com.sems.module.stats.mapper.StatsDailyMapper;
import com.sems.module.stats.mapper.StatsQueryMapper;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.StationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日统计汇总任务（每日 01:00；见《系统设计说明书》7.5「定时任务设计」日统计汇总行、
 * 8.2 {@code stats_daily} 表口径、《需求规格说明书》FR-M6-14）。
 *
 * <p>汇总对象为<b>刚结束的一个自然日</b>（01:00 执行时取 yesterday）：订单量（取袋按下单时间
 * {@code created_at}、归还按回收确认时间 {@code confirmed_at}）、周转率、库存分布快照，
 * 按站点各写一行并写一行全平台汇总（{@code station_id=0}，8.2 约定）。
 *
 * <p><b>幂等依据</b>（7.5 表「幂等依据」列：{@code (date, station_id)} 唯一键）：
 * 写入前按唯一键查行，存在则整行覆盖更新、不存在则插入；同日重复执行结果一致，
 * 对历史日期重算也安全。单机部署不加分布式锁（7.5）。
 *
 * <p><b>口径说明</b>：
 * <ul>
 *   <li>周转率 = 当日完成归还确认订单数 ÷ 在循环袋数。分母以任务执行时点的在循环袋数
 *       （非报废非遗失，3.5）近似当日均值——与 {@code StatsService.bagTurnover} 的近似口径一致，
 *       校园规模下站点袋量小、日间波动有限；分母为 0 即数据不足，写 0 而不是跳过该行；</li>
 *   <li>库存分布快照按 11 态全集补 0 后落 {@code stock_json}（与看板的键集合口径一致，
 *       无袋状态补 0、未知状态码如实保留）；</li>
 *   <li>归还订单按<b>取袋站点</b>归属（复用 {@code BagOrderMapper#countFinishedByStation}，
 *       与看板周转率分子同口径）；8.2 未定义站点维度的归还地归属，跨站归还的归还地细分留待后续；</li>
 *   <li>站点清单取全表（含停用站点）：当日计数是已发生的事实，停用不影响历史口径
 *       （同 {@code StatsService.sumStationToday} 的既定口径）。</li>
 * </ul>
 *
 * <p><b>执行与容错</b>：整轮 try/catch 兜底，单行写入独立 try/catch（单站点失败不拖垮整轮），
 * 异常只记 ERROR，绝不终止调度线程；单轮扫描有界（一天区间 + 小表聚合），符合 7.5 的规模约定。
 */
@Component
public class StatsDailyJob {

    private static final Logger log = LoggerFactory.getLogger(StatsDailyJob.class);

    /** 全平台汇总行的站点段（8.2：{@code station_id=0} 表示全平台）。 */
    private static final long PLATFORM_STATION_ID = 0L;

    private final StatsDailyMapper statsDailyMapper;
    private final StationMapper stationMapper;
    private final BagOrderMapper bagOrderMapper;
    private final BagMapper bagMapper;
    private final StatsQueryMapper statsQueryMapper;

    public StatsDailyJob(StatsDailyMapper statsDailyMapper,
                         StationMapper stationMapper,
                         BagOrderMapper bagOrderMapper,
                         BagMapper bagMapper,
                         StatsQueryMapper statsQueryMapper) {
        this.statsDailyMapper = statsDailyMapper;
        this.stationMapper = stationMapper;
        this.bagOrderMapper = bagOrderMapper;
        this.bagMapper = bagMapper;
        this.statsQueryMapper = statsQueryMapper;
    }

    /**
     * 每日 01:00 执行（7.5 表：cron {@code 0 0 1 * * ?}），汇总刚结束的一个自然日。
     * 本方法不向外抛异常，保证调度线程不被一次故障终止。
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void aggregateDaily() {
        LocalDate statDate = LocalDate.now().minusDays(1);
        try {
            LocalDateTime begin = statDate.atStartOfDay();
            LocalDateTime end = statDate.plusDays(1).atStartOfDay();

            Map<Long, Long> borrowByStation = countBorrowByStation(begin, end);
            Map<Long, Long> returnByStation = toStationCountMap(
                    bagOrderMapper.countFinishedByStation(begin, end));
            Map<Long, Long> circulationByStation = toStationCountMap(
                    statsQueryMapper.countInCirculationByStation());
            Map<Long, Map<String, Long>> statusByStation = countStatusByStation();

            int written = 0;
            // 全平台汇总行（station_id=0）：各指标按站点行求和，库存分布取全库状态分组
            StatsDaily platform = buildRow(statDate, PLATFORM_STATION_ID,
                    sum(borrowByStation), sum(returnByStation), sum(circulationByStation),
                    buildStatusJson(toStatusMap(statsQueryMapper.countBagByStatus())));
            saveRow(platform);
            written++;

            // 各站点行：站点清单取全表（含停用站点，停用不影响历史口径）
            List<Station> stations = stationMapper.selectList(Wrappers.lambdaQuery(Station.class));
            for (Station station : stations) {
                try {
                    StatsDaily row = buildRow(statDate, station.getId(),
                            borrowByStation.getOrDefault(station.getId(), 0L),
                            returnByStation.getOrDefault(station.getId(), 0L),
                            circulationByStation.getOrDefault(station.getId(), 0L),
                            buildStatusJson(statusByStation.getOrDefault(station.getId(), Map.of())));
                    saveRow(row);
                    written++;
                } catch (Exception e) {
                    // 单站点写入失败不影响其余站点；下一次执行会按唯一键重算覆盖
                    log.error("日统计汇总单站点写入失败：date={}, stationId={}", statDate, station.getId(), e);
                }
            }
            log.info("日统计汇总完成：date={}，站点 {} 个，写入（含更新）{} 行",
                    statDate, stations.size(), written);
        } catch (Exception e) {
            log.error("日统计汇总任务执行异常，本轮到此处终止，下一日重试：{}", e.getMessage(), e);
        }
    }

    /**
     * 按站点统计当日取袋（申领）订单数：建单时间 {@code created_at} 落在当日的订单，
     * 按取袋站点分组（命中 {@code idx_order_created_at}）。
     * 口径与 {@code BagOrderMapper#countBorrowByDay} 一致，只是多了站点维度。
     */
    private Map<Long, Long> countBorrowByStation(LocalDateTime begin, LocalDateTime end) {
        QueryWrapper<BagOrder> wrapper = new QueryWrapper<>();
        wrapper.select("station_id AS stationId", "COUNT(*) AS cnt")
                .ge("created_at", begin)
                .lt("created_at", end)
                .groupBy("station_id");
        return toStationCountMap(bagOrderMapper.selectMaps(wrapper));
    }

    /** 按站点统计各状态袋数（库存分布快照的数据源；小表聚合，命中 {@code idx_bag_station_status}）。 */
    private Map<Long, Map<String, Long>> countStatusByStation() {
        QueryWrapper<Bag> wrapper = new QueryWrapper<>();
        wrapper.select("station_id AS stationId", "status AS status", "COUNT(*) AS cnt")
                .groupBy("station_id", "status");
        Map<Long, Map<String, Long>> result = new HashMap<>();
        for (Map<String, Object> row : bagMapper.selectMaps(wrapper)) {
            Long stationId = toLong(row.get("stationId"));
            Object status = row.get("status");
            Long count = toLong(row.get("cnt"));
            if (stationId == null || status == null || count == null) {
                continue;
            }
            result.computeIfAbsent(stationId, key -> new HashMap<>())
                    .merge(String.valueOf(status), count, Long::sum);
        }
        return result;
    }

    /**
     * 组装一行日统计。周转率分母为 0（站点无在循环袋）时写 0，保证柱状图/曲线不因缺行断裂。
     */
    private static StatsDaily buildRow(LocalDate statDate, long stationId,
                                       long ordersBorrow, long ordersReturn,
                                       long circulation, String stockJson) {
        StatsDaily row = new StatsDaily();
        row.setDate(statDate);
        row.setStationId(stationId);
        row.setOrdersBorrow((int) ordersBorrow);
        row.setOrdersReturn((int) ordersReturn);
        row.setTurnoverRate(circulation <= 0L ? 0.0 : round4((double) ordersReturn / circulation));
        row.setStockJson(stockJson);
        return row;
    }

    /**
     * 按唯一键 (date, station_id) 幂等写入：存在则整行覆盖更新（重算同一日结果一致），
     * 不存在则插入。
     */
    private void saveRow(StatsDaily row) {
        StatsDaily existing = statsDailyMapper.selectOne(new LambdaQueryWrapper<StatsDaily>()
                .eq(StatsDaily::getDate, row.getDate())
                .eq(StatsDaily::getStationId, row.getStationId()));
        if (existing == null) {
            statsDailyMapper.insert(row);
            return;
        }
        row.setId(existing.getId());
        statsDailyMapper.updateById(row);
    }

    /** 库存分布快照：11 态全集补 0 后序列化；未知状态码也如实保留（数据异常应当可见）。 */
    private static String buildStatusJson(Map<String, Long> counts) {
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (BagStatus status : BagStatus.values()) {
            ordered.put(status.code(), counts.getOrDefault(status.code(), 0L));
        }
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            ordered.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return JsonUtils.toJson(ordered);
    }

    /** {@code {stationId, cnt}} 行集 → 站点ID → 计数（同一天重复行求和，防聚合键异常时丢数）。 */
    private static Map<Long, Long> toStationCountMap(List<Map<String, Object>> rows) {
        Map<Long, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long stationId = toLong(row.get("stationId"));
            Long count = toLong(row.get("cnt"));
            if (stationId != null && count != null) {
                result.merge(stationId, count, Long::sum);
            }
        }
        return result;
    }

    /** {@code {status, cnt}} 行集 → 状态码 → 计数。 */
    private static Map<String, Long> toStatusMap(List<Map<String, Object>> rows) {
        Map<String, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object status = row.get("status");
            Long count = toLong(row.get("cnt"));
            if (status != null && count != null) {
                result.merge(String.valueOf(status), count, Long::sum);
            }
        }
        return result;
    }

    private static long sum(Map<Long, Long> counts) {
        long total = 0L;
        for (Long value : counts.values()) {
            total += value == null ? 0L : value;
        }
        return total;
    }

    /** 聚合值转 Long：COUNT(*) 由驱动返回 Number；脏值返回 null 由调用方跳过。 */
    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 周转率保留 4 位小数：与 StatsService 展示口径一致，避免长尾小数。 */
    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
