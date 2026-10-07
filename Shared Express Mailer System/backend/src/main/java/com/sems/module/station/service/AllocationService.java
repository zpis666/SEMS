package com.sems.module.station.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.common.PageResult;
import com.sems.common.util.BizNoUtils;
import com.sems.common.util.JsonUtils;
import com.sems.common.util.MaskUtils;
import com.sems.config.SemsProperties;
import com.sems.infra.IdempotentService;
import com.sems.module.audit.entity.AuditLogEntity;
import com.sems.module.audit.service.AuditLogService;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.entity.LiabilityRecord;
import com.sems.module.order.mapper.LiabilityRecordMapper;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.statemachine.BagTransitionResult;
import com.sems.module.statemachine.BagTransitionService;
import com.sems.module.statemachine.TransitionCommand;
import com.sems.module.station.dto.AllocationCancelDto;
import com.sems.module.station.dto.AllocationDetailVo;
import com.sems.module.station.dto.AllocationDispatchDto;
import com.sems.module.station.dto.AllocationLostDto;
import com.sems.module.station.dto.AllocationQuery;
import com.sems.module.station.dto.AllocationReceiveDto;
import com.sems.module.station.dto.AllocationRemoveDto;
import com.sems.module.station.dto.AllocationVo;
import com.sems.module.station.dto.CreateAllocationDto;
import com.sems.module.station.entity.AllocationDetail;
import com.sems.module.station.entity.AllocationOrder;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.AllocationDetailMapper;
import com.sems.module.station.mapper.AllocationOrderMapper;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.station.service.action.AllocationTransitionActions;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;

/**
 * 调拨服务（M3 共享站点管理；见《系统设计说明书》5.3 调拨端点表、3.4.3 调拨流程、
 * 3.2 #16/#17/#19 状态转移表与 4.4 数据权限）。
 *
 * <p>覆盖端点：列表 / 详情（{@code GET /allocations}、{@code GET /allocations/{id}}）、
 * 建单（{@code POST /allocations}）、取消 / 终止（{@code DELETE /allocations/{id}}）、
 * 出库（{@code POST /allocations/{id}/dispatch}）、核收（{@code POST /allocations/{id}/receive}）、
 * 在途遗失（{@code POST /allocations/{id}/lost-confirm}）、剔除明细
 * （{@code POST /allocations/{id}/remove-detail}）。
 *
 * <p><b>单据状态机</b>（3.4.3）：待出库 PENDING_DISPATCH --首只出库--> 在途 IN_TRANSIT
 * --全部（未剔除）明细核收--> 已完成 COMPLETED；异常分支 ABNORMAL（在途遗失）、
 * 待出库可整单取消 CANCELLED、异常可终止 TERMINATED（未出库明细解锁回空闲）。
 * 单据状态不允许的操作一律 4105（袋状态机拒绝才归 4102，边界见 6.4）。
 *
 * <p><b>事务边界（批量端点为什么不加 @Transactional）</b>：出库/核收/遗失确认按批量范式
 * 逐袋处理、单只失败不影响其余；而状态机 {@code BagTransitionService.fire()} 自带事务，
 * 失败时会把它所参与的「外层事务」标记为 rollback-only——若外层再包一层事务，
 * 任何一只袋的业务失败都会让整批在提交时回滚。因此这三个方法的循环体不包事务：
 * 每只袋的 {@code fire()} 各自一个事务，随后的明细/单据推进是幂等的状态条件 UPDATE
 * （重复执行无副作用）。建单 / 取消终止 / 剔除明细不调用状态机，按契约加
 * {@code @Transactional(rollbackFor = Exception.class)}。
 *
 * <p><b>幂等</b>（契约模块版第 4 节）：接口级幂等（{@code findCompleted/markProcessing/markCompleted/clear}）；
 * 逐袋幂等交给状态机内部（把 clientRequestId 传进 {@code TransitionCommand}，重复出库/核收直接回放，
 * 不重复扣增库存）——本类不得再自行调用 {@code tryAcquireBag(crid, bagCode)} 造成一袋两次消费同一幂等键。
 *
 * <p><b>4110 唯一判定入口</b>：{@link #isBagLocked(String)}，其它模块需要「袋是否被未出库调拨单引用」
 * 时一律调用本方法，不要各自实现。
 */
@Service
public class AllocationService {

    private static final Logger log = LoggerFactory.getLogger(AllocationService.class);

    /** 审计模块名（与设计书 2.5 模块划分一致）。 */
    private static final String AUDIT_MODULE = "共享站点管理";

    // ===== 单据状态（allocation_order.status，码值与建表脚本列注释逐字一致） =====

    /** 待出库（建单即为本状态） */
    private static final String ORDER_PENDING_DISPATCH = "PENDING_DISPATCH";
    /** 在途（首只出库后进入） */
    private static final String ORDER_IN_TRANSIT = "IN_TRANSIT";
    /** 已完成（全部未剔除明细核收后进入） */
    private static final String ORDER_COMPLETED = "COMPLETED";
    /** 异常（在途遗失确认后进入） */
    private static final String ORDER_ABNORMAL = "ABNORMAL";
    /** 已取消（仅待出库可取消） */
    private static final String ORDER_CANCELLED = "CANCELLED";
    /** 已终止（异常单据由平台管理员终止） */
    private static final String ORDER_TERMINATED = "TERMINATED";

    // ===== 明细状态（allocation_detail.status） =====

    /** 待出库（建单即锁定该袋） */
    private static final String DETAIL_PENDING_DISPATCH = "PENDING_DISPATCH";
    /** 已出库（在途） */
    private static final String DETAIL_DISPATCHED = "DISPATCHED";
    /** 已核收（归入目标站） */
    private static final String DETAIL_RECEIVED = "RECEIVED";
    /** 已剔除（解锁回空闲） */
    private static final String DETAIL_REMOVED = "REMOVED";
    /** 已遗失（在途遗失确认） */
    private static final String DETAIL_LOST = "LOST";

    /** 单据状态中文名（与建表脚本注释、设计书 5.3 一致）。 */
    private static final Map<String, String> ORDER_STATUS_NAMES = Map.of(
            ORDER_PENDING_DISPATCH, "待出库",
            ORDER_IN_TRANSIT, "在途",
            ORDER_COMPLETED, "已完成",
            ORDER_ABNORMAL, "异常",
            ORDER_CANCELLED, "已取消",
            ORDER_TERMINATED, "已终止");

    /** 明细状态中文名（与建表脚本注释一致）。 */
    private static final Map<String, String> DETAIL_STATUS_NAMES = Map.of(
            DETAIL_PENDING_DISPATCH, "待出库",
            DETAIL_DISPATCHED, "已出库",
            DETAIL_RECEIVED, "已核收",
            DETAIL_REMOVED, "已剔除",
            DETAIL_LOST, "已遗失");

    /** 取消 / 终止原因缺省文案（cancel_reason 列非空更利于事后追溯）。 */
    private static final String DEFAULT_CANCEL_REASON = "平台管理员取消调拨单";
    private static final String DEFAULT_TERMINATE_REASON = "调拨单异常终止";
    /** 在途遗失责任记录的缺省处置结论（3.2 责任认定口径：调拨在途遗失责任主体为源站）。 */
    private static final String DEFAULT_LOST_CONCLUSION = "在途遗失，责任归属源站";

    // ===== 幂等 apiKey：HTTP 方法 + 路径模板（契约模块版第 4 节口径，全站唯一） =====

    private static final String API_CREATE = "POST /allocations";
    private static final String API_CANCEL = "DELETE /allocations/{id}";
    private static final String API_DISPATCH = "POST /allocations/{id}/dispatch";
    private static final String API_RECEIVE = "POST /allocations/{id}/receive";
    private static final String API_LOST_CONFIRM = "POST /allocations/{id}/lost-confirm";
    private static final String API_REMOVE_DETAIL = "POST /allocations/{id}/remove-detail";

