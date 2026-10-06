package com.sems.module.bag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.common.PageResult;
import com.sems.common.util.BizNoUtils;
import com.sems.common.util.JsonUtils;
import com.sems.infra.IdempotentService;
import com.sems.module.bag.dto.CreateInventoryCheckDto;
import com.sems.module.bag.dto.InventoryCheckQuery;
import com.sems.module.bag.dto.InventoryCheckVo;
import com.sems.module.bag.dto.VoidInventoryCheckDto;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.station.entity.InventoryCheck;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.InventoryCheckMapper;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.station.service.StockQueryService;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 库存盘点服务（快递袋管理模块 M4；见《系统设计说明书》5.4、3.4.4「库存盘点流程」、
 * 3.2 #21 与需求 FR-M4-17）。
 *
 * <p><b>提交盘点单</b>：系统按 DB 实时统计本站各状态账面数（DB 为权威口径），与录入的实盘数
 * 逐状态比对自动计算差异，并生成袋级差异清单（差异无法从计数直接定位到实物，按账面袋清单取
 * 「盘亏候选 / 盘盈候选」，供平台管理员现场核对后按 #21 状态修正并留痕，见 3.4.4；
 * 修正经 {@code bag_transfer_log.ref_check_no} 与本单号关联，形成「盘点→修正→追溯」闭环）。
 *
 * <p><b>盘点单不可删除</b>：状态只在 PENDING_FIX 待修正 / CLOSED 已闭环 / VOID 作废 间流转；
 * 仅「待修正」可作废（否则 4105），作废需填原因（重盘说明）并记录作废人/作废时间，作废后可重盘。
 *
 * <p>本类所有写方法单事务完成（提交/作废均无「部分成功」语义），事务注解按契约模块版 11 落实。
 */
@Service
public class InventoryCheckService {

    private static final Logger log = LoggerFactory.getLogger(InventoryCheckService.class);

    /** 幂等键：HTTP 方法 + 路径模板（契约模块版 4）。 */
    private static final String API_KEY_SUBMIT = "POST /inventory-checks";
    private static final String API_KEY_VOID = "POST /inventory-checks/{id}/void";

    /** 盘点单状态码（与 inventory_check.status 的 DDL 码值一致）。 */
    private static final String STATUS_PENDING_FIX = "PENDING_FIX";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_VOID = "VOID";

    /** 差异类型（袋级差异清单元素字段）。 */
    private static final String DIFF_TYPE_LOSS = "盘亏";
    private static final String DIFF_TYPE_GAIN = "盘盈";

    /** 分页上限（契约 10：pageSize 最大 100）。 */
    private static final int PAGE_SIZE_MAX = 100;

    private final InventoryCheckMapper inventoryCheckMapper;
    private final BagMapper bagMapper;
    private final StationMapper stationMapper;
    private final StockQueryService stockQueryService;
    private final IdempotentService idempotentService;

    public InventoryCheckService(InventoryCheckMapper inventoryCheckMapper,
                                 BagMapper bagMapper,
                                 StationMapper stationMapper,
                                 StockQueryService stockQueryService,
                                 IdempotentService idempotentService) {
        this.inventoryCheckMapper = inventoryCheckMapper;
        this.bagMapper = bagMapper;
        this.stationMapper = stationMapper;
        this.stockQueryService = stockQueryService;
        this.idempotentService = idempotentService;
    }

