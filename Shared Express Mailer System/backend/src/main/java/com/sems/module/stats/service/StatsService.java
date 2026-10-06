package com.sems.module.stats.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.infra.CounterService;
import com.sems.infra.StockService;
import com.sems.module.audit.entity.SysMetric;
import com.sems.module.audit.mapper.SysMetricMapper;
import com.sems.module.audit.service.AuditLogService;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.stats.dto.DashboardVo;
import com.sems.module.stats.dto.OrderTrendVo;
import com.sems.module.stats.dto.StockDistributionVo;
import com.sems.module.stats.dto.TurnoverVo;
import com.sems.module.stats.mapper.StatsQueryMapper;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.StationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 平台看板统计服务（M6，接口见《系统设计说明书》5.6，统计口径唯一出处为 3.5「关键时限与计数口径表」）。
 *
 * <p>四个读接口：
 * <ul>
 *   <li>{@link #dashboard()} 看板汇总：状态分布、在用/在循环袋数、今日取/还、预警站点数、
 *       审计丢弃与对账差异（口径见 {@link DashboardVo}）；</li>
 *   <li>{@link #orderTrend(Integer)} 近 N 日取袋/归还趋势（borrow 按 created_at、return 按 confirmed_at 分组）；</li>
 *   <li>{@link #bagTurnover(Integer)} 按站点周转率（完成归还确认订单数 ÷ 在循环袋数均值）；</li>
 *   <li>{@link #stockDistribution()} 库存分布（按状态 Map + 按站点列表）。</li>
 * </ul>
 *
 * <p><b>为什么全部实时聚合</b>：校园规模下各指标基于索引实时聚合即可满足性能目标（5.6），
 * {@code stats_daily} 仅作为可选的历史沉淀（8.2，由 {@code com.sems.job.StatsDailyJob} 每日 01:00 写入）。
 * 本类读接口不写库，只读事务（{@code readOnly}）以走只读库连接、避免误写；
 * 唯一的写入点是 8.3 指定的「回收确认 INCR return」——{@link #incrementTodayReturn(Long)}
 * 只写 Redis 计数视图，不写数据库。
 *
 * <p><b>数据来源纪律</b>：跨模块只注入对方 Mapper（契约 1 允许）；
 * 今日计数走 {@link CounterService}（Redis 视图）、可用库存在缺失时回落 DB 权威口径（7.2）；
 * 审计丢弃数走 {@link AuditLogService#getAuditDropCount()}（7.6 指定的唯一读取入口）。
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    /**
     * 指标键：库存对账差异的当日累计（R4 对账差异告警，见 7.2）。
     *
     * <p>写入方为 {@code job/StockReconcileJob}（每 30 分钟对账，差异累加同名键、按
     * {@code (metric_key, stat_date)} 唯一键落 sys_metric）；读取方为本类看板
     * {@code reconcileDiffCount} 与 {@link AlertService} 第六类告警。
     * 键名在两处共用本常量，禁止各自写字面量（防两处口径漂移）。
     */
    public static final String METRIC_STOCK_RECONCILE_DIFF_COUNT = "stock_reconcile_diff_count";

    /** 「在用袋数」口径的状态集合（3.5：已分配、使用中、回收在途、待消毒、可复用）。 */
    private static final Set<BagStatus> IN_USE_STATUSES = EnumSet.of(
            BagStatus.ALLOCATED, BagStatus.IN_USE, BagStatus.RETURNING,
            BagStatus.TO_DISINFECT, BagStatus.REUSABLE);

    /** 全平台汇总键的站点段（8.3：{@code stats:today:0} 为可选的全平台汇总键）。 */
    private static final long PLATFORM_STATS_STATION_ID = 0L;

    /** 今日统计 Hash 的域：取袋 / 还袋（与 CounterService.statsTodayIncrement 的写入域一致，8.3）。 */
    private static final String STATS_FIELD_BORROW = "borrow";
    private static final String STATS_FIELD_RETURN = "return";

    /** 趋势/周转率的统计周期上限（天）：看板为实时聚合，限制单次查询跨度，防超大入参拖垮统计。 */
    private static final int MAX_PERIOD_DAYS = 90;

    /** order-trend 默认周期（天）：与前端看板「近 7 日」一致（5.6）。 */
    private static final int DEFAULT_ORDER_TREND_DAYS = 7;

    /** bag-turnover 默认周期（天）：与前端周转率柱状图的一月观察窗一致（5.6）。 */
    private static final int DEFAULT_TURNOVER_DAYS = 30;

    private final StatsQueryMapper statsQueryMapper;
    private final StationMapper stationMapper;
    private final BagOrderMapper bagOrderMapper;
    private final CounterService counterService;
    private final StockService stockService;
    private final AuditLogService auditLogService;
    private final SysMetricMapper sysMetricMapper;

    public StatsService(StatsQueryMapper statsQueryMapper,
                        StationMapper stationMapper,
                        BagOrderMapper bagOrderMapper,
                        CounterService counterService,
                        StockService stockService,
                        AuditLogService auditLogService,
                        SysMetricMapper sysMetricMapper) {
        this.statsQueryMapper = statsQueryMapper;
        this.stationMapper = stationMapper;
        this.bagOrderMapper = bagOrderMapper;
        this.counterService = counterService;
        this.stockService = stockService;
        this.auditLogService = auditLogService;
        this.sysMetricMapper = sysMetricMapper;
    }

    /**
     * 看板汇总（5.6）。一次状态分组查询同时派生「在循环袋数」「在用袋数」两个口径，
     * 避免为同一底账重复全表计数；其余字段各自取权威来源（见类头）。
     */
    @Transactional(readOnly = true)
    public DashboardVo dashboard() {
        DashboardVo vo = new DashboardVo();

        // ① 各状态分布 + 两个派生袋数口径（3.5）
        Map<String, Long> byStatus = bagStatusDistribution();
        vo.setStatusDistribution(byStatus);
        long inUse = 0L;
        for (BagStatus status : IN_USE_STATUSES) {
            inUse += byStatus.getOrDefault(status.code(), 0L);
        }
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        vo.setInUseCount(inUse);
        long inCirculation = total
                - byStatus.getOrDefault(BagStatus.SCRAPPED.code(), 0L)
                - byStatus.getOrDefault(BagStatus.LOST.code(), 0L);
        vo.setInCirculationCount(inCirculation);
        vo.setInCycleCount(inCirculation);

        // ② 今日取/还：汇总键优先，缺失字段按站点求和兜底（5.6「求和读取」口径）
        Map<String, Long> platformToday = counterService.getStatsToday(PLATFORM_STATS_STATION_ID);
        Long todayBorrow = platformToday.get(STATS_FIELD_BORROW);
        Long todayReturn = platformToday.get(STATS_FIELD_RETURN);
        if (todayBorrow == null || todayReturn == null) {
            long[] stationSums = sumStationToday();
            if (todayBorrow == null) {
                todayBorrow = stationSums[0];
            }
            if (todayReturn == null) {
                todayReturn = stationSums[1];
            }
        }
        vo.setTodayBorrow(todayBorrow);
        vo.setTodayReturn(todayReturn);

        // ③ 预警站点数（启用站点且可用库存低于阈值；Redis 缺失时回落 DB 口径）
        vo.setWarningStationCount(warningStationCount());

        // ④ 系统指标（当日累计）：审计丢弃读 7.6 的指定入口；对账差异直读 sys_metric（7.2 R4）
        vo.setAuditDropCount(auditLogService.getAuditDropCount());
        vo.setReconcileDiffCount(todayMetricValue(METRIC_STOCK_RECONCILE_DIFF_COUNT));

        return vo;
    }

    // ------------------------------------------------------------------
    // 写入侧（8.3 指定的 stats:today 写入点）
    // ------------------------------------------------------------------

    /**
     * 今日归还计数 +1（8.3 指定写入点：「回收确认 INCR return」；看板「今日归还」KPI 的数据源）。
     *
     * <p>为什么写读同源放在本类：{@code return} 域的字段名与站点键口径的唯一读者是本类看板，
     * 写入点同处一地可避免键名/字段名两处各自写字面量而漂移（与
     * {@link #METRIC_STOCK_RECONCILE_DIFF_COUNT} 同一条纪律）。
     *
     * <p>调用时机：回收确认链路中<b>每只确认成功（真正完成状态流转）的袋调用一次</b>，
     * 站点取「回收箱所属站点」（4.4 第④条）；命中接口级/逐袋幂等回放的请求不得重复调用。
     *
     * <p>为什么不抛异常：Redis 计数只是看板视图（7.2：Redis 为视图、DB 为权威），
     * 回收确认是业务写操作——计数写失败不得回滚确认事务；熔断降级期按 7.2「降级期间不写
     * Redis 计数」直接跳过（该时段计数缺失，不追补）。
     *
     * @param stationId 回收确认站点（回收箱所属站点）；null 时忽略
     */
    public void incrementTodayReturn(Long stationId) {
        if (stationId == null) {
            return;
        }
        if (stockService.isDegraded()) {
            log.debug("库存熔断打开，跳过站点今日归还统计（stationId={}）", stationId);
            return;
        }
        try {
            counterService.statsTodayIncrement(stationId, STATS_FIELD_RETURN);
        } catch (RuntimeException e) {
            log.warn("站点今日归还统计写入失败（stationId={}），本次跳过：{}", stationId, e.getMessage());
        }
    }

    /**
     * 近 N 日取袋/归还订单趋势（5.6；borrow 按 {@code created_at} 分组、return 按 {@code confirmed_at} 分组）。
     * 返回按日期升序的连续曲线：无数据的日期补 0，保证折线横轴连续。
     *
     * @param days 统计天数（含今天），null 取默认 7；取值范围 1–90
     */
    @Transactional(readOnly = true)
    public List<OrderTrendVo> orderTrend(Integer days) {
        int span = normalizeDays(days, DEFAULT_ORDER_TREND_DAYS);
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(span - 1L);
        // 右开区间到明日 0 点：把今天整日纳入，同时避免用 23:59:59 造成毫秒边界误差
        LocalDateTime begin = start.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        Map<LocalDate, Long> borrowByDay = toDateCountMap(bagOrderMapper.countBorrowByDay(begin, end));
        Map<LocalDate, Long> returnByDay = toDateCountMap(bagOrderMapper.countReturnByDay(begin, end));

        List<OrderTrendVo> trend = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            LocalDate date = start.plusDays(i);
            OrderTrendVo point = new OrderTrendVo();
            point.setDate(date);
            point.setBorrowCount(borrowByDay.getOrDefault(date, 0L));
            point.setReturnCount(returnByDay.getOrDefault(date, 0L));
            trend.add(point);
        }
        return trend;
    }

    /**
     * 按站点周转率（5.6；口径见 3.5：完成归还确认的订单数 ÷ 在循环袋数均值）。
     *
     * <p>「在循环袋数均值」的实现说明：本接口以当前在循环袋数（非报废非遗失）近似统计周期均值——
     * 校园规模下站点袋量小、日间波动有限，且 {@code stats_daily} 为可选快照表（8.2）不一定有数据；
     * 后续若启用日汇总，可从快照按日平均精化。分母为 0 属数据不足，返回 0 而不是抛异常，
     * 保证柱状图在部分站点无袋时仍可整图渲染。
     *
     * @param days 统计周期（含今天），null 取默认 30；取值范围 1–90
     */
    @Transactional(readOnly = true)
    public List<TurnoverVo> bagTurnover(Integer days) {
        int span = normalizeDays(days, DEFAULT_TURNOVER_DAYS);
        LocalDate today = LocalDate.now();
        LocalDateTime begin = today.minusDays(span - 1L).atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        Map<Long, Long> finishedByStation = toStationCountMap(
                bagOrderMapper.countFinishedByStation(begin, end));
        Map<Long, Long> circulationByStation = toStationCountMap(
                statsQueryMapper.countInCirculationByStation());

        List<Station> stations = stationMapper.selectList(
                Wrappers.lambdaQuery(Station.class).orderByAsc(Station::getId));
        List<TurnoverVo> turnover = new ArrayList<>(stations.size());
        for (Station station : stations) {
            long finished = finishedByStation.getOrDefault(station.getId(), 0L);
            long circulation = circulationByStation.getOrDefault(station.getId(), 0L);
            TurnoverVo vo = new TurnoverVo();
            vo.setStationId(station.getId());
            vo.setStationName(station.getName());
            vo.setTurnoverRate(circulation <= 0L ? 0.0 : round4((double) finished / circulation));
            turnover.add(vo);
        }
        return turnover;
    }

    /**
     * 库存分布（5.6，按站点/按状态，供饼图）：
     * 按状态为 11 态全集计数；按站点为在循环袋数（非报废非遗失，3.5），
     * 报废已退出循环、遗失不在站点，均不参与站点分布。
     */
    @Transactional(readOnly = true)
    public StockDistributionVo stockDistribution() {
        StockDistributionVo vo = new StockDistributionVo();
        vo.setStatusDistribution(bagStatusDistribution());

        Map<Long, Long> circulationByStation = toStationCountMap(
                statsQueryMapper.countInCirculationByStation());
        List<Station> stations = stationMapper.selectList(
                Wrappers.lambdaQuery(Station.class).orderByAsc(Station::getId));
        List<StockDistributionVo.StationSlice> slices = new ArrayList<>(stations.size());
        for (Station station : stations) {
            StockDistributionVo.StationSlice slice = new StockDistributionVo.StationSlice();
            slice.setStationId(station.getId());
            slice.setStationName(station.getName());
            slice.setCount(circulationByStation.getOrDefault(station.getId(), 0L));
            slices.add(slice);
        }
        vo.setByStation(slices);
        return vo;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 各状态袋数（11 态全集补 0）：先以枚举顺序铺满 0，再覆盖查询结果，
     * 保证同一底账无论有无某状态，前端拿到的键集合都一致、图例不跳动。
     */
    private Map<String, Long> bagStatusDistribution() {
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (BagStatus status : BagStatus.values()) {
            distribution.put(status.code(), 0L);
        }
        for (Map<String, Object> row : statsQueryMapper.countBagByStatus()) {
            Object code = row.get("status");
            Long count = toLong(row.get("cnt"));
            if (code == null || count == null) {
                continue;
            }
            // 未知状态码也如实保留（数据异常应当可见），命中已铺 0 的状态则覆盖
            distribution.merge(String.valueOf(code), count, Long::sum);
        }
        return distribution;
    }

    /**
     * 今日取/还按站点求和（5.6：平台看板按站点 {@code stats:today:{stationId}} 求和读取）。
     * 遍历全部站点（含停用站点）：今日计数是已发生的事实，停用不影响历史口径。
     *
     * @return {@code [borrow 合计, return 合计]}
     */
    private long[] sumStationToday() {
        long borrow = 0L;
        long returned = 0L;
        for (Station station : stationMapper.selectList(Wrappers.lambdaQuery(Station.class))) {
            Map<String, Long> today = counterService.getStatsToday(station.getId());
            borrow += today.getOrDefault(STATS_FIELD_BORROW, 0L);
            returned += today.getOrDefault(STATS_FIELD_RETURN, 0L);
        }
        return new long[]{borrow, returned};
    }

    /**
     * 预警站点数：启用站点中「可用库存 < 预警阈值」的数量（阈值 &le; 0 表示不预警，见 station 表口径）。
     * 可用库存优先读 Redis 视图，计数未初始化/熔断降级返回 null 时回落 DB 权威口径
     * {@code COUNT(IDLE)}（7.2：Redis 为视图、DB 为权威）——因此 Redis 故障时该指标仍可用。
     */
    private int warningStationCount() {
        Map<Long, Long> idleByStation = toStationCountMap(statsQueryMapper.countIdleByStation());
        List<Station> enabledStations = stationMapper.selectList(Wrappers.lambdaQuery(Station.class)
                .eq(Station::getStatus, Constants.STATUS_ENABLED));
        int warning = 0;
        for (Station station : enabledStations) {
            Integer threshold = station.getWarnThreshold();
            if (threshold == null || threshold <= 0) {
                continue;
            }
            Integer availableFromRedis = stockService.getAvailable(station.getId());
            long current = availableFromRedis != null
                    ? availableFromRedis
                    : idleByStation.getOrDefault(station.getId(), 0L);
            // 「低于」阈值告警（3.4.3：库存低于预警阈值触发），等于阈值不告警
            if (current < threshold) {
                warning++;
            }
        }
        return warning;
    }

    /** 读某指标键的当日累计值（无记录返回 0）。指标表按 (metric_key, stat_date) 唯一键按天一行。 */
    private long todayMetricValue(String metricKey) {
        SysMetric metric = sysMetricMapper.selectOne(Wrappers.lambdaQuery(SysMetric.class)
                .eq(SysMetric::getMetricKey, metricKey)
                .eq(SysMetric::getStatDate, LocalDate.now()));
        return metric == null || metric.getMetricValue() == null ? 0L : metric.getMetricValue();
    }

    /** 校验并归一统计天数：null 取默认值；超范围抛 3001（天数本身非法，不应静默截断成另一种口径）。 */
    private static int normalizeDays(Integer days, int fallback) {
        if (days == null) {
            return fallback;
        }
        if (days < 1 || days > MAX_PERIOD_DAYS) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "days 取值范围 1-" + MAX_PERIOD_DAYS + " 天");
        }
        return days;
    }

    /** {@code {statDate, cnt}} 行集 → 日期 → 计数（同一天重复行求和，防聚合键异常时丢数）。 */
    private static Map<LocalDate, Long> toDateCountMap(List<Map<String, Object>> rows) {
        Map<LocalDate, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            LocalDate date = toLocalDate(row.get("statDate"));
            Long count = toLong(row.get("cnt"));
            if (date != null && count != null) {
                result.merge(date, count, Long::sum);
            }
        }
        return result;
    }

    /** {@code {stationId, cnt}} 行集 → 站点ID → 计数。 */
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

    /** 聚合值转 Long：COUNT(*) 由驱动返回 Number；脏值返回 null 由调用方跳过，不打断整个看板。 */
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

    /**
     * 聚合日期转 LocalDate：趋势 SQL 以 {@code DATE_FORMAT(..., '%Y-%m-%d')} 输出字符串
     * （与驱动版本无关，避免 DATE 列映射为 java.sql.Date 的差异），此处兼容 Date/String 两种形态。
     */
    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        String text = String.valueOf(value);
        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 周转率保留 4 位小数：柱状图展示足够，避免 0.3333333333333333 之类的长尾。 */
    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