    /** 分页口径（6.1：默认 10、最大 100）。 */
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final AllocationOrderMapper allocationOrderMapper;

    private final AllocationDetailMapper allocationDetailMapper;

    private final StationMapper stationMapper;

    private final BagMapper bagMapper;

    private final LiabilityRecordMapper liabilityRecordMapper;

    private final BagTransitionService bagTransitionService;

    private final IdempotentService idempotentService;

    private final AuditLogService auditLogService;

    private final SemsProperties properties;

    public AllocationService(AllocationOrderMapper allocationOrderMapper,
                             AllocationDetailMapper allocationDetailMapper,
                             StationMapper stationMapper,
                             BagMapper bagMapper,
                             LiabilityRecordMapper liabilityRecordMapper,
                             BagTransitionService bagTransitionService,
                             IdempotentService idempotentService,
                             AuditLogService auditLogService,
                             SemsProperties properties) {
        this.allocationOrderMapper = allocationOrderMapper;
        this.allocationDetailMapper = allocationDetailMapper;
        this.stationMapper = stationMapper;
        this.bagMapper = bagMapper;
        this.liabilityRecordMapper = liabilityRecordMapper;
        this.bagTransitionService = bagTransitionService;
        this.idempotentService = idempotentService;
        this.auditLogService = auditLogService;
        this.properties = properties;
    }

    /**
     * 批量接口逐袋结果（字段名与契约模块版第 5 节固定口径逐字一致：{@code bagCode/success/code/message}，
     * 前端批量结果弹窗按此渲染，不得改名）。
     */
    public record BagResult(String bagCode, boolean success, int code, String message) {

        /** 逐袋成功结果。 */
        public static BagResult ok(String bagCode) {
            return new BagResult(bagCode, true, ErrorCode.SUCCESS.getCode(), "成功");
        }

        /** 逐袋失败结果（错误码沿用全局错误码表）。 */
        public static BagResult fail(String bagCode, ErrorCode errorCode, String message) {
            return new BagResult(bagCode, false, errorCode.getCode(), message);
        }
    }

    // ==================== 查询 ====================