    /** 提交盘点单（见类注释；idempotency 范式见契约模块版 4）。 */
    @Transactional(rollbackFor = Exception.class)
    public InventoryCheckVo submit(CreateInventoryCheckDto dto, String clientRequestId) {
        requireClientRequestId(clientRequestId);
        String cached = idempotentService.findCompleted(API_KEY_SUBMIT, clientRequestId);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            return JsonUtils.parse(cached, InventoryCheckVo.class);
        }
        idempotentService.markProcessing(API_KEY_SUBMIT, clientRequestId);
        try {
            InventoryCheckVo vo = doSubmit(dto);
            idempotentService.markCompleted(API_KEY_SUBMIT, clientRequestId, JsonUtils.toJson(vo));
            return vo;
        } catch (RuntimeException e) {
            idempotentService.clear(API_KEY_SUBMIT, clientRequestId);
            throw e;
        }
    }

    /** 盘点单分页查询（含袋级差异清单；本站站点管理员限本站，见 5.4 与 4.4）。 */
    public PageResult<InventoryCheckVo> page(InventoryCheckQuery query) {
        LoginUser operator = AuthContext.require();
        Long stationId = resolvePageStation(query.getStationId(), operator);
        String statusFilter = trimToNull(query.getStatus());
        if (statusFilter != null && !STATUS_PENDING_FIX.equals(statusFilter)
                && !STATUS_CLOSED.equals(statusFilter) && !STATUS_VOID.equals(statusFilter)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "盘点单状态筛选取值非法：" + statusFilter);
        }

        LambdaQueryWrapper<InventoryCheck> wrapper = Wrappers.lambdaQuery(InventoryCheck.class)
                .eq(stationId != null, InventoryCheck::getStationId, stationId)
                .eq(statusFilter != null, InventoryCheck::getStatus, statusFilter)
                // 时间筛选口径：提交时间（created_at），与 idx_inventory_check_station_created 索引对齐
                .ge(query.getBeginTime() != null, InventoryCheck::getCreatedAt, query.getBeginTime())
                .le(query.getEndTime() != null, InventoryCheck::getCreatedAt, query.getEndTime());
        applyOrder(wrapper, query.getOrderBy());

        Page<InventoryCheck> page = inventoryCheckMapper.selectPage(
                new Page<>(normalizePageNum(query.getPageNum()), normalizePageSize(query.getPageSize())), wrapper);
        List<InventoryCheckVo> records = new ArrayList<>(page.getRecords().size());
        for (InventoryCheck check : page.getRecords()) {
            records.add(toVo(check));
        }
        return PageResult.of(records, page.getTotal(), page.getSize());
    }

    /** 作废盘点单（仅「待修正」可作废，否则 4105；盘点单不可删除，作废后可重盘，见 5.4）。 */
    @Transactional(rollbackFor = Exception.class)
    public InventoryCheckVo voidCheck(Long id, VoidInventoryCheckDto dto, String clientRequestId) {
        requireClientRequestId(clientRequestId);
        String cached = idempotentService.findCompleted(API_KEY_VOID, clientRequestId);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            return JsonUtils.parse(cached, InventoryCheckVo.class);
        }
        idempotentService.markProcessing(API_KEY_VOID, clientRequestId);
        try {
            InventoryCheckVo vo = doVoid(id, dto);
            idempotentService.markCompleted(API_KEY_VOID, clientRequestId, JsonUtils.toJson(vo));
            return vo;
        } catch (RuntimeException e) {
            idempotentService.clear(API_KEY_VOID, clientRequestId);
            throw e;
        }
    }

    /* ------------------------------------------------------------------ 提交盘点单 */

    private InventoryCheckVo doSubmit(CreateInventoryCheckDto dto) {
        Map<String, Integer> actual = normalizeActual(dto.getActualJson());
        LoginUser operator = AuthContext.require();
        Long stationId = resolveOperationStation(dto.getStationId(), operator);

        // 账面数实时统计（3.4.4 处理要点：DB 为权威口径；前端提交的 expectJson 不采信）
        Map<String, Integer> expect = countByStatus(stationId);
        List<InventoryCheckVo.DiffBag> diffBags = buildDiffBags(stationId, expect, actual);

        LocalDateTime now = LocalDateTime.now();
        InventoryCheck check = new InventoryCheck();
        check.setCheckNo(BizNoUtils.checkNo());
        check.setStationId(stationId);
        check.setCheckTime(now);
        check.setExpectJson(JsonUtils.toJson(expect));
        check.setActualJson(JsonUtils.toJson(actual));
        check.setDiffBagsJson(JsonUtils.toJson(diffBags));
        check.setDiffCount(diffBags.size());
        // 有差异待平台管理员按 #21 修正；无差异也保持待修正（状态扭转入口本期只提供作废，见 5.4）
        check.setStatus(STATUS_PENDING_FIX);
        check.setOperatorId(operator.getUserId());
        check.setRemark(trimToNull(dto.getRemark()));
        check.setCreatedAt(now);
        insertWithRetry(check);

        log.info("盘点单提交完成：checkNo={}，站点={}，差异袋数={}", check.getCheckNo(), stationId, check.getDiffCount());
        return toVo(check);
    }

    /** 实盘数归一：键必须是 11 态枚举码，值必须非负；缺省状态补 0，保证与账面快照键集合一致。 */
    private Map<String, Integer> normalizeActual(Map<String, Integer> actualJson) {
        if (actualJson == null || actualJson.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "实盘各状态袋数不能为空");
        }
        Map<String, Integer> normalized = new LinkedHashMap<>();
        for (BagStatus status : BagStatus.values()) {
            normalized.put(status.code(), 0);
        }
        for (Map.Entry<String, Integer> entry : actualJson.entrySet()) {
            BagStatus status = parseStatus(entry.getKey());
            if (status == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "未知的袋状态码：" + entry.getKey());
            }
            Integer value = entry.getValue();
            if (value == null || value < 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "实盘数必须为非负整数：" + status.code());
            }
            normalized.put(status.code(), value);
        }
        return normalized;
    }

    /**
     * 账面各状态袋数统计：复用站点模块 {@link StockQueryService#countByStatus(Long)}
     * （跨模块只调对方 Service，不重复实现聚合逻辑；该方法返回全 11 态、无记录补 0，
     * 与实盘快照的键集合天然一致），此处仅把计数类型收敛为 Integer 供快照与比对使用。
     */
    private Map<String, Integer> countByStatus(Long stationId) {
        Map<String, Long> counts = stockQueryService.countByStatus(stationId);
        Map<String, Integer> result = new LinkedHashMap<>();
        counts.forEach((status, count) -> result.put(status, count == null ? 0 : count.intValue()));
        return result;
    }

    /**
     * 生成袋级差异清单：逐状态比对实盘−账面，差异无法从计数定位到具体实物，
     * 按账面袋清单给出候选（袋码升序，去重），供平台管理员现场核对后走 #21 状态修正留痕：
     * <ul>
     *   <li>盘亏（实盘 &lt; 账面）：从该状态在册袋中取候选；</li>
     *   <li>盘盈（实盘 &gt; 账面）：多出的实物袋在账上状态不一致，从本站其他状态（排除报废）取候选。</li>
     * </ul>
     */
    private List<InventoryCheckVo.DiffBag> buildDiffBags(Long stationId, Map<String, Integer> expect,
                                                         Map<String, Integer> actual) {
        List<InventoryCheckVo.DiffBag> diffs = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (BagStatus status : BagStatus.values()) {
            int book = expect.getOrDefault(status.code(), 0);
            int real = actual.getOrDefault(status.code(), 0);
            int delta = real - book;
            if (delta < 0) {
                appendCandidates(diffs, used, candidateBagsOfStatus(stationId, status, -delta), DIFF_TYPE_LOSS);
            } else if (delta > 0) {
                appendCandidates(diffs, used, candidateBagsOfOtherStatus(stationId, status, delta), DIFF_TYPE_GAIN);
            }
        }
        return diffs;
    }

    /** 盘亏候选：本站该状态的在册袋（袋码升序），取前 limit 只。 */
    private List<Bag> candidateBagsOfStatus(Long stationId, BagStatus status, int limit) {
        Page<Bag> page = new Page<>(1, limit, false);
        return bagMapper.selectPage(page, Wrappers.lambdaQuery(Bag.class)
                .eq(Bag::getStationId, stationId)
                .eq(Bag::getStatus, status.code())
                .orderByAsc(Bag::getBagCode)).getRecords();
    }

    /** 盘盈候选：本站其他状态（报废除外）的在册袋（袋码升序），取前 limit 只。 */
    private List<Bag> candidateBagsOfOtherStatus(Long stationId, BagStatus status, int limit) {
        Page<Bag> page = new Page<>(1, limit, false);
        return bagMapper.selectPage(page, Wrappers.lambdaQuery(Bag.class)
                .eq(Bag::getStationId, stationId)
                .ne(Bag::getStatus, status.code())
                .ne(Bag::getStatus, BagStatus.SCRAPPED.code())
                .orderByAsc(Bag::getBagCode)).getRecords();
    }

    private static void appendCandidates(List<InventoryCheckVo.DiffBag> diffs, Set<String> used,
                                         List<Bag> bags, String diffType) {
        for (Bag bag : bags) {
            if (used.add(bag.getBagCode())) {
                InventoryCheckVo.DiffBag item = new InventoryCheckVo.DiffBag();
                item.setBagCode(bag.getBagCode());
                item.setDiffType(diffType);
                diffs.add(item);
            }
        }
    }

    /* ------------------------------------------------------------------ 作废盘点单 */

    private InventoryCheckVo doVoid(Long id, VoidInventoryCheckDto dto) {
        if (id == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "盘点单 ID 不能为空");
        }
        String voidReason = dto == null ? null : trimToNull(dto.getVoidReason());
        if (voidReason == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "作废原因不能为空");
        }
        // 校验顺序：先资源存在性（4106），后数据归属（2002），再单据状态（4105）
        InventoryCheck check = inventoryCheckMapper.selectById(id);
        if (check == null) {
            throw new BizException(ErrorCode.BAG_NOT_FOUND, "盘点单不存在");
        }
        LoginUser operator = AuthContext.require();
        if (!AuthContext.isPlatformAdmin() && !Objects.equals(check.getStationId(), operator.getStationId())) {
            throw new BizException(ErrorCode.NO_DATA_PERMISSION, "只能作废本站的盘点单");
        }
        if (!STATUS_PENDING_FIX.equals(check.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "仅「待修正」状态的盘点单可作废");
        }

        LocalDateTime now = LocalDateTime.now();
        // 部分更新：只改状态与作废留痕列（MyBatis-Plus 默认忽略 null 字段），单据其余内容不可篡改
        InventoryCheck update = new InventoryCheck();
        update.setId(check.getId());
        update.setStatus(STATUS_VOID);
        update.setVoidedAt(now);
        update.setVoidedBy(operator.getUserId());
        update.setVoidReason(voidReason);
        inventoryCheckMapper.updateById(update);

        check.setStatus(STATUS_VOID);
        check.setVoidedAt(now);
        check.setVoidedBy(operator.getUserId());
        check.setVoidReason(voidReason);
        log.info("盘点单已作废：checkNo={}，操作人={}", check.getCheckNo(), operator.getUserId());
        return toVo(check);
    }

    /* ------------------------------------------------------------------ 校验与权限 */

    /** 操作站点判定（4.4）：站点管理员默认本站、传他站报 2002；平台管理员须显式指定站点。 */
    private Long resolveOperationStation(Long requested, LoginUser operator) {
        Long stationId = requested != null ? requested : operator.getStationId();
        if (stationId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请指定盘点站点（平台管理员操作须显式指定）");
        }
        if (!AuthContext.isPlatformAdmin() && !stationId.equals(operator.getStationId())) {
            throw new BizException(ErrorCode.NO_DATA_PERMISSION, "只能提交本站的盘点单");
        }
        Station station = stationMapper.selectById(stationId);
        if (station == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "站点不存在：" + stationId);
        }
        return stationId;
    }

    /** 查询场景的站点范围：站点管理员强制本站（显式他站报 2002），平台管理员不传即全站。 */
    private Long resolvePageStation(Long requested, LoginUser operator) {
        if (AuthContext.isPlatformAdmin()) {
            return requested;
        }
        if (requested != null && !requested.equals(operator.getStationId())) {
            throw new BizException(ErrorCode.NO_DATA_PERMISSION, "只能查询本站的盘点单");
        }
        if (operator.getStationId() == null) {
            throw new BizException(ErrorCode.NO_DATA_PERMISSION, "当前账号未绑定站点");
        }
        return operator.getStationId();
    }

    private void requireClientRequestId(String clientRequestId) {
        if (clientRequestId == null || !clientRequestId.matches(Constants.UUID_REGEX)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少或非法的 clientRequestId 请求头");
        }
    }

    /* ------------------------------------------------------------------ 转换与工具 */

    private InventoryCheckVo toVo(InventoryCheck check) {
        InventoryCheckVo vo = new InventoryCheckVo();
        vo.setId(check.getId());
        vo.setCheckNo(check.getCheckNo());
        vo.setStationId(check.getStationId());
        vo.setCheckTime(check.getCheckTime());
        vo.setExpectJson(parseStatusCounts(check.getExpectJson()));
        vo.setActualJson(parseStatusCounts(check.getActualJson()));
        vo.setDiffBags(parseDiffBags(check.getDiffBagsJson()));
        vo.setDiffCount(check.getDiffCount());
        vo.setStatus(check.getStatus());
        vo.setOperatorId(check.getOperatorId());
        vo.setClosedAt(check.getClosedAt());
        vo.setVoidedAt(check.getVoidedAt());
        vo.setVoidedBy(check.getVoidedBy());
        vo.setVoidReason(check.getVoidReason());
        vo.setRemark(check.getRemark());
        vo.setCreatedAt(check.getCreatedAt());
        return vo;
    }

    /** 解析状态→袋数 JSON（库列 json 经 JDBC 读写为字符串，见契约 3.7）；解析失败按空映射降级。 */
    private static Map<String, Integer> parseStatusCounts(String json) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return counts;
        }
        Map<?, ?> raw = JsonUtils.parse(json, Map.class);
        if (raw == null) {
            return counts;
        }
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            Object value = entry.getValue();
            counts.put(String.valueOf(entry.getKey()), value instanceof Number number ? number.intValue() : 0);
        }
        return counts;
    }

    private static List<InventoryCheckVo.DiffBag> parseDiffBags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<InventoryCheckVo.DiffBag> list = JsonUtils.parseList(json, InventoryCheckVo.DiffBag.class);
        return list == null ? List.of() : list;
    }

    private void insertWithRetry(InventoryCheck check) {
        try {
            inventoryCheckMapper.insert(check);
        } catch (DuplicateKeyException e) {
            // 单号 = 时间戳 + 6 位安全随机段，碰撞概率极低；唯一键冲突时换号重试一次（BizNoUtils 约定）
            log.warn("盘点单号碰撞，换号重试：{}", check.getCheckNo());
            check.setCheckNo(BizNoUtils.checkNo());
            inventoryCheckMapper.insert(check);
        }
    }

    /** 排序字段白名单（契约 11）：仅 checkTime / createdAt，缺省按提交时间倒序。 */
    private static void applyOrder(LambdaQueryWrapper<InventoryCheck> wrapper, String orderBy) {
        if (orderBy == null || orderBy.isBlank() || "createdAt".equals(orderBy)) {
            wrapper.orderByDesc(InventoryCheck::getCreatedAt).orderByDesc(InventoryCheck::getId);
            return;
        }
        if ("checkTime".equals(orderBy)) {
            wrapper.orderByDesc(InventoryCheck::getCheckTime).orderByDesc(InventoryCheck::getId);
            return;
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "不支持的排序字段：" + orderBy);
    }

    private static BagStatus parseStatus(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toUpperCase();
        for (BagStatus status : BagStatus.values()) {
            if (status.code().equals(normalized)) {
                return status;
            }
        }
        return null;
    }

    private static int normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, PAGE_SIZE_MAX);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
