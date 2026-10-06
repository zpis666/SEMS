package com.sems.module.station.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.common.PageResult;
import com.sems.common.util.JsonUtils;
import com.sems.infra.IdempotentService;
import com.sems.module.station.dto.CreateStationDto;
import com.sems.module.station.dto.NearbyStationVo;
import com.sems.module.station.dto.StationQuery;
import com.sems.module.station.dto.StationStatusDto;
import com.sems.module.station.dto.StationStockVo;
import com.sems.module.station.dto.StationVo;
import com.sems.module.station.dto.UpdateStationDto;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.StationMapper;
import com.sems.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 共享站点管理服务（M3，见《系统设计说明书》5.3「共享站点管理模块」、3.4.2 与 4.4「数据权限」）。
 *
 * <p>覆盖端点的业务侧：站点分页查询、新增、编辑、停用/启用、站点库存视图、库存预警列表、
 * 用户端 nearby 站点引导。回收箱清单与回收确认见 {@link RecycleService}（复用本站点服务的
 * 资源存在性与「本站」校验；停用前置校验复用 {@link StockQueryService} 的库存统计口径）。
 *
 * <p><b>权限口径：</b>垂直权限由 Controller 的 {@code @RequireRole} 承担；
 * 水平数据权限在本层强制——{@link #assertStationAccess}（平台管理员全站放行、站点管理员限本人绑定站点，
 * 不符抛 2002），且遵循「先资源存在性（{@link #requireStation}）、后归属」的校验顺序（契约 6）。
 *
 * <p><b>写接口幂等：</b>新增/编辑/停用启用四个写入口均按契约第 4 节的统一范式执行
 * （接口级幂等键 = HTTP 方法 + 路径模板，TTL 10 分钟，失败即删占位）。
 */
@Service
public class StationService {

    private static final Logger log = LoggerFactory.getLogger(StationService.class);

    /** 分页默认条数（设计书 6.1：默认 10）。 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** 分页单页上限（设计书 6.1：最大 100，超出按上限截断）。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 查询时间入参的日期时间格式（前端展示口径为 yyyy-MM-dd HH:mm:ss）。 */
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 查询时间入参的分钟精度格式。 */
    private static final DateTimeFormatter DATE_TIME_MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 查询时间入参的日期格式（起止分别取当天最小/最大时刻）。 */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StationMapper stationMapper;
    private final StockQueryService stockQueryService;
    private final IdempotentService idempotentService;

    public StationService(StationMapper stationMapper,
                          StockQueryService stockQueryService,
                          IdempotentService idempotentService) {
        this.stationMapper = stationMapper;
        this.stockQueryService = stockQueryService;
        this.idempotentService = idempotentService;
    }

    // ==================== 查询 ====================

    /** 站点分页查询（GET /stations，平台管理员；见 5.3 与 6.1 分页约定）。 */
    public PageResult<StationVo> page(StationQuery query) {
        StationQuery condition = query == null ? new StationQuery() : query;
        int pageNum = condition.getPageNum() == null || condition.getPageNum() < 1 ? 1 : condition.getPageNum();
        int pageSize = condition.getPageSize() == null || condition.getPageSize() < 1
                ? DEFAULT_PAGE_SIZE
                : Math.min(condition.getPageSize(), MAX_PAGE_SIZE);

        LambdaQueryWrapper<Station> wrapper = new LambdaQueryWrapper<>();
        String keyword = trimToNull(condition.getKeyword());
        if (keyword != null) {
            // 关键字命中名称或地址（括号分组，避免与其它条件的 AND 语义串扰）
            wrapper.and(w -> w.like(Station::getName, keyword).or().like(Station::getAddress, keyword));
        }
        String status = trimToNull(condition.getStatus());
        if (status != null) {
            if (!Constants.STATUS_ENABLED.equals(status) && !Constants.STATUS_DISABLED.equals(status)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "状态筛选仅支持 ENABLED / DISABLED");
            }
            wrapper.eq(Station::getStatus, status);
        }
        LocalDateTime begin = parseTime(condition.getBeginTime(), false);
        LocalDateTime end = parseTime(condition.getEndTime(), true);
        if (begin != null) {
            wrapper.ge(Station::getCreatedAt, begin);
        }
        if (end != null) {
            wrapper.le(Station::getCreatedAt, end);
        }
        applyOrder(wrapper, condition.getOrderBy());

        Page<Station> page = stationMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<StationVo> records = new ArrayList<>(page.getRecords().size());
        for (Station station : page.getRecords()) {
            records.add(toVo(station));
        }
        return PageResult.of(records, page.getTotal(), page.getSize());
    }

    /** 用户端站点列表（GET /stations/nearby，登录用户；见 5.2：过滤停用站，供取袋与归还引导）。 */
    public List<NearbyStationVo> nearby() {
        List<Station> stations = stationMapper.selectList(new LambdaQueryWrapper<Station>()
                .eq(Station::getStatus, Constants.STATUS_ENABLED)
                .orderByAsc(Station::getId));
        List<NearbyStationVo> result = new ArrayList<>(stations.size());
        for (Station station : stations) {
            NearbyStationVo vo = new NearbyStationVo();
            vo.setId(station.getId());
            vo.setName(station.getName());
            vo.setAddress(station.getAddress());
            vo.setOpenTime(station.getOpenTime());
            // 可用库存 Redis 优先；计数未就绪回落 DB 统计 IDLE（权威口径，见 3.1/7.2）
            vo.setAvailableStock(stockQueryService.getAvailableStock(station.getId()));
            result.add(vo);
        }
        return result;
    }

    /**
     * 站点库存视图（GET /stations/{id}/stock，平台管理员 / 本站站点管理员；见 5.3）。
     *
     * <p>各状态计数来自 DB（权威且稳定）；可用库存优先读 Redis 计数视图，
     * 计数缺失时先触发重建；仍不可用（熔断/重建失败）抛 5102 可重试——
     * 「Redis 优先读取」的视图语义不允许用未初始化计数冒充实时值（FR-M3-05 验收口径）。
     */
    public StationStockVo stock(Long id) {
        Station station = requireStation(id);
        assertStationAccess(id);
        Map<String, Long> statusCounts = stockQueryService.countByStatus(id);
        Integer available = stockQueryService.readAvailableWithRebuild(id);
        if (available == null) {
            throw new BizException(ErrorCode.STOCK_NOT_READY, "站点库存计数未就绪，请稍后重试");
        }
        return toStockVo(station, statusCounts, available);
    }

    /** 库存预警列表（GET /stations/stock-warning，平台管理员；见 5.3 与 5.6：低于预警阈值的站点）。 */
    public List<StationStockVo> stockWarning() {
        // 仅启用且配置了阈值的站点参与预警（阈值 0=不预警；停用站不纳入调度决策）
        List<Station> stations = stationMapper.selectList(new LambdaQueryWrapper<Station>()
                .eq(Station::getStatus, Constants.STATUS_ENABLED)
                .gt(Station::getWarnThreshold, 0)
                .orderByAsc(Station::getId));
        List<StationStockVo> result = new ArrayList<>();
        for (Station station : stations) {
            int available = stockQueryService.getAvailableStock(station.getId());
            if (isWarning(available, station.getWarnThreshold())) {
                // 预警列表只回传判定所需字段（statusCounts 为空，序列化时省略），控制响应体积
                result.add(toStockVo(station, null, available));
            }
        }
        return result;
    }

    // ==================== 写操作（幂等范式见契约第 4 节） ====================

    /** 新增站点（POST /stations，平台管理员；见 5.3）。 */
    @Transactional(rollbackFor = Exception.class)
    public StationVo create(CreateStationDto dto, String clientRequestId) {
        String apiKey = "POST /stations";
        String cached = idempotentService.findCompleted(apiKey, clientRequestId);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            return JsonUtils.parse(cached, StationVo.class);
        }
        idempotentService.markProcessing(apiKey, clientRequestId);
        try {
            String name = dto.getName().trim();
            assertNameUnique(name, null);

            LocalDateTime now = LocalDateTime.now();
            Station station = new Station();
            station.setName(name);
            station.setAddress(dto.getAddress().trim());
            station.setOpenTime(trimToNull(dto.getOpenTime()));
            station.setWarnThreshold(dto.getWarnThreshold() == null ? 0 : dto.getWarnThreshold());
            station.setStatus(Constants.STATUS_ENABLED);
            station.setCreatedAt(now);
            station.setUpdatedAt(now);
            stationMapper.insert(station);

            StationVo vo = toVo(station);
            idempotentService.markCompleted(apiKey, clientRequestId, JsonUtils.toJson(vo));
            log.info("新增站点完成：id={}, name={}, operatorId={}", vo.getId(), vo.getName(), AuthContext.userId());
            return vo;
        } catch (RuntimeException e) {
            // 任何失败立即删占位，保证客户端重试不被自己的占位挡死（契约第 4 节）
            idempotentService.clear(apiKey, clientRequestId);
            throw e;
        }
    }

    /** 编辑站点（PUT /stations/{id}，平台管理员；见 5.3）。 */
    @Transactional(rollbackFor = Exception.class)
    public StationVo update(Long id, UpdateStationDto dto, String clientRequestId) {
        String apiKey = "PUT /stations/{id}";
        String cached = idempotentService.findCompleted(apiKey, clientRequestId);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            return JsonUtils.parse(cached, StationVo.class);
        }
        idempotentService.markProcessing(apiKey, clientRequestId);
        try {
            Station station = requireStation(id);
            String name = dto.getName().trim();
            assertNameUnique(name, id);
            station.setName(name);
            station.setAddress(dto.getAddress().trim());
            if (dto.getOpenTime() != null) {
                station.setOpenTime(trimToNull(dto.getOpenTime()));
            }
            if (dto.getWarnThreshold() != null) {
                station.setWarnThreshold(dto.getWarnThreshold());
            }
            station.setUpdatedAt(LocalDateTime.now());
            stationMapper.updateById(station);

            StationVo vo = toVo(station);
            idempotentService.markCompleted(apiKey, clientRequestId, JsonUtils.toJson(vo));
            log.info("编辑站点完成：id={}, operatorId={}", id, AuthContext.userId());
            return vo;
        } catch (RuntimeException e) {
            idempotentService.clear(apiKey, clientRequestId);
            throw e;
        }
    }

    /**
     * 停用/启用站点（PATCH /stations/{id}/status，平台管理员；见 5.3 与表后说明）。
     *
     * <p>停用前置（P0 修订口径）：可用库存为 0 且（非终态实物为 0 或已指定合法接收站）。
     * 非终态实物 = 状态 ≠ 报废的袋（含未激活、待消毒、可复用、维修中、遗失等）；
     * 满足时把接收站写入 {@code handover_station_id} 并与后续移交对齐。
     * 启用不设前置，直接改状态。前置不满足一律抛 4105 并给出具体原因。
     */
    @Transactional(rollbackFor = Exception.class)
    public StationVo updateStatus(Long id, StationStatusDto dto, String clientRequestId) {
        String apiKey = "PATCH /stations/{id}/status";
        String cached = idempotentService.findCompleted(apiKey, clientRequestId);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            return JsonUtils.parse(cached, StationVo.class);
        }
        idempotentService.markProcessing(apiKey, clientRequestId);
        try {
            Station station = requireStation(id);
            String target = dto.getStatus().trim().toUpperCase(Locale.ROOT);
            Long handover = dto.getHandoverStationId();
            if (Constants.STATUS_DISABLED.equals(target)) {
                assertDisablePrecondition(station, handover);
                station.setStatus(Constants.STATUS_DISABLED);
                if (handover != null) {
                    station.setHandoverStationId(handover);
                }
            } else {
                station.setStatus(Constants.STATUS_ENABLED);
            }
            station.setUpdatedAt(LocalDateTime.now());
            stationMapper.updateById(station);

            StationVo vo = toVo(station);
            idempotentService.markCompleted(apiKey, clientRequestId, JsonUtils.toJson(vo));
            log.info("站点状态变更完成：id={}, status={}, handoverStationId={}, operatorId={}",
                    id, vo.getStatus(), vo.getHandoverStationId(), AuthContext.userId());
            return vo;
        } catch (RuntimeException e) {
            idempotentService.clear(apiKey, clientRequestId);
            throw e;
        }
    }

    // ==================== 资源存在性与数据权限（同模块复用） ====================

    /**
     * 站点存在性校验（先资源存在性、后归属，见契约 6）：不存在抛 3001。
     * 回收箱清单/回收确认等接口复用本方法，避免各写一份查询。
     */
    public Station requireStation(Long id) {
        Station station = id == null ? null : stationMapper.selectById(id);
        if (station == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "站点不存在：id=" + id);
        }
        return station;
    }

    /**
     * 「本站」数据权限校验（设计书 4.4 / 契约 6）：平台管理员全站放行；
     * 站点管理员仅可操作本人绑定站点，不符抛 2002。水平越权由本层强制兜底（拦截器管不到）。
     */
    public void assertStationAccess(Long stationId) {
        if (AuthContext.isPlatformAdmin()) {
            return;
        }
        Long mine = AuthContext.stationId();
        if (mine == null || !mine.equals(stationId)) {
            throw new BizException(ErrorCode.NO_DATA_PERMISSION, "仅可操作本站数据");
        }
    }

    // ==================== 私有实现 ====================

    /** 停用前置校验（5.3 表后说明）：不满足抛 4105 并写明具体原因。 */
    private void assertDisablePrecondition(Station station, Long handoverStationId) {
        Long id = station.getId();
        // ① 可用库存为 0（Redis 优先、DB 兜底；DB 口径即 3.1 的权威口径 COUNT(IDLE)）
        int available = stockQueryService.getAvailableStock(id);
        if (available > 0) {
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                    "站点可用库存为 " + available + " 只，须清零后方可停用");
        }
        // ② 指定接收站时先校验其合法：存在且非本站自身
        if (handoverStationId != null) {
            if (handoverStationId.equals(id)) {
                throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "接收站不能为本站自身");
            }
            if (stationMapper.selectById(handoverStationId) == null) {
                throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "指定的接收站不存在：id=" + handoverStationId);
            }
        }
        // ③ 非终态实物要么清零，要么已指定接收站
        long nonTerminal = stockQueryService.countNonTerminal(id);
        if (nonTerminal > 0 && handoverStationId == null) {
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                    "站点仍有 " + nonTerminal + " 只非终态实物，须在请求中经 handoverStationId 指定接收站后方可停用");
        }
    }

    /** 站点名称全平台唯一（uk_station_name）；编辑时排除自身。 */
    private void assertNameUnique(String name, Long excludeId) {
        LambdaQueryWrapper<Station> wrapper = new LambdaQueryWrapper<Station>().eq(Station::getName, name);
        if (excludeId != null) {
            wrapper.ne(Station::getId, excludeId);
        }
        Long count = stationMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "站点名称已存在：" + name);
        }
    }

    /**
     * 排序白名单（设计书 6.1：排序字段必须走白名单）：只接受具名字段并映射为类型安全的列引用，
     * 不把任何用户输入拼进 SQL（9.4）。
     */
    private static void applyOrder(LambdaQueryWrapper<Station> wrapper, String orderBy) {
        if (orderBy == null || orderBy.isBlank()) {
            wrapper.orderByAsc(Station::getId);
            return;
        }
        String[] parts = orderBy.split(",");
        String field = parts[0].trim();
        boolean asc = parts.length < 2 || !"desc".equalsIgnoreCase(parts[1].trim());
        switch (field) {
            case "id" -> wrapper.orderBy(true, asc, Station::getId);
            case "name" -> wrapper.orderBy(true, asc, Station::getName);
            case "status" -> wrapper.orderBy(true, asc, Station::getStatus);
            case "warnThreshold" -> wrapper.orderBy(true, asc, Station::getWarnThreshold);
            case "createdAt" -> wrapper.orderBy(true, asc, Station::getCreatedAt);
            case "updatedAt" -> wrapper.orderBy(true, asc, Station::getUpdatedAt);
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "不支持的排序字段：" + field);
        }
    }

    /** 解析查询时间：支持 yyyy-MM-dd HH:mm:ss、yyyy-MM-dd HH:mm、yyyy-MM-dd 与 ISO-8601。 */
    private static LocalDateTime parseTime(String text, boolean endOfDay) {
        String value = trimToNull(text);
        if (value == null) {
            return null;
        }
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value, DATE_FORMAT).atTime(endOfDay ? LocalTime.MAX : LocalTime.MIN);
            }
            if (value.length() == 16) {
                return LocalDateTime.parse(value, DATE_TIME_MINUTE_FORMAT);
            }
            if (value.length() == 19 && value.charAt(10) == ' ') {
                return LocalDateTime.parse(value, DATE_TIME_FORMAT);
            }
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "时间格式不正确：" + value + "（支持 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss）");
        }
    }

    /** 站点实体 → 出参 VO。 */
    private static StationVo toVo(Station station) {
        StationVo vo = new StationVo();
        vo.setId(station.getId());
        vo.setName(station.getName());
        vo.setAddress(station.getAddress());
        vo.setOpenTime(station.getOpenTime());
        vo.setWarnThreshold(station.getWarnThreshold());
        vo.setStatus(station.getStatus());
        vo.setStatusName(Constants.STATUS_DISABLED.equals(station.getStatus()) ? "停用" : "启用");
        vo.setHandoverStationId(station.getHandoverStationId());
        vo.setCreatedAt(station.getCreatedAt());
        vo.setUpdatedAt(station.getUpdatedAt());
        return vo;
    }

    /** 组装库存视图出参（statusCounts 可为空：预警列表不回传状态明细）。 */
    private static StationStockVo toStockVo(Station station, Map<String, Long> statusCounts, int available) {
        StationStockVo vo = new StationStockVo();
        vo.setStationId(station.getId());
        vo.setStationName(station.getName());
        vo.setAddress(station.getAddress());
        vo.setStatusCounts(statusCounts);
        vo.setAvailableStock(available);
        vo.setWarnThreshold(station.getWarnThreshold());
        vo.setWarning(isWarning(available, station.getWarnThreshold()));
        return vo;
    }

    /** 预警判定：阈值 > 0 且可用库存低于阈值（阈值 0 = 不预警，见 5.3/5.6）。 */
    private static boolean isWarning(int available, Integer warnThreshold) {
        return warnThreshold != null && warnThreshold > 0 && available < warnThreshold;
    }

    /** 去空白；空白归一为 null。 */
    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