    /**
     * 调拨单分页查询（5.3 GET /allocations）。
     *
     * <p>数据权限（4.4 第⑤条）：站点管理员强制过滤「源站=本站 OR 目标站=本站」，平台管理员不过滤；
     * 过滤条件在 Service 层追加，请求参数无法绕过。
     */
    public PageResult<AllocationVo> page(AllocationQuery query) {
        AllocationQuery q = query == null ? new AllocationQuery() : query;
        LambdaQueryWrapper<AllocationOrder> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(q.getStatus())) {
            String status = q.getStatus().trim();
            if (!ORDER_STATUS_NAMES.containsKey(status)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "非法的单据状态筛选值：" + q.getStatus());
            }
            wrapper.eq(AllocationOrder::getStatus, status);
        }
        wrapper.eq(q.getFromStationId() != null, AllocationOrder::getFromStationId, q.getFromStationId());
        wrapper.eq(q.getToStationId() != null, AllocationOrder::getToStationId, q.getToStationId());
        wrapper.ge(q.getBeginTime() != null, AllocationOrder::getCreatedAt, q.getBeginTime());
        wrapper.le(q.getEndTime() != null, AllocationOrder::getCreatedAt, q.getEndTime());

        if (!AuthContext.isPlatformAdmin()) {
            Long stationId = AuthContext.stationId();
            if (stationId == null) {
                throw new BizException(ErrorCode.NO_DATA_PERMISSION, "当前账号未绑定站点，无法查询调拨单");
            }
            wrapper.and(w -> w.eq(AllocationOrder::getFromStationId, stationId)
                    .or()
                    .eq(AllocationOrder::getToStationId, stationId));
        }
        applyOrderBy(wrapper, q.getOrderBy());

        Page<AllocationOrder> page = new Page<>(normalizePageNum(q.getPageNum()), normalizePageSize(q.getPageSize()));
        Page<AllocationOrder> result = allocationOrderMapper.selectPage(page, wrapper);
        return PageResult.of(toVoList(result.getRecords()), result.getTotal(), result.getSize());
    }

    /**
     * 调拨单详情（5.3 GET /allocations/{id}）：含明细逐袋核收进度与在途超时标注。
     *
     * <p>归属二次校验（4.4）：操作人站点必须 ∈ {源站, 目标站}，否则 2002；校验顺序
     * 先资源存在性（3001），后归属（2002）。
     */
    public AllocationDetailVo detail(Long id) {
        AllocationOrder order = requireOrder(id);
        checkViewPermission(order);
        return buildDetailVo(order);
    }

    // ==================== 建单 ====================

    /**
     * 创建调拨单（5.3 POST /allocations，平台管理员）：建单即锁定明细袋。
     *
     * <p>校验（逐条照抄任务口径）：源站与目标站必须不同且都存在；袋码清单先去重，
     * 再逐只校验存在、状态为 IDLE、且未被其他未出库调拨单引用，任一不可用即整单拒绝
     * （3001 并写明哪些袋不可用）；单据与全部明细以 PENDING_DISPATCH 落库。
     */
    @Transactional(rollbackFor = Exception.class)
    public AllocationDetailVo create(CreateAllocationDto dto, String clientRequestId) {
        String crid = requireClientRequestId(clientRequestId);
        String cached = beginIdempotent(API_CREATE, crid);
        if (cached != null) {
            return JsonUtils.parse(cached, AllocationDetailVo.class);
        }
        try {
            AllocationDetailVo vo = doCreate(dto);
            idempotentService.markCompleted(API_CREATE, crid, JsonUtils.toJson(vo));
            return vo;
        } catch (RuntimeException e) {
            idempotentService.clear(API_CREATE, crid);
            throw e;
        }
    }

    private AllocationDetailVo doCreate(CreateAllocationDto dto) {
        if (dto.getFromStationId().equals(dto.getToStationId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "源站点与目标站点不能相同");
        }
        Station from = requireStation(dto.getFromStationId(), "源站点");
        Station to = requireStation(dto.getToStationId(), "目标站点");

        List<String> bagCodes = dedupeBagCodes(dto.getBagCodes());
        checkBatchSize(bagCodes.size());
        List<String> unavailable = collectUnavailableReasons(bagCodes, from.getId());
        if (!unavailable.isEmpty()) {
            // 整单原子：任一袋不可用都不建单，避免"建到一半锁了一部分"
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "以下袋码不可用，无法建单：" + String.join("；", unavailable));
        }

        AllocationOrder order = new AllocationOrder();
        order.setAllocationNo(BizNoUtils.allocationNo());
        order.setFromStationId(from.getId());
        order.setToStationId(to.getId());
        order.setStatus(ORDER_PENDING_DISPATCH);
        order.setCreatedBy(AuthContext.userId());
        order.setRemark(dto.getRemark());
        order.setVersion(0);
        order.setCreatedAt(LocalDateTime.now());
        allocationOrderMapper.insert(order);

        LocalDateTime now = LocalDateTime.now();
        for (String bagCode : bagCodes) {
            AllocationDetail detail = new AllocationDetail();
            detail.setAllocationId(order.getId());
            detail.setBagCode(bagCode);
            detail.setStatus(DETAIL_PENDING_DISPATCH);
            detail.setCreatedAt(now);
            allocationDetailMapper.insert(detail);
        }
        log.info("调拨单创建：allocationNo={}, from={}, to={}, 袋数={}",
                order.getAllocationNo(), order.getFromStationId(), order.getToStationId(), bagCodes.size());
        return buildDetailVo(order);
    }

    // ==================== 取消 / 终止 ====================

    /**
     * 取消或终止调拨单（5.3 DELETE /allocations/{id}，平台管理员）：Service 内按单据状态分派。
     *
     * <ul>
     *   <li>PENDING_DISPATCH 走「取消」：明细置 REMOVED 并记 removed_at（解锁回空闲），
     *       单据置 CANCELLED 并写 cancelled_at 与 cancel_reason；</li>
     *   <li>ABNORMAL 走「终止」：未出库明细解锁回空闲，单据置 TERMINATED 并写
     *       terminated_by、cancelled_at 与 cancel_reason，已出库/已核收/已遗失明细保留差异记录；</li>
     *   <li>其余状态一律 4105。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public AllocationDetailVo cancelOrTerminate(Long id, AllocationCancelDto dto, String clientRequestId) {
        String crid = requireClientRequestId(clientRequestId);
        String cached = beginIdempotent(API_CANCEL, crid);
        if (cached != null) {
            return JsonUtils.parse(cached, AllocationDetailVo.class);
        }
        try {
            AllocationOrder order = requireOrder(id);
            if (ORDER_PENDING_DISPATCH.equals(order.getStatus())) {
                doCancel(order, dto);
            } else if (ORDER_ABNORMAL.equals(order.getStatus())) {
                doTerminate(order, dto);
            } else {
                throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                        "单据当前状态为" + orderStatusName(order.getStatus())
                                + "，仅待出库可取消、异常可终止");
            }
            AllocationDetailVo vo = buildDetailVo(requireOrder(id));
            idempotentService.markCompleted(API_CANCEL, crid, JsonUtils.toJson(vo));
            return vo;
        } catch (RuntimeException e) {
            idempotentService.clear(API_CANCEL, crid);
            throw e;
        }
    }

    /** 取消：仅待出库可取消，明细袋解锁回空闲（袋从未离开空闲态，只需解除锁定）。 */
    private void doCancel(AllocationOrder order, AllocationCancelDto dto) {
        LocalDateTime now = LocalDateTime.now();
        String reason = resolveReason(dto, DEFAULT_CANCEL_REASON);
        unlockPendingDetails(order.getId(), now);
        int rows = allocationOrderMapper.update(null, new UpdateWrapper<AllocationOrder>()
                .eq("id", order.getId())
                .eq("status", ORDER_PENDING_DISPATCH)
                .set("status", ORDER_CANCELLED)
                .set("cancelled_at", now)
                .set("cancel_reason", reason));
        if (rows == 0) {
            // 条件 UPDATE 落空 = 并发下状态已被他人推进：与前置校验冲突，按 4105 拒绝
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "单据状态已变化，请刷新后重试");
        }
        log.info("调拨单取消：allocationNo={}, reason={}", order.getAllocationNo(), reason);
    }

    /** 终止：异常单据收尾，未出库明细解锁回空闲，其余明细保留差异记录。 */
    private void doTerminate(AllocationOrder order, AllocationCancelDto dto) {
        LocalDateTime now = LocalDateTime.now();
        String reason = resolveReason(dto, DEFAULT_TERMINATE_REASON);
        unlockPendingDetails(order.getId(), now);
        int rows = allocationOrderMapper.update(null, new UpdateWrapper<AllocationOrder>()
                .eq("id", order.getId())
                .eq("status", ORDER_ABNORMAL)
                .set("status", ORDER_TERMINATED)
                .set("cancelled_at", now)
                .set("cancel_reason", reason)
                .set("terminated_by", AuthContext.userId()));
        if (rows == 0) {
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "单据状态已变化，请刷新后重试");
        }
        // @AuditLog 只能声明静态动作（该端点主语义为"取消调拨单"），异常终止路径在此补记一条
        // 动作明确的审计：否则"终止调拨单"会在审计中缺失（7.6 关键事件必审）
        recordTerminateAudit(order, reason);
        log.info("调拨单终止：allocationNo={}, reason={}", order.getAllocationNo(), reason);
    }

    /** 未出库明细解锁（置 REMOVED 并记剔除时间）；已出库/已核收/已遗失/已剔除明细不动。 */
    private void unlockPendingDetails(Long allocationId, LocalDateTime now) {
        allocationDetailMapper.update(null, new UpdateWrapper<AllocationDetail>()
                .eq("allocation_id", allocationId)
                .eq("status", DETAIL_PENDING_DISPATCH)
                .set("status", DETAIL_REMOVED)
                .set("removed_at", now));
    }

    /** 补充审计写入（审计服务自身吞异常，不抛）。 */
    private void recordTerminateAudit(AllocationOrder order, String reason) {
        LoginUser operator = AuthContext.get();
        AuditLogEntity entity = new AuditLogEntity();
        entity.setOperatorId(operator == null || operator.getUserId() == null ? 0L : operator.getUserId());
        entity.setOperatorRole(operator == null || operator.getRole() == null ? "" : operator.getRole());
        entity.setModule(AUDIT_MODULE);
        entity.setAction("终止调拨单");
        entity.setRequestUri("/api/v1/allocations/" + order.getId());
        entity.setParamsMasked(MaskUtils.truncate(
                JsonUtils.toJson(Map.of("allocationNo", order.getAllocationNo(), "reason", reason)), 4096));
        entity.setIp("");
        entity.setUa("");
        entity.setResult(1);
        entity.setErrorCode(ErrorCode.SUCCESS.getCode());
        entity.setCostMs(0);
        auditLogService.record(entity);
    }

    // ==================== 出库 ====================

    /**
     * 调拨出库（5.3 POST /allocations/{id}/dispatch，源站站点管理员；平台管理员可代操作）。
     *
     * <p>单据必须为 PENDING_DISPATCH 或 IN_TRANSIT（否则 4105）；逐个袋：明细属于该单且为
     * PENDING_DISPATCH → 触发 ALLOCATE_OUT（袋 空闲→调拨在途、源站库存 −1）；首只出库时把单据
     * 置 IN_TRANSIT 并写 dispatch_at。逐袋返回结果明细，单只失败不影响其余。
     */
    public List<BagResult> dispatch(Long id, AllocationDispatchDto dto, String clientRequestId) {
        String crid = requireClientRequestId(clientRequestId);
        String cached = beginIdempotent(API_DISPATCH, crid);
        if (cached != null) {
            return replayBagResults(cached);
        }
        try {
            List<BagResult> results = doDispatch(id, dto, crid);
            idempotentService.markCompleted(API_DISPATCH, crid, JsonUtils.toJson(results));
            return results;
        } catch (RuntimeException e) {
            idempotentService.clear(API_DISPATCH, crid);
            throw e;
        }
    }

    private List<BagResult> doDispatch(Long id, AllocationDispatchDto dto, String crid) {
        AllocationOrder order = requireOrder(id);
        checkSourceStationPermission(order);
        if (!ORDER_PENDING_DISPATCH.equals(order.getStatus()) && !ORDER_IN_TRANSIT.equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                    "单据当前状态为" + orderStatusName(order.getStatus()) + "，仅待出库或途中的调拨单可出库");
        }
        List<AllocationDispatchDto.BagItem> bags = dto.getBags();
        checkBatchSize(bags.size());
        List<BagResult> results = new ArrayList<>(bags.size());
        Set<String> seen = new HashSet<>();
        for (AllocationDispatchDto.BagItem item : bags) {
            String bagCode = item.getBagCode() == null ? "" : item.getBagCode().trim();
            if (!seen.add(bagCode)) {
                results.add(BagResult.fail(bagCode, ErrorCode.PARAM_INVALID, "袋码在本次请求中重复"));
                continue;
            }
            results.add(dispatchOne(order, bagCode, effectiveBagRemark(item.getRemark(), dto.getRemark()), crid));
        }
        return results;
    }

    private BagResult dispatchOne(AllocationOrder order, String bagCode, String remark, String crid) {
        AllocationDetail detail = findDetail(order.getId(), bagCode);
        if (detail == null) {
            return BagResult.fail(bagCode, ErrorCode.PARAM_INVALID, "该袋不在本调拨单明细中");
        }
        if (!DETAIL_PENDING_DISPATCH.equals(detail.getStatus())) {
            return BagResult.fail(bagCode, ErrorCode.ORDER_STATE_NOT_ALLOWED,
                    "明细当前状态为" + detailStatusName(detail.getStatus()) + "，仅待出库明细可出库");
        }

        BagTransitionResult transition;
        try {
            transition = bagTransitionService.fire(TransitionCommand.builder()
                    .bagCode(bagCode)
                    .event(BagEvent.ALLOCATE_OUT)
                    .operatorId(AuthContext.userId())
                    .operatorRole(AuthContext.role())
                    .stationId(order.getFromStationId())
                    .proxy(AuthContext.isPlatformAdmin())
                    .clientRequestId(crid)
                    .attributes(allocationAttributes(order))
                    .build());
        } catch (BizException e) {
            // 业务拒绝（袋非空闲 4102、已被并发流转 4103 等）：记为单袋失败，不影响其余袋
            return BagResult.fail(bagCode, e.getErrorCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("调拨出库流转异常：allocationNo={}, bagCode={}", order.getAllocationNo(), bagCode, e);
            return BagResult.fail(bagCode, ErrorCode.SYSTEM_ERROR, "出库失败，请稍后重试");
        }
        if (transition.isReplayed() && transition.getToStatus() != BagStatus.TRANSFERRING) {
            // 幂等键命中但袋并未处于调拨在途：首笔流转未真正提交（Redis 幂等键不随 DB 事务回滚），
            // 不能据此判定成功，提示客户端稍后重试（幂等窗口 10 分钟后自动消散）
            return BagResult.fail(bagCode, ErrorCode.ORDER_STATE_NOT_ALLOWED, "该袋流转状态未确认，请稍后重试");
        }

        LocalDateTime now = LocalDateTime.now();
        allocationDetailMapper.update(null, new UpdateWrapper<AllocationDetail>()
                .eq("id", detail.getId())
                .eq("status", DETAIL_PENDING_DISPATCH)
                .set("status", DETAIL_DISPATCHED)
                .set("dispatch_at", now)
                .set(StringUtils.hasText(remark), "remark", remark));
        // 首只出库：单据转在途并记 dispatch_at（在途超时扫描的起算基准）；条件 UPDATE 使重复执行无副作用
        allocationOrderMapper.update(null, new UpdateWrapper<AllocationOrder>()
                .eq("id", order.getId())
                .eq("status", ORDER_PENDING_DISPATCH)
                .set("status", ORDER_IN_TRANSIT)
                .set("dispatch_at", now));
        return BagResult.ok(bagCode);
    }

    // ==================== 核收 ====================

    /**
     * 调拨核收入库（5.3 POST /allocations/{id}/receive，目标站站点管理员；平台管理员可代操作）。
     *
     * <p>单据必须为 IN_TRANSIT（否则 4105）；逐个袋：明细为 DISPATCHED → 触发 ALLOCATE_IN
     * （袋 调拨在途→空闲、归属改目标站、目标站库存 +1）；明细置 RECEIVED 并记 receive_at；
     * 全部未剔除明细核收完成后单据置 COMPLETED 并写 completed_at。
     */
    public List<BagResult> receive(Long id, AllocationReceiveDto dto, String clientRequestId) {
        String crid = requireClientRequestId(clientRequestId);
        String cached = beginIdempotent(API_RECEIVE, crid);
        if (cached != null) {
            return replayBagResults(cached);
        }
        try {
            List<BagResult> results = doReceive(id, dto, crid);
            idempotentService.markCompleted(API_RECEIVE, crid, JsonUtils.toJson(results));
            return results;
        } catch (RuntimeException e) {
            idempotentService.clear(API_RECEIVE, crid);
            throw e;
        }
    }

    private List<BagResult> doReceive(Long id, AllocationReceiveDto dto, String crid) {
        AllocationOrder order = requireOrder(id);
        checkTargetStationPermission(order);
        if (!ORDER_IN_TRANSIT.equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                    "单据当前状态为" + orderStatusName(order.getStatus()) + "，仅途中的调拨单可核收");
        }
        List<AllocationReceiveDto.BagItem> bags = dto.getBags();
        checkBatchSize(bags.size());
        List<BagResult> results = new ArrayList<>(bags.size());
        Set<String> seen = new HashSet<>();
        boolean anySuccess = false;
        for (AllocationReceiveDto.BagItem item : bags) {
            String bagCode = item.getBagCode() == null ? "" : item.getBagCode().trim();
            if (!seen.add(bagCode)) {
                results.add(BagResult.fail(bagCode, ErrorCode.PARAM_INVALID, "袋码在本次请求中重复"));
                continue;
            }
            BagResult result = receiveOne(order, bagCode, effectiveBagRemark(item.getRemark(), dto.getRemark()), crid);
            results.add(result);
            anySuccess |= result.success();
        }
        if (anySuccess) {
            completeIfAllReceived(order);
        }
        return results;
    }

    private BagResult receiveOne(AllocationOrder order, String bagCode, String remark, String crid) {
        AllocationDetail detail = findDetail(order.getId(), bagCode);
        if (detail == null) {
            return BagResult.fail(bagCode, ErrorCode.PARAM_INVALID, "该袋不在本调拨单明细中");
        }
        if (!DETAIL_DISPATCHED.equals(detail.getStatus())) {
            return BagResult.fail(bagCode, ErrorCode.ORDER_STATE_NOT_ALLOWED,
                    "明细当前状态为" + detailStatusName(detail.getStatus()) + "，仅已出库明细可核收");
        }

        BagTransitionResult transition;
        try {
            transition = bagTransitionService.fire(TransitionCommand.builder()
                    .bagCode(bagCode)
                    .event(BagEvent.ALLOCATE_IN)
                    .operatorId(AuthContext.userId())
                    .operatorRole(AuthContext.role())
                    .stationId(order.getToStationId())
                    .targetStationId(order.getToStationId())
                    .proxy(AuthContext.isPlatformAdmin())
                    .clientRequestId(crid)
                    .attributes(allocationAttributes(order))
                    .build());
        } catch (BizException e) {
            return BagResult.fail(bagCode, e.getErrorCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("调拨核收流转异常：allocationNo={}, bagCode={}", order.getAllocationNo(), bagCode, e);
            return BagResult.fail(bagCode, ErrorCode.SYSTEM_ERROR, "核收失败，请稍后重试");
        }
        if (transition.isReplayed() && transition.getToStatus() != BagStatus.IDLE) {
            return BagResult.fail(bagCode, ErrorCode.ORDER_STATE_NOT_ALLOWED, "该袋流转状态未确认，请稍后重试");
        }

        LocalDateTime now = LocalDateTime.now();
        allocationDetailMapper.update(null, new UpdateWrapper<AllocationDetail>()
                .eq("id", detail.getId())
                .eq("status", DETAIL_DISPATCHED)
                .set("status", DETAIL_RECEIVED)
                .set("receive_at", now)
                .set(StringUtils.hasText(remark), "remark", remark));
        return BagResult.ok(bagCode);
    }

    /**
     * 全部（未剔除）明细核收完成则单据置 COMPLETED 并写 completed_at。
     *
     * <p>剔除明细不参与完成判定：剔除的袋已解锁回空闲、不属本次在途货物（设计书 3.4.3
     * 「扫码失败剔除后其余继续出库」，若把 REMOVED 计入将导致单据永远无法闭环）；
     * 在途遗失明细同样不参与——该场景下单据已置 ABNORMAL，由平台管理员终止收尾。
     */
    private void completeIfAllReceived(AllocationOrder order) {
        Long pending = allocationDetailMapper.selectCount(new LambdaQueryWrapper<AllocationDetail>()
                .eq(AllocationDetail::getAllocationId, order.getId())
                .in(AllocationDetail::getStatus, List.of(DETAIL_PENDING_DISPATCH, DETAIL_DISPATCHED)));
        if (pending != null && pending == 0) {
            Long received = allocationDetailMapper.selectCount(new LambdaQueryWrapper<AllocationDetail>()
                    .eq(AllocationDetail::getAllocationId, order.getId())
                    .eq(AllocationDetail::getStatus, DETAIL_RECEIVED));
            if (received != null && received > 0) {
                allocationOrderMapper.update(null, new UpdateWrapper<AllocationOrder>()
                        .eq("id", order.getId())
                        .eq("status", ORDER_IN_TRANSIT)
                        .set("status", ORDER_COMPLETED)
                        .set("completed_at", LocalDateTime.now()));
            }
        }
    }

    // ==================== 在途遗失确认 ====================

    /**
     * 在途遗失确认（5.3 POST /allocations/{id}/lost-confirm，平台管理员，3.2 #19）。
     *
     * <p>仅 IN_TRANSIT（否则 4105，含已异常单据不可重复确认）；逐个袋：明细为 DISPATCHED →
     * 触发 LOST_IN_TRANSIT（袋→已遗失）；单据置 ABNORMAL 并写 abnormal_at；
     * 写责任记录（biz_type=ALLOCATION、type=在途遗失、responsible_station_id=源站、biz_no=调拨单号）。
     */
    public List<BagResult> lostConfirm(Long id, AllocationLostDto dto, String clientRequestId) {
        String crid = requireClientRequestId(clientRequestId);
        String cached = beginIdempotent(API_LOST_CONFIRM, crid);
        if (cached != null) {
            return replayBagResults(cached);
        }
        try {
            List<BagResult> results = doLostConfirm(id, dto, crid);
            idempotentService.markCompleted(API_LOST_CONFIRM, crid, JsonUtils.toJson(results));
            return results;
        } catch (RuntimeException e) {
            idempotentService.clear(API_LOST_CONFIRM, crid);
            throw e;
        }
    }

    private List<BagResult> doLostConfirm(Long id, AllocationLostDto dto, String crid) {
        AllocationOrder order = requireOrder(id);
        if (!ORDER_IN_TRANSIT.equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                    "单据当前状态为" + orderStatusName(order.getStatus()) + "，仅途中的调拨单可确认在途遗失");
        }
        List<AllocationLostDto.BagItem> bags = dto.getBags();
        if (bags == null || bags.isEmpty()) {
            // 单据级裁决：未指定清单时作用于该单当前全部「已出库未核收」的明细。
            // 由服务端现算而非要求前端枚举，避免前端提交期间有袋被核收导致清单过期。
            List<AllocationDetail> inTransit = allocationDetailMapper.selectList(
                    new LambdaQueryWrapper<AllocationDetail>()
                            .eq(AllocationDetail::getAllocationId, order.getId())
                            .eq(AllocationDetail::getStatus, DETAIL_DISPATCHED)
                            .orderByAsc(AllocationDetail::getId));
            bags = inTransit.stream()
                    .map(detail -> {
                        AllocationLostDto.BagItem item = new AllocationLostDto.BagItem();
                        item.setBagCode(detail.getBagCode());
                        return item;
                    })
                    .toList();
            if (bags.isEmpty()) {
                throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                        "该调拨单没有处于在途（已出库未核收）的明细，无需确认遗失");
            }
        }
        checkBatchSize(bags.size());
        List<BagResult> results = new ArrayList<>(bags.size());
        List<String> lostBagCodes = new ArrayList<>(bags.size());
        Set<String> seen = new HashSet<>();
        for (AllocationLostDto.BagItem item : bags) {
            String bagCode = item.getBagCode() == null ? "" : item.getBagCode().trim();
            if (!seen.add(bagCode)) {
                results.add(BagResult.fail(bagCode, ErrorCode.PARAM_INVALID, "袋码在本次请求中重复"));
                continue;
            }
            BagResult result = lostConfirmOne(order, bagCode, item.getRemark(), crid);
            results.add(result);
            if (result.success()) {
                lostBagCodes.add(bagCode);
            }
        }
        if (!lostBagCodes.isEmpty()) {
            markOrderAbnormal(order);
            writeLiabilityRecord(order, dto, lostBagCodes);
            log.warn("调拨在途遗失确认：allocationNo={}, 源站={}, 遗失袋={}",
                    order.getAllocationNo(), order.getFromStationId(), String.join(",", lostBagCodes));
        }
        return results;
    }

    private BagResult lostConfirmOne(AllocationOrder order, String bagCode, String remark, String crid) {
        AllocationDetail detail = findDetail(order.getId(), bagCode);
        if (detail == null) {
            return BagResult.fail(bagCode, ErrorCode.PARAM_INVALID, "该袋不在本调拨单明细中");
        }
        if (!DETAIL_DISPATCHED.equals(detail.getStatus())) {
            return BagResult.fail(bagCode, ErrorCode.ORDER_STATE_NOT_ALLOWED,
                    "明细当前状态为" + detailStatusName(detail.getStatus()) + "，仅已出库（在途）明细可确认遗失");
        }

        BagTransitionResult transition;
        try {
            transition = bagTransitionService.fire(TransitionCommand.builder()
                    .bagCode(bagCode)
                    .event(BagEvent.LOST_IN_TRANSIT)
                    .operatorId(AuthContext.userId())
                    .operatorRole(AuthContext.role())
                    // 命令站号取源站：责任主体为源站（3.2 责任认定口径），流转日志据此留痕
                    .stationId(order.getFromStationId())
                    .proxy(false)
                    .clientRequestId(crid)
                    .attributes(allocationAttributes(order))
                    .build());
        } catch (BizException e) {
            return BagResult.fail(bagCode, e.getErrorCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("在途遗失流转异常：allocationNo={}, bagCode={}", order.getAllocationNo(), bagCode, e);
            return BagResult.fail(bagCode, ErrorCode.SYSTEM_ERROR, "遗失确认失败，请稍后重试");
        }
        if (transition.isReplayed() && transition.getToStatus() != BagStatus.LOST) {
            return BagResult.fail(bagCode, ErrorCode.ORDER_STATE_NOT_ALLOWED, "该袋流转状态未确认，请稍后重试");
        }

        allocationDetailMapper.update(null, new UpdateWrapper<AllocationDetail>()
                .eq("id", detail.getId())
                .eq("status", DETAIL_DISPATCHED)
                .set("status", DETAIL_LOST)
                .set(StringUtils.hasText(remark), "remark", remark));
        return BagResult.ok(bagCode);
    }

    /** 单据置异常并写 abnormal_at（条件 UPDATE：仅当仍在途，重复执行无副作用）。 */
    private void markOrderAbnormal(AllocationOrder order) {
        allocationOrderMapper.update(null, new UpdateWrapper<AllocationOrder>()
                .eq("id", order.getId())
                .eq("status", ORDER_IN_TRANSIT)
                .set("status", ORDER_ABNORMAL)
                .set("abnormal_at", LocalDateTime.now()));
    }

    /** 写责任记录（3.2 责任认定口径：调拨在途遗失责任主体为源站，由平台管理员裁决）。 */
    private void writeLiabilityRecord(AllocationOrder order, AllocationLostDto dto, List<String> lostBagCodes) {
        LocalDateTime now = LocalDateTime.now();
        LiabilityRecord record = new LiabilityRecord();
        record.setBizType("ALLOCATION");
        record.setBizNo(order.getAllocationNo());
        record.setType("在途遗失");
        record.setResponsibleStationId(order.getFromStationId());
        record.setResponsibleUserId(null);
        record.setConclusion(StringUtils.hasText(dto.getConclusion()) ? dto.getConclusion() : DEFAULT_LOST_CONCLUSION);
        record.setDecidedBy(AuthContext.userId());
        record.setDecidedAt(now);
        record.setStatus("CONFIRMED");
        StringBuilder note = new StringBuilder();
        if (StringUtils.hasText(dto.getNote())) {
            note.append(dto.getNote()).append("；");
        }
        note.append("遗失袋码：").append(String.join("、", lostBagCodes));
        record.setNote(MaskUtils.truncate(note.toString(), 255));
        record.setCreatedAt(now);
        liabilityRecordMapper.insert(record);
    }

    // ==================== 剔除明细 ====================

    /**
     * 剔除调拨明细（5.3 POST /allocations/{id}/remove-detail，平台管理员，3.4.3）。
     *
     * <p>仅「待出库 / 在途」单据、明细为 PENDING_DISPATCH、且袋当前仍为 IDLE 时可剔除
     * （明细置 REMOVED 并记 removed_at，袋解锁回空闲）；袋已非空闲（如被转维修/报废/状态修正）
     * 时无法剔除，抛 4105 并提示须先终止调拨单。
     */
    @Transactional(rollbackFor = Exception.class)
    public AllocationDetailVo removeDetail(Long id, AllocationRemoveDto dto, String clientRequestId) {
        String crid = requireClientRequestId(clientRequestId);
        String cached = beginIdempotent(API_REMOVE_DETAIL, crid);
        if (cached != null) {
            return JsonUtils.parse(cached, AllocationDetailVo.class);
        }
        try {
            AllocationDetailVo vo = doRemoveDetail(id, dto);
            idempotentService.markCompleted(API_REMOVE_DETAIL, crid, JsonUtils.toJson(vo));
            return vo;
        } catch (RuntimeException e) {
            idempotentService.clear(API_REMOVE_DETAIL, crid);
            throw e;
        }
    }

    private AllocationDetailVo doRemoveDetail(Long id, AllocationRemoveDto dto) {
        AllocationOrder order = requireOrder(id);
        if (!ORDER_PENDING_DISPATCH.equals(order.getStatus()) && !ORDER_IN_TRANSIT.equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                    "单据当前状态为" + orderStatusName(order.getStatus())
                            + "，仅待出库或途中的调拨单可剔除明细");
        }
        String bagCode = dto.getBagCode() == null ? "" : dto.getBagCode().trim();
        AllocationDetail detail = findDetail(order.getId(), bagCode);
        if (detail == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该袋不在本调拨单明细中：" + bagCode);
        }
        if (!DETAIL_PENDING_DISPATCH.equals(detail.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                    "该明细当前状态为" + detailStatusName(detail.getStatus())
                            + "，已出库或已处置的明细无法剔除，须先终止调拨单");
        }
        Bag bag = bagMapper.selectOne(new LambdaQueryWrapper<Bag>().eq(Bag::getBagCode, bagCode));
        if (bag == null) {
            throw new BizException(ErrorCode.BAG_NOT_FOUND, "袋码不存在：" + bagCode);
        }
        if (!BagStatus.IDLE.code().equals(bag.getStatus())) {
            // 袋已非空闲：转维修/报废/状态修正等已把袋移出空闲态，剔除不再成立（3.4.3）
            throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "袋已非空闲，须先终止调拨单");
        }
        allocationDetailMapper.update(null, new UpdateWrapper<AllocationDetail>()
                .eq("id", detail.getId())
                .eq("status", DETAIL_PENDING_DISPATCH)
                .set("status", DETAIL_REMOVED)
                .set("removed_at", LocalDateTime.now())
                .set(StringUtils.hasText(dto.getRemark()), "remark", dto.getRemark()));
        log.info("调拨明细剔除：allocationNo={}, bagCode={}", order.getAllocationNo(), bagCode);
        return buildDetailVo(requireOrder(id));
    }

    // ==================== 4110 判定入口 ====================

    /**
     * 判定袋是否被「未出库调拨单」引用（设计书错误码 <b>4110 的唯一判定入口</b>）。
     *
     * <p>规则（3.2 建单即锁定）：存在该袋的调拨明细且明细状态为 PENDING_DISPATCH、
     * 所属单据状态 ∈ {PENDING_DISPATCH, IN_TRANSIT}，即视为锁定。申领（#2）、转维修（#12）、
     * 直接报废（#15）、状态修正（#21）等一切需要 4110 判定的调用方都应调用本方法
     * （契约模块版 8.4 明示 module-bag 的 BagService 亦调用本方法），
     * 不得各自实现一份——口径漂移会出现"有的入口拦、有的入口放"。
     *
     * @param bagCode 快递袋编码；null / 空白返回 false
     * @return true = 被未出库调拨单锁定，相关操作应抛 {@code BizException(BAG_LOCKED_BY_ALLOCATION)}
     */
    public boolean isBagLocked(String bagCode) {
        if (bagCode == null || bagCode.isBlank()) {
            return false;
        }
        return !findLockedBagCodes(List.of(bagCode)).isEmpty();
    }

    /** 批量版锁定判定：一次查询找出入参袋码中处于锁定态的集合（建单校验与 isBagLocked 共用）。 */
    private Set<String> findLockedBagCodes(Collection<String> bagCodes) {
        List<String> codes = new ArrayList<>();
        for (String bagCode : bagCodes) {
            if (bagCode != null && !bagCode.isBlank()) {
                codes.add(bagCode);
            }
        }
        if (codes.isEmpty()) {
            return Set.of();
        }
        List<AllocationDetail> pendingDetails = allocationDetailMapper.selectList(
                new LambdaQueryWrapper<AllocationDetail>()
                        .select(AllocationDetail::getBagCode, AllocationDetail::getAllocationId)
                        .in(AllocationDetail::getBagCode, codes)
                        .eq(AllocationDetail::getStatus, DETAIL_PENDING_DISPATCH));
        if (pendingDetails.isEmpty()) {
            return Set.of();
        }
        Set<Long> orderIds = new HashSet<>();
        for (AllocationDetail detail : pendingDetails) {
            orderIds.add(detail.getAllocationId());
        }
        List<AllocationOrder> activeOrders = allocationOrderMapper.selectList(
                new LambdaQueryWrapper<AllocationOrder>()
                        .select(AllocationOrder::getId)
                        .in(AllocationOrder::getId, orderIds)
                        .in(AllocationOrder::getStatus, List.of(ORDER_PENDING_DISPATCH, ORDER_IN_TRANSIT)));
        if (activeOrders.isEmpty()) {
            return Set.of();
        }
        Set<Long> activeIds = new HashSet<>();
        for (AllocationOrder order : activeOrders) {
            activeIds.add(order.getId());
        }
        Set<String> locked = new HashSet<>();
        for (AllocationDetail detail : pendingDetails) {
            if (activeIds.contains(detail.getAllocationId())) {
                locked.add(detail.getBagCode());
            }
        }
        return locked;
    }

    // ==================== 幂等 / 校验 / 权限（私有） ====================

    /** 校验并返回请求头 clientRequestId（9.6）：缺失或非 UUID 格式一律 3001。 */
    private static String requireClientRequestId(String clientRequestId) {
        if (clientRequestId == null || !clientRequestId.matches(Constants.UUID_REGEX)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少或非法的 clientRequestId 请求头");
        }
        return clientRequestId;
    }

    /**
     * 幂等前置（契约模块版第 4 节范式）：
     * 返回首次结果 JSON（调用方直接回放）；null 表示首次执行，已写入「处理中」占位。
     */
    private String beginIdempotent(String apiKey, String clientRequestId) {
        String cached = idempotentService.findCompleted(apiKey, clientRequestId);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached == null) {
            idempotentService.markProcessing(apiKey, clientRequestId);
        }
        return cached;
    }

    /** 回放批量接口首次结果。 */
    private static List<BagResult> replayBagResults(String cached) {
        List<BagResult> replayed = JsonUtils.parseList(cached, BagResult.class);
        return replayed == null ? List.of() : replayed;
    }

    /** 调拨单装载：不存在按 3001 拒绝（错误码表未定义单据级 404，参数语义归 3001）。 */
    private AllocationOrder requireOrder(Long id) {
        if (id == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "调拨单ID不能为空");
        }
        AllocationOrder order = allocationOrderMapper.selectById(id);
        if (order == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "调拨单不存在：" + id);
        }
        return order;
    }

    /** 站点装载：不存在按 3001 拒绝。 */
    private Station requireStation(Long stationId, String label) {
        Station station = stationMapper.selectById(stationId);
        if (station == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, label + "不存在：" + stationId);
        }
        return station;
    }

    /** 按（调拨单, 袋码）装载明细；唯一键 uk_allocation_detail_allocation_id_bag_code 保证至多一条。 */
    private AllocationDetail findDetail(Long allocationId, String bagCode) {
        if (bagCode == null || bagCode.isBlank()) {
            return null;
        }
        return allocationDetailMapper.selectOne(new LambdaQueryWrapper<AllocationDetail>()
                .eq(AllocationDetail::getAllocationId, allocationId)
                .eq(AllocationDetail::getBagCode, bagCode));
    }

    /** 视图权限：平台管理员放行；站点管理员要求 ∈ {源站, 目标站}（4.4 第⑤条）。 */
    private static void checkViewPermission(AllocationOrder order) {
        if (AuthContext.isPlatformAdmin()) {
            return;
        }
        Long stationId = AuthContext.stationId();
        if (stationId == null
                || (!stationId.equals(order.getFromStationId()) && !stationId.equals(order.getToStationId()))) {
            throw new BizException(ErrorCode.NO_DATA_PERMISSION, "仅源站或目标站站点管理员可查看该调拨单");
        }
    }

    /** 出库权限：源站站点管理员（平台管理员全站代操作，4.4 第②条）。 */
    private static void checkSourceStationPermission(AllocationOrder order) {
        if (AuthContext.isPlatformAdmin()) {
            return;
        }
        Long stationId = AuthContext.stationId();
        if (stationId == null || !stationId.equals(order.getFromStationId())) {
            throw new BizException(ErrorCode.NO_DATA_PERMISSION, "仅源站站点管理员可执行调拨出库");
        }
    }

    /** 核收权限：目标站站点管理员（平台管理员全站代操作，4.4 第③条）。 */
    private static void checkTargetStationPermission(AllocationOrder order) {
        if (AuthContext.isPlatformAdmin()) {
            return;
        }
        Long stationId = AuthContext.stationId();
        if (stationId == null || !stationId.equals(order.getToStationId())) {
            throw new BizException(ErrorCode.NO_DATA_PERMISSION, "仅目标站站点管理员可执行调拨核收");
        }
    }

    /** 批量上限：默认读 {@code sems.batch.max-size}（超限 3001，文案写明上限值，见 6.2）。 */
    private void checkBatchSize(int size) {
        int max = properties.getBatch().getMaxSize();
        if (size > max) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单批数量超限：最多 " + max + " 只");
        }
    }

    /** 袋码清单去重（保序）：重复袋码只锁一次，避免同一单明细唯一键冲突。 */
    private static List<String> dedupeBagCodes(List<String> bagCodes) {
        if (bagCodes == null || bagCodes.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(bagCodes));
    }

    /** 逐只收集不可用原因（存在性 / 空闲态 / 调拨锁定），供建单整单拒绝时写明明细。 */
    private List<String> collectUnavailableReasons(List<String> bagCodes, Long sourceStationId) {
        List<String> reasons = new ArrayList<>();
        List<String> validFormatCodes = new ArrayList<>(bagCodes.size());
        for (String bagCode : bagCodes) {
            if (bagCode == null || bagCode.isBlank()) {
                reasons.add("（空袋码）");
            } else if (!bagCode.matches(Constants.BAG_CODE_REGEX)) {
                reasons.add(bagCode + "（袋码格式非法）");
            } else {
                validFormatCodes.add(bagCode);
            }
        }
        if (validFormatCodes.isEmpty()) {
            return reasons;
        }
        Map<String, Bag> bagByCode = new HashMap<>();
        List<Bag> bags = bagMapper.selectList(new LambdaQueryWrapper<Bag>().in(Bag::getBagCode, validFormatCodes)
                .orderByAsc(Bag::getBagCode).last("FOR UPDATE"));
        for (Bag bag : bags) {
            bagByCode.put(bag.getBagCode(), bag);
        }
        Set<String> locked = findLockedBagCodes(validFormatCodes);
        for (String bagCode : validFormatCodes) {
            Bag bag = bagByCode.get(bagCode);
            if (bag == null) {
                reasons.add(bagCode + "（袋码不存在）");
                continue;
            }
            if (!Objects.equals(sourceStationId, bag.getStationId())) {
                reasons.add(bagCode + "（袋不属于源站点）");
                continue;
            }
            if (!BagStatus.IDLE.code().equals(bag.getStatus())) {
                reasons.add(bagCode + "（当前状态非空闲：" + BagStatus.of(bag.getStatus()).displayName() + "）");
                continue;
            }
            if (locked.contains(bagCode)) {
                reasons.add(bagCode + "（已被其他未出库调拨单锁定）");
            }
        }
        return reasons;
    }

    /** 单袋备注取用：优先逐袋备注，缺省回落整批备注（都没有则 null，表示不改写明细备注列）。 */
    private static String effectiveBagRemark(String itemRemark, String batchRemark) {
        if (StringUtils.hasText(itemRemark)) {
            return itemRemark;
        }
        return StringUtils.hasText(batchRemark) ? batchRemark : null;
    }

    /** 取取消/终止原因：请求未给时补默认文案（cancel_reason 全量留痕便于追溯）。 */
    private static String resolveReason(AllocationCancelDto dto, String defaultReason) {
        if (dto != null && StringUtils.hasText(dto.getReason())) {
            return dto.getReason().trim();
        }
        return defaultReason;
    }

    /** 状态机编排所需的调拨单上下文（动作侧据此填充 Redis 增减与推送载荷）。 */
    private static Map<String, Object> allocationAttributes(AllocationOrder order) {
        Map<String, Object> attributes = new HashMap<>(4);
        attributes.put(AllocationTransitionActions.ATTR_ALLOCATION_ID, order.getId());
        attributes.put(AllocationTransitionActions.ATTR_ALLOCATION_NO, order.getAllocationNo());
        attributes.put(AllocationTransitionActions.ATTR_FROM_STATION_ID, order.getFromStationId());
        attributes.put(AllocationTransitionActions.ATTR_TO_STATION_ID, order.getToStationId());
        return attributes;
    }

    // ==================== VO 组装（私有） ====================

    /** 列表视图批量组装：一次 IN 查询取回本页明细与站点名，避免逐行 N+1。 */
    private List<AllocationVo> toVoList(List<AllocationOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        List<Long> orderIds = orders.stream().map(AllocationOrder::getId).toList();
        List<AllocationDetail> details = allocationDetailMapper.selectList(
                new LambdaQueryWrapper<AllocationDetail>().in(AllocationDetail::getAllocationId, orderIds));
        Map<Long, List<AllocationDetail>> detailsByOrder = new HashMap<>();
        for (AllocationDetail detail : details) {
            detailsByOrder.computeIfAbsent(detail.getAllocationId(), key -> new ArrayList<>()).add(detail);
        }
        Map<Long, String> stationNames = loadStationNames(orders);

        List<AllocationVo> list = new ArrayList<>(orders.size());
        for (AllocationOrder order : orders) {
            AllocationVo vo = new AllocationVo();
            fillOrderFields(vo, order, detailsByOrder.getOrDefault(order.getId(), List.of()), stationNames);
            list.add(vo);
        }
        return list;
    }

    /** 详情视图：单头字段 + 明细逐袋进度。 */
    private AllocationDetailVo buildDetailVo(AllocationOrder order) {
        List<AllocationDetail> details = allocationDetailMapper.selectList(
                new LambdaQueryWrapper<AllocationDetail>()
                        .eq(AllocationDetail::getAllocationId, order.getId())
                        .orderByAsc(AllocationDetail::getId));
        Map<Long, String> stationNames = loadStationNames(List.of(order));

        AllocationDetailVo vo = new AllocationDetailVo();
        fillOrderFields(vo, order, details, stationNames);

        List<AllocationDetailVo.DetailItem> items = new ArrayList<>(details.size());
        for (AllocationDetail detail : details) {
            AllocationDetailVo.DetailItem item = new AllocationDetailVo.DetailItem();
            item.setId(detail.getId());
            item.setBagCode(detail.getBagCode());
            item.setStatus(detail.getStatus());
            item.setStatusName(detailStatusName(detail.getStatus()));
            item.setDispatchAt(detail.getDispatchAt());
            item.setReceiveAt(detail.getReceiveAt());
            item.setRemovedAt(detail.getRemovedAt());
            item.setRemark(detail.getRemark());
            items.add(item);
        }
        vo.setDetails(items);
        return vo;
    }

    /** 单头字段与进度计数填充（列表 / 详情共用）。 */
    private void fillOrderFields(AllocationVo vo, AllocationOrder order,
                                 List<AllocationDetail> details, Map<Long, String> stationNames) {
        vo.setId(order.getId());
        vo.setAllocationNo(order.getAllocationNo());
        vo.setFromStationId(order.getFromStationId());
        vo.setFromStationName(stationNames.get(order.getFromStationId()));
        vo.setToStationId(order.getToStationId());
        vo.setToStationName(stationNames.get(order.getToStationId()));
        vo.setStatus(order.getStatus());
        vo.setStatusName(orderStatusName(order.getStatus()));
        vo.setRemark(order.getRemark());
        vo.setDispatchAt(order.getDispatchAt());
        vo.setCompletedAt(order.getCompletedAt());
        vo.setAbnormalAt(order.getAbnormalAt());
        vo.setCancelledAt(order.getCancelledAt());
        vo.setCreatedAt(order.getCreatedAt());

        int pending = 0;
        int dispatched = 0;
        int received = 0;
        int removed = 0;
        int lost = 0;
        for (AllocationDetail detail : details) {
            switch (detail.getStatus() == null ? "" : detail.getStatus()) {
                case DETAIL_PENDING_DISPATCH -> pending++;
                case DETAIL_DISPATCHED -> dispatched++;
                case DETAIL_RECEIVED -> received++;
                case DETAIL_REMOVED -> removed++;
                case DETAIL_LOST -> lost++;
                default -> log.warn("调拨明细 {} 状态码未登记：{}", detail.getId(), detail.getStatus());
            }
        }
        vo.setTotalCount(details.size());
        vo.setPendingCount(pending);
        vo.setDispatchedCount(dispatched);
        vo.setReceivedCount(received);
        vo.setRemovedCount(removed);
        vo.setLostCount(lost);
        vo.setTimeout(isInTransitTimeout(order));
    }

    /** 站点名映射（列表页一次 IN 查询）。 */
    private Map<Long, String> loadStationNames(List<AllocationOrder> orders) {
        Set<Long> stationIds = new HashSet<>();
        for (AllocationOrder order : orders) {
            if (order.getFromStationId() != null) {
                stationIds.add(order.getFromStationId());
            }
            if (order.getToStationId() != null) {
                stationIds.add(order.getToStationId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (stationIds.isEmpty()) {
            return names;
        }
        List<Station> stations = stationMapper.selectList(
                new LambdaQueryWrapper<Station>().in(Station::getId, stationIds));
        for (Station station : stations) {
            names.put(station.getId(), station.getName());
        }
        return names;
    }

    /** 在途超时标注：仅 IN_TRANSIT 且首只出库已满 {@code sems.alloc.timeout-hours} 小时（5.3）。 */
    private boolean isInTransitTimeout(AllocationOrder order) {
        if (!ORDER_IN_TRANSIT.equals(order.getStatus()) || order.getDispatchAt() == null) {
            return false;
        }
        int timeoutHours = properties.getAlloc().getTimeoutHours();
        return !LocalDateTime.now().isBefore(order.getDispatchAt().plusHours(timeoutHours));
    }

    /** 单据状态中文名（未登记码值回落原码，供提示文案使用）。 */
    private static String orderStatusName(String status) {
        return ORDER_STATUS_NAMES.getOrDefault(status, status);
    }

    /** 明细状态中文名（未登记码值回落原码）。 */
    private static String detailStatusName(String status) {
        return DETAIL_STATUS_NAMES.getOrDefault(status, status);
    }

    // ==================== 分页与排序（私有） ====================

    private static int normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * 排序白名单（6.1）：仅接受 createdAt / dispatchAt / completedAt / abnormalAt / status，
     * 方向 asc|desc；未登记字段或格式一律回落默认「createdAt desc, id desc」——禁止把用户输入
     * 直接拼进 ORDER BY。
     */
    private static void applyOrderBy(LambdaQueryWrapper<AllocationOrder> wrapper, String orderBy) {
        String field = orderBy == null ? "" : orderBy.trim();
        boolean desc = true;
        int comma = field.indexOf(',');
        if (comma >= 0) {
            desc = !"asc".equalsIgnoreCase(field.substring(comma + 1).trim());
            field = field.substring(0, comma).trim();
        }
        switch (field) {
            case "dispatchAt" -> {
                if (desc) {
                    wrapper.orderByDesc(AllocationOrder::getDispatchAt);
                } else {
                    wrapper.orderByAsc(AllocationOrder::getDispatchAt);
                }
            }
            case "completedAt" -> {
                if (desc) {
                    wrapper.orderByDesc(AllocationOrder::getCompletedAt);
                } else {
                    wrapper.orderByAsc(AllocationOrder::getCompletedAt);
                }
            }
            case "abnormalAt" -> {
                if (desc) {
                    wrapper.orderByDesc(AllocationOrder::getAbnormalAt);
                } else {
                    wrapper.orderByAsc(AllocationOrder::getAbnormalAt);
                }
            }
            case "status" -> {
                if (desc) {
                    wrapper.orderByDesc(AllocationOrder::getStatus);
                } else {
                    wrapper.orderByAsc(AllocationOrder::getStatus);
                }
            }
            case "createdAt" -> {
                if (desc) {
                    wrapper.orderByDesc(AllocationOrder::getCreatedAt);
                } else {
                    wrapper.orderByAsc(AllocationOrder::getCreatedAt);
                }
            }
            default -> wrapper.orderByDesc(AllocationOrder::getCreatedAt);
        }
        wrapper.orderByDesc(AllocationOrder::getId);
    }
}
