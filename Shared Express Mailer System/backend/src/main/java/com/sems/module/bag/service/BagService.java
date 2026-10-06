package com.sems.module.bag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.common.PageResult;
import com.sems.common.util.JsonUtils;
import com.sems.common.util.MaskUtils;
import com.sems.config.SemsProperties;
import com.sems.infra.IdempotentService;
import com.sems.infra.RateLimitService;
import com.sems.module.audit.entity.AuditLogEntity;
import com.sems.module.audit.service.AuditLogService;
import com.sems.module.bag.dto.ActivateDto;
import com.sems.module.bag.dto.BagDetailVo;
import com.sems.module.bag.dto.BagInfoVo;
import com.sems.module.bag.dto.BagQuery;
import com.sems.module.bag.dto.BagTraceVo;
import com.sems.module.bag.dto.BatchGenerateDto;
import com.sems.module.bag.dto.CreateBagDto;
import com.sems.module.bag.dto.LostRecoveryDto;
import com.sems.module.bag.dto.RepairCompleteDto;
import com.sems.module.bag.dto.RepairDto;
import com.sems.module.bag.dto.ScrapDto;
import com.sems.module.bag.dto.StatusCorrectDto;
import com.sems.module.bag.dto.StockInDto;
import com.sems.module.bag.dto.StockOutDto;
import com.sems.module.bag.dto.UnusedReturnDto;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.entity.BagTransferLog;
import com.sems.module.bag.entity.StockRecord;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.bag.mapper.BagTransferLogMapper;
import com.sems.module.bag.mapper.StockRecordMapper;
import com.sems.module.bag.service.action.BagTransitionActions;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.station.entity.AllocationDetail;
import com.sems.module.station.entity.AllocationOrder;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.AllocationDetailMapper;
import com.sems.module.station.mapper.AllocationOrderMapper;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.statemachine.BagTransitionResult;
import com.sems.module.statemachine.BagTransitionService;
import com.sems.module.statemachine.OrderStatus;
import com.sems.module.statemachine.TransitionCommand;
import com.sems.module.user.entity.SysUser;
import com.sems.module.user.mapper.SysUserMapper;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 快递袋管理服务（M4）：档案建档/批量生成、台账与追溯查询、激活/出入库、维修报废、
 * 遗失找回与盘点状态修正（见《系统设计说明书》5.4 与 3.2 状态转移表）。
 *
 * <p><b>与状态机的关系</b>：所有状态变化一律经 {@link BagTransitionService#fire} 执行
 * （袋状态的唯一写入口）；本类只做「入参校验 → 数据权限（本站）/调拨锁定（4110）/复用超限（4108）
 * 等前置校验 → 触发流转 → 组装出参」，伴随动作（记录写入、Redis 增减、订单联动）
 * 由 {@link BagTransitionActions} 在状态机事务内完成。
 *
 * <p><b>批量语义</b>：上架入库与出库逐袋独立成败（单只失败不影响其余），因此批量方法
 * <b>不加</b> {@code @Transactional}：若把整批放进一个事务，某袋被状态机拒绝时 Spring 会把
 * 共享事务标记为 rollback-only（内层 {@code fire} 的 @Transactional 参与外层事务），
 * 后续袋与最终提交都会失败，与"逐袋部分成功"的接口语义冲突；每袋的原子性由
 * {@code fire} 自身的单袋事务保证（设计书 3.2 表后说明）。
 *
 * <p><b>写接口幂等</b>：全部写接口按契约模块版第 4 节范式使用 {@code clientRequestId}
 * （10 分钟窗口，命中回放首次结果）；批量接口另在状态机内按 {@code clientRequestId + bagCode}
 * 逐袋幂等；出库不走状态机，由本类逐袋调用 {@link IdempotentService#tryAcquireBag} 去重。
 * 查询袋码的失败限流见 {@link #info(String)}（设计书 7.4 防枚举）。
 */
@Service
public class BagService {

    private static final Logger log = LoggerFactory.getLogger(BagService.class);

    // ===== 幂等 apiKey：HTTP 方法 + 路径模板，全站唯一（契约模块版第 4 节） =====
    private static final String API_CREATE = "POST /bags";
    private static final String API_BATCH_GENERATE = "POST /bags/batch-generate";
    private static final String API_ACTIVATE = "POST /bags/{bagCode}/activate";
    private static final String API_STOCK_IN = "POST /bags/stock-in";
    private static final String API_UNUSED_RETURN = "POST /bags/{bagCode}/unused-return";
    private static final String API_STOCK_OUT = "POST /bags/stock-out";
    private static final String API_REPAIR = "POST /bags/{bagCode}/repair";
    private static final String API_REPAIR_COMPLETE = "POST /bags/{bagCode}/repair-complete";
    private static final String API_SCRAP = "POST /bags/{bagCode}/scrap";
    private static final String API_LOST_RECOVERY = "POST /bags/{bagCode}/lost-recovery";
    private static final String API_STATUS_CORRECT = "POST /bags/{bagCode}/status-correct";

    /** 新建袋的默认复用上限（设计书 3.5「复用次数上限」：默认 50，可按袋落于 {@code bag.max_use_count}）。 */
    private static final int DEFAULT_MAX_USE_COUNT = 50;

    /** 扫码查询失败限流（设计书 7.4 信息最小化防枚举）：同一用户 1 分钟内失败达 10 次即触发 5101。 */
    private static final int SCAN_FAIL_LIMIT = 10;
    private static final Duration SCAN_FAIL_WINDOW = Duration.ofMinutes(1);
    private static final String SCAN_FAIL_API = "bag-info-fail";

    /** 分页口径（设计书 6.1：默认 10、最大 100）。 */
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    /** 调拨单/明细状态码值（与 DDL 注释一致）：未出库调拨单锁定袋的口径见设计书 3.2 #2 与契约模块版 8.4。 */
    private static final String ALLOCATION_DETAIL_PENDING_DISPATCH = "PENDING_DISPATCH";
    private static final String ALLOCATION_ORDER_PENDING_DISPATCH = "PENDING_DISPATCH";
    private static final String ALLOCATION_ORDER_IN_TRANSIT = "IN_TRANSIT";

    /** 状态修正目标态白名单（设计书 3.2 #21）。 */
    private static final Set<BagStatus> STATUS_CORRECT_TARGETS =
            EnumSet.of(BagStatus.IDLE, BagStatus.REPAIRING, BagStatus.LOST, BagStatus.SCRAPPED);

    /** 出库一律拒绝的在循环状态（需求 FR-M4-10：「对维修中或正常在用袋调用出库被拒」）。 */
    private static final Set<BagStatus> CIRCULATING_STATES =
            EnumSet.of(BagStatus.ALLOCATED, BagStatus.IN_USE, BagStatus.RETURNING, BagStatus.TRANSFERRING);

    /** 审计模块名（与 Controller 上的 @AuditLog module 一致）。 */
    private static final String AUDIT_MODULE = "快递袋管理";

    private final BagMapper bagMapper;
    private final BagTransferLogMapper transferLogMapper;
    private final StockRecordMapper stockRecordMapper;
    private final BagOrderMapper bagOrderMapper;
    private final StationMapper stationMapper;
    private final AllocationDetailMapper allocationDetailMapper;
    private final AllocationOrderMapper allocationOrderMapper;
    private final SysUserMapper sysUserMapper;
    private final BagTransitionService bagTransitionService;
    private final IdempotentService idempotentService;
    private final RateLimitService rateLimitService;
    private final AuditLogService auditLogService;
    private final SemsProperties properties;
    private final BagCodeGenerator bagCodeGenerator;
    private final QrCodeService qrCodeService;

    public BagService(BagMapper bagMapper,
                      BagTransferLogMapper transferLogMapper,
                      StockRecordMapper stockRecordMapper,
                      BagOrderMapper bagOrderMapper,
                      StationMapper stationMapper,
                      AllocationDetailMapper allocationDetailMapper,
                      AllocationOrderMapper allocationOrderMapper,
                      SysUserMapper sysUserMapper,
                      BagTransitionService bagTransitionService,
                      IdempotentService idempotentService,
                      RateLimitService rateLimitService,
                      AuditLogService auditLogService,
                      SemsProperties properties,
                      BagCodeGenerator bagCodeGenerator,
                      QrCodeService qrCodeService) {
        this.bagMapper = bagMapper;
        this.transferLogMapper = transferLogMapper;
        this.stockRecordMapper = stockRecordMapper;
        this.bagOrderMapper = bagOrderMapper;
        this.stationMapper = stationMapper;
        this.allocationDetailMapper = allocationDetailMapper;
        this.allocationOrderMapper = allocationOrderMapper;
        this.sysUserMapper = sysUserMapper;
        this.bagTransitionService = bagTransitionService;
        this.idempotentService = idempotentService;
        this.rateLimitService = rateLimitService;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.bagCodeGenerator = bagCodeGenerator;
        this.qrCodeService = qrCodeService;
    }

    // ==================== 查询 ====================

    /**
     * 袋台账分页查询（5.4：站点/平台管理员；按状态/站点/编码筛选）。
     *
     * <p>本站站点管理员强制过滤 {@code station_id = 操作人站点}（设计书 4.4），
     * 前端传入的 stationId 只能收窄、不能越界（越界报 2002）。
     */
    public PageResult<BagDetailVo> page(BagQuery query) {
        LoginUser user = AuthContext.require();
        LambdaQueryWrapper<Bag> wrapper = new LambdaQueryWrapper<>();
        if (Constants.ROLE_PLATFORM_ADMIN.equals(user.getRole())) {
            if (query != null && query.getStationId() != null) {
                wrapper.eq(Bag::getStationId, query.getStationId());
            }
        } else {
            Long ownStationId = user.getStationId();
            if (ownStationId == null) {
                throw new BizException(ErrorCode.NO_DATA_PERMISSION, "当前账号未绑定站点，无法查询袋台账");
            }
            if (query != null && query.getStationId() != null && !ownStationId.equals(query.getStationId())) {
                throw new BizException(ErrorCode.NO_DATA_PERMISSION, "仅能查询本站点的袋台账");
            }
            wrapper.eq(Bag::getStationId, ownStationId);
        }
        if (query != null) {
            if (query.getStatus() != null && !query.getStatus().isBlank()) {
                wrapper.eq(Bag::getStatus, parseStatus(query.getStatus()).code());
            }
            if (query.getBagCode() != null && !query.getBagCode().isBlank()) {
                wrapper.like(Bag::getBagCode, query.getBagCode().trim());
            }
            if (query.getSpec() != null && !query.getSpec().isBlank()) {
                wrapper.eq(Bag::getSpec, query.getSpec().trim());
            }
            if (query.getBeginTime() != null) {
                wrapper.ge(Bag::getCreatedAt, query.getBeginTime());
            }
            if (query.getEndTime() != null) {
                wrapper.le(Bag::getCreatedAt, query.getEndTime());
            }
        }
        applyOrderBy(wrapper, query == null ? null : query.getOrderBy());

        Page<Bag> page = bagMapper.selectPage(new Page<>(normalizePageNum(query), normalizePageSize(query)), wrapper);
        Map<Long, String> stationNames = stationNameMap(page.getRecords().stream().map(Bag::getStationId).toList());
        List<BagDetailVo> records = new ArrayList<>(page.getRecords().size());
        for (Bag bag : page.getRecords()) {
            records.add(toDetailVo(bag, stationNames.get(bag.getStationId())));
        }
        return PageResult.of(records, page.getTotal(), page.getSize());
    }

    /**
     * 扫码查询袋信息（5.2：登录用户；7.4 信息最小化与防枚举）。
     *
     * <p>不可用状态（未激活/已遗失/已报废）抛 4107 并按状态区分文案；与查询者有归属关系
     * （本人持该袋进行中订单、本站站点管理员、平台管理员）时返回扩展字段，否则只回最小必要集。
     * 同一用户 1 分钟内查询失败达 {@value #SCAN_FAIL_LIMIT} 次触发 5101 限额并补记风控审计。
     */
    public BagInfoVo info(String bagCode) {
        LoginUser user = AuthContext.require();
        Bag bag = findBagByCode(bagCode);
        if (bag == null) {
            recordScanFailure(user);
            throw new BizException(ErrorCode.BAG_NOT_FOUND, "袋码不存在，请核对后重试");
        }
        BagStatus status = BagStatus.of(bag.getStatus());
        if (status == BagStatus.INACTIVE || status == BagStatus.LOST || status == BagStatus.SCRAPPED) {
            recordScanFailure(user);
            throw new BizException(ErrorCode.BAG_UNAVAILABLE, unavailableMessage(status));
        }

        boolean available = status == BagStatus.IDLE && !isLockedByAllocation(bag.getBagCode());
        boolean fullView = isPlatformAdmin()
                || (Constants.ROLE_STATION_ADMIN.equals(user.getRole())
                    && user.getStationId() != null
                    && user.getStationId().equals(bag.getStationId()))
                || hasActiveOrder(user.getUserId(), bag.getBagCode());

        BagInfoVo vo = new BagInfoVo();
        vo.setBagCode(bag.getBagCode());
        vo.setSpec(bag.getSpec());
        vo.setAvailable(available);
        Station station = bag.getStationId() == null ? null : stationMapper.selectById(bag.getStationId());
        vo.setStationName(station == null ? null : station.getName());
        if (fullView) {
            vo.setStationId(bag.getStationId());
            vo.setColor(bag.getColor());
            vo.setCapacity(bag.getCapacity());
            vo.setStatus(bag.getStatus());
            vo.setStatusName(status.displayName());
            vo.setUseCount(bag.getUseCount());
            vo.setMaxUseCount(bag.getMaxUseCount());
        }
        return vo;
    }

    /** 单只袋二维码 PNG（5.4：站点/平台管理员，标签补打用；站管限本站袋）。 */
    public byte[] qrCodePng(String bagCode) {
        Bag bag = loadBagOrThrow(bagCode);
        assertStationAccess(bag.getStationId());
        return qrCodeService.generatePng(bag.getBagCode());
    }

    /** 袋全生命周期流转日志（5.4：追溯用；站管限本站袋），操作人/站点名称批量联查补全。 */
    public List<BagTraceVo> trace(String bagCode) {
        Bag bag = loadBagOrThrow(bagCode);
        assertStationAccess(bag.getStationId());
        List<BagTransferLog> logs = transferLogMapper.selectList(new LambdaQueryWrapper<BagTransferLog>()
                .eq(BagTransferLog::getBagCode, bagCode)
                .orderByAsc(BagTransferLog::getCreatedAt)
                .orderByAsc(BagTransferLog::getId));
        Map<Long, String> operatorNames = userNameMap(logs.stream().map(BagTransferLog::getOperatorId).toList());
        Map<Long, String> stationNames = stationNameMap(logs.stream().map(BagTransferLog::getStationId).toList());
        List<BagTraceVo> traceVos = new ArrayList<>(logs.size());
        for (BagTransferLog transferLog : logs) {
            traceVos.add(toTraceVo(transferLog, operatorNames, stationNames));
        }
        return traceVos;
    }

    // ==================== 建档与批量生成 ====================

    /** 新建袋档案（5.4：平台管理员；编码自动生成、初始状态=未激活、写登记站点）。 */
    @Transactional(rollbackFor = Exception.class)
    public BagDetailVo create(CreateBagDto dto, String clientRequestId) {
        return executeIdempotent(API_CREATE, clientRequestId,
                json -> JsonUtils.parse(json, BagDetailVo.class),
                () -> {
                    Station station = requireEnabledStation(dto.getStationId());
                    String bagCode = bagCodeGenerator.next(dto.getSpec());
                    Bag bag = newBag(bagCode, dto.getSpec(), dto.getColor(), dto.getCapacity(), station.getId());
                    bagMapper.insert(bag);
                    // 回读一次：createdAt/updatedAt 由库端默认值填充，直接在响应里回显完整档案
                    Bag saved = bagMapper.selectById(bag.getId());
                    return toDetailVo(saved, station.getName());
                });
    }

    /**
     * 批量生成袋档案并打包二维码 PNG（5.4：平台管理员；整批原子、≤{@code sems.batch.max-size}）。
     *
     * <p>返回 ZIP：每只袋一个以袋码命名的 PNG + 一个 {@code manifest.txt}（逐行列出全部袋码）。
     * 幂等回放不缓存 ZIP 二进制，只记首次袋码清单，命中时按清单重建同内容压缩包
     * （见契约模块版第 4 节：首次结果摘要入 Redis，二进制流不入）。
     */
    @Transactional(rollbackFor = Exception.class)
    public byte[] batchGenerate(BatchGenerateDto dto, String clientRequestId) {
        int maxSize = properties.getBatch().getMaxSize();
        if (dto.getCount() == null || dto.getCount() < 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "生成数量须大于 0");
        }
        if (dto.getCount() > maxSize) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单批最多生成 " + maxSize + " 只，请分批处理");
        }
        assertClientRequestId(clientRequestId);
        String cached = idempotentService.findCompleted(API_BATCH_GENERATE, clientRequestId);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            GeneratedBatch recorded = JsonUtils.parse(cached, GeneratedBatch.class);
            return buildZip(reloadBags(recorded.codes()));
        }
        idempotentService.markProcessing(API_BATCH_GENERATE, clientRequestId);
        try {
            Station station = requireEnabledStation(dto.getStationId());
            List<String> codes = bagCodeGenerator.nextBatch(dto.getSpec(), dto.getCount());
            List<Bag> bags = new ArrayList<>(codes.size());
            for (String code : codes) {
                Bag bag = newBag(code, dto.getSpec(), dto.getColor(), dto.getCapacity(), station.getId());
                bagMapper.insert(bag);
                bags.add(bag);
            }
            byte[] zip = buildZip(bags);
            idempotentService.markCompleted(API_BATCH_GENERATE, clientRequestId,
                    JsonUtils.toJson(new GeneratedBatch(codes)));
            return zip;
        } catch (RuntimeException e) {
            // 任何失败立即删占位，保证客户端重试不被自己的"处理中"挡死（9.6）
            idempotentService.clear(API_BATCH_GENERATE, clientRequestId);
            throw e;
        }
    }

    // ==================== 状态流转类写接口（单袋） ====================

    /** 激活上架（5.4 / 3.2 #1）：袋归属站点=操作人站点且站点启用；重复激活由状态机拒绝（4102）。 */
    @Transactional(rollbackFor = Exception.class)
    public BagDetailVo activate(String bagCode, ActivateDto dto, String clientRequestId) {
        return executeIdempotent(API_ACTIVATE, clientRequestId,
                json -> JsonUtils.parse(json, BagDetailVo.class),
                () -> {
                    Bag bag = loadBagOrThrow(bagCode);
                    assertStationAccess(bag.getStationId());
                    assertStationEnabled(bag.getStationId(), "激活上架");
                    bagTransitionService.fire(newCommand(bagCode, BagEvent.ACTIVATE, bag.getStationId(), isPlatformAdmin())
                            .remark(dto == null ? null : dto.getRemark())
                            .build());
                    return detailOf(bagCode);
                });
    }

    /**
     * 未使用回收（5.4 / 3.2 #4）：已分配→空闲，前置=绑定订单已取消或不存在。
     * 站点归属在状态机内改为确认站点（本站操作人站点）；库存 +1 与在持袋数 −1
     * 由 station 模块的伴随动作完成（事件归属见契约模块版第 2 节）。
     */
    @Transactional(rollbackFor = Exception.class)
    public BagDetailVo unusedReturn(String bagCode, UnusedReturnDto dto, String clientRequestId) {
        return executeIdempotent(API_UNUSED_RETURN, clientRequestId,
                json -> JsonUtils.parse(json, BagDetailVo.class),
                () -> {
                    Bag bag = loadBagOrThrow(bagCode);
                    assertStationAccess(bag.getStationId());
                    BagOrder latestOrder = latestOrderOfBag(bagCode);
                    if (latestOrder != null && !OrderStatus.CANCELLED.code().equals(latestOrder.getStatus())) {
                        throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                                "绑定订单状态为「" + orderStatusName(latestOrder.getStatus())
                                        + "」，未使用回收要求订单已取消或不存在");
                    }
                    bagTransitionService.fire(newCommand(bagCode, BagEvent.RECOVER_UNUSED, bag.getStationId(), isPlatformAdmin())
                            .remark(dto == null ? null : dto.getRemark())
                            .build());
                    return detailOf(bagCode);
                });
    }

    /** 维修登记（5.4 / 3.2 #12）：破损描述必填；袋被未出库调拨单引用时报 4110；前态=空闲时库存 −1（动作内）。 */
    @Transactional(rollbackFor = Exception.class)
    public BagDetailVo repair(String bagCode, RepairDto dto, String clientRequestId) {
        return executeIdempotent(API_REPAIR, clientRequestId,
                json -> JsonUtils.parse(json, BagDetailVo.class),
                () -> {
                    Bag bag = loadBagOrThrow(bagCode);
                    assertStationAccess(bag.getStationId());
                    assertNotLockedByAllocation(bagCode);
                    bagTransitionService.fire(newCommand(bagCode, BagEvent.REPAIR_START, bag.getStationId(), isPlatformAdmin())
                            .remark(dto.getDescription())
                            .build());
                    return detailOf(bagCode);
                });
    }

    /**
     * 维修完成（5.4 / 3.2 #13/#14）：结果=修复→可复用；结果=无法修复→报废；
     * 已修复但 {@code use_count ≥ max_use_count} 时按需求 FR-M4-13 备选流 6a 强制走 #14 报废。
     *
     * <p>走 #14 时补记一条 action="报废" 的审计：本接口注解固定为「维修完成」，
     * 而报废属审计重点核查项（设计书 7.6），需要独立动作名供审计按 action 筛出。
     */
    @Transactional(rollbackFor = Exception.class)
    public BagDetailVo repairComplete(String bagCode, RepairCompleteDto dto, String clientRequestId) {
        return executeIdempotent(API_REPAIR_COMPLETE, clientRequestId,
                json -> JsonUtils.parse(json, BagDetailVo.class),
                () -> {
                    Bag bag = loadBagOrThrow(bagCode);
                    assertStationAccess(bag.getStationId());
                    boolean repaired = BagTransitionActions.REPAIR_RESULT_FIXED.equals(dto.getResult());
                    boolean overLimit = isUseCountExceeded(bag);
                    if (repaired && !overLimit) {
                        bagTransitionService.fire(newCommand(bagCode, BagEvent.REPAIR_DONE, bag.getStationId(), isPlatformAdmin())
                                .remark(dto.getRemark())
                                .attributes(Map.<String, Object>of(BagTransitionActions.ATTR_REPAIR_RESULT, dto.getResult()))
                                .build());
                    } else {
                        String reason = repaired
                                ? "复用次数已达上限（" + safeUseCount(bag) + "/" + safeMaxUseCount(bag) + "），修复后仍须报废"
                                : fallbackText(dto.getRemark(), "维修结果：无法修复");
                        bagTransitionService.fire(newCommand(bagCode, BagEvent.SCRAP, bag.getStationId(), isPlatformAdmin())
                                .remark(reason)
                                .attributes(Map.<String, Object>of(BagTransitionActions.ATTR_REPAIR_RESULT, dto.getResult()))
                                .build());
                        writeSupplementAudit("报废", "袋码 " + bagCode + "：#14 报废（" + reason + "）");
                    }
                    return detailOf(bagCode);
                });
    }

    /** 直接报废登记（5.4 / 3.2 #15）：原因必填；袋被未出库调拨单引用时报 4110；审计重点核查项。 */
    @Transactional(rollbackFor = Exception.class)
    public BagDetailVo scrap(String bagCode, ScrapDto dto, String clientRequestId) {
        return executeIdempotent(API_SCRAP, clientRequestId,
                json -> JsonUtils.parse(json, BagDetailVo.class),
                () -> {
                    Bag bag = loadBagOrThrow(bagCode);
                    assertStationAccess(bag.getStationId());
                    assertNotLockedByAllocation(bagCode);
                    bagTransitionService.fire(newCommand(bagCode, BagEvent.SCRAP_DIRECT, bag.getStationId(), isPlatformAdmin())
                            .remark(dto.getReason())
                            .build());
                    return detailOf(bagCode);
                });
    }

    /** 遗失找回（5.4 / 3.2 #20，平台管理员）：说明与找回站点必填；袋归属改为找回站点、转待消毒。 */
    @Transactional(rollbackFor = Exception.class)
    public BagDetailVo lostRecovery(String bagCode, LostRecoveryDto dto, String clientRequestId) {
        return executeIdempotent(API_LOST_RECOVERY, clientRequestId,
                json -> JsonUtils.parse(json, BagDetailVo.class),
                () -> {
                    loadBagOrThrow(bagCode);
                    Station station = stationMapper.selectById(dto.getStationId());
                    if (station == null) {
                        throw new BizException(ErrorCode.PARAM_INVALID, "找回站点不存在");
                    }
                    bagTransitionService.fire(newCommand(bagCode, BagEvent.FOUND, dto.getStationId(), false)
                            .remark(dto.getRemark())
                            .targetStationId(dto.getStationId())
                            .build());
                    return detailOf(bagCode);
                });
    }

    /**
     * 状态修正（5.4 / 3.2 #21，平台管理员）：目标态白名单 {空闲, 维修中, 已遗失, 报废}；
     * 差异单号与修正原因必填；袋被未出库调拨单引用时报 4110；未指定目标站点即视为同站修正。
     *
     * <p>库存两站增减、订单闭环与业务记录（维修/报废/责任）写入由伴随动作按 #21 公式完成；
     * 流转日志的 station_id 记为操作站点（事件发生地）＝修正前的袋归属站点（设计书 8.2 该列
     * 定义为「操作站点（事件发生地）」，7.4 又规定流转地点以该列为唯一数据来源）；
     * 跨站修正的落定地由 {@code targetStationId} 交由状态机同步到 {@code bag.station_id}。
     */
    @Transactional(rollbackFor = Exception.class)
    public BagDetailVo statusCorrect(String bagCode, StatusCorrectDto dto, String clientRequestId) {
        return executeIdempotent(API_STATUS_CORRECT, clientRequestId,
                json -> JsonUtils.parse(json, BagDetailVo.class),
                () -> {
                    Bag bag = loadBagOrThrow(bagCode);
                    BagStatus target = parseStatus(dto.getTargetStatus());
                    if (!STATUS_CORRECT_TARGETS.contains(target)) {
                        throw new BizException(ErrorCode.BAG_ILLEGAL_TRANSITION,
                                "状态修正目标态不在白名单内（仅支持 空闲/维修中/已遗失/报废）");
                    }
                    assertNotLockedByAllocation(bagCode);

                    Long sourceStation = bag.getStationId();
                    Long targetStation = dto.getTargetStationId() != null ? dto.getTargetStationId() : sourceStation;
                    if (!Objects.equals(sourceStation, targetStation) && stationMapper.selectById(targetStation) == null) {
                        throw new BizException(ErrorCode.PARAM_INVALID, "跨站修正指定的目标站点不存在");
                    }
                    // 操作站点（事件发生地）取修正前的归属站点：跨站修正时流转日志记事发地、
                    // 目标落定地走 targetStationId（设计书 8.2 的列定义与 7.4 的追溯口径）
                    bagTransitionService.fire(newCommand(bagCode, BagEvent.STATUS_CORRECT, sourceStation, false)
                            .remark(dto.getRemark())
                            .refCheckNo(dto.getRefCheckNo())
                            .targetStatus(target)
                            .targetStationId(targetStation)
                            .build());
                    return detailOf(bagCode);
                });
    }

    // ==================== 批量写接口（逐袋部分成功） ====================

    /**
     * 上架入库（批量）（5.4 / 3.2 #11）：逐袋 可复用→空闲，库存 +1、写入库流水（动作内）；
     * {@code use_count ≥ max_use_count} 的袋返回 4108、状态不变（需求 FR-M4-08）。
     *
     * <p>整批不加事务（见类注释）：每袋的原子性由状态机单袋事务保证；逐袋幂等由
     * 状态机按 {@code clientRequestId + bagCode} 完成（契约模块版第 4 节）。
     */
    public List<BatchItem> stockIn(StockInDto dto, String clientRequestId) {
        assertBatchSize(dto.getBags(), "上架入库");
        return executeIdempotent(API_STOCK_IN, clientRequestId,
                json -> JsonUtils.parseList(json, BatchItem.class),
                () -> {
                    List<BatchItem> items = new ArrayList<>(dto.getBags().size());
                    for (StockInDto.Item item : dto.getBags()) {
                        String bagCode = item.getBagCode();
                        try {
                            Bag bag = loadBagOrThrow(bagCode);
                            assertStationAccess(bag.getStationId());
                            if (isUseCountExceeded(bag)) {
                                throw new BizException(ErrorCode.USE_COUNT_EXCEEDED,
                                        "复用次数已达上限（" + safeUseCount(bag) + "/" + safeMaxUseCount(bag)
                                                + "），请转维修或报废");
                            }
                            BagTransitionResult result = bagTransitionService.fire(
                                    newCommand(bagCode, BagEvent.SHELVE, bag.getStationId(), isPlatformAdmin())
                                            .remark(item.getRemark())
                                            .clientRequestId(clientRequestId)
                                            .build());
                            items.add(new BatchItem(bagCode, true, 0,
                                    result.isReplayed() ? "幂等回放：该袋已处理" : "上架入库成功"));
                        } catch (BizException e) {
                            // 单只失败不影响其余：逐袋收集错误码与文案（契约模块版第 5 节）
                            items.add(new BatchItem(bagCode, false, e.getErrorCode().getCode(), e.getMessage()));
                        }
                    }
                    return items;
                });
    }

    /**
     * 出库登记（批量）（5.4、需求 FR-M4-10/UC-S04）：仅用于报废袋实物出库与经批准的盘点差异出库，
     * 只写 {@code stock_record} 流水、登记实物离站，不重复扣减库存（库存增减只由状态转移表的事件触发）。
     *
     * <p>逐袋白名单：报废袋→SCRAP_OUT；非报废袋须携带盘点差异单号→OUT（无单号 4105）；
     * 维修中袋拒绝并引导走维修接口；在循环中的袋（已分配/使用中/回收在途/调拨在途）一律拒绝。
     * 本接口不经过状态机，逐袋幂等由 {@link IdempotentService#tryAcquireBag} 显式承担。
     */
    public List<BatchItem> stockOut(StockOutDto dto, String clientRequestId) {
        assertBatchSize(dto.getBags(), "出库");
        return executeIdempotent(API_STOCK_OUT, clientRequestId,
                json -> JsonUtils.parseList(json, BatchItem.class),
                () -> {
                    List<BatchItem> items = new ArrayList<>(dto.getBags().size());
                    for (StockOutDto.Item item : dto.getBags()) {
                        String bagCode = item.getBagCode();
                        try {
                            Bag bag = loadBagOrThrow(bagCode);
                            assertStationAccess(bag.getStationId());
                            BagStatus status = BagStatus.of(bag.getStatus());
                            if (status == BagStatus.REPAIRING) {
                                throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                                        "维修中的袋不得出库，请走维修完成流程（repair-complete）");
                            }
                            if (CIRCULATING_STATES.contains(status)) {
                                throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                                        "在循环中的袋（已分配/使用中/回收在途/调拨在途）不得出库");
                            }
                            boolean scrapped = status == BagStatus.SCRAPPED;
                            if (!scrapped && (item.getRefCheckNo() == null || item.getRefCheckNo().isBlank())) {
                                throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                                        "非报废袋出库须填写经批准的盘点差异单号");
                            }
                            // 校验通过后才占用逐袋幂等键：校验失败不落键，重试可正常复检
                            if (!idempotentService.tryAcquireBag(clientRequestId, bagCode)) {
                                items.add(new BatchItem(bagCode, true, 0, "幂等回放：该袋已处理"));
                                continue;
                            }
                            writeStockOutRecord(bag, item, scrapped);
                            items.add(new BatchItem(bagCode, true, 0,
                                    scrapped ? "报废袋出库登记成功" : "盘点差异出库登记成功"));
                        } catch (BizException e) {
                            items.add(new BatchItem(bagCode, false, e.getErrorCode().getCode(), e.getMessage()));
                        }
                    }
                    return items;
                });
    }

    // ==================== 私有：写流水与装配 ====================

    /** 写出库流水：报废袋记 SCRAP_OUT、差异出库记 OUT；备注拼接出库原因与差异单号。 */
    private void writeStockOutRecord(Bag bag, StockOutDto.Item item, boolean scrapped) {
        String baseReason = item.getReason() != null && !item.getReason().isBlank()
                ? item.getReason()
                : (scrapped ? "报废移交" : "盘点差异出库");
        String refCheckNo = item.getRefCheckNo();
        String remark = refCheckNo != null && !refCheckNo.isBlank()
                ? baseReason + "（差异单号 " + refCheckNo + "）"
                : baseReason;

        StockRecord record = new StockRecord();
        record.setStationId(bag.getStationId());
        record.setBagCode(bag.getBagCode());
        record.setType(scrapped ? BagTransitionActions.STOCK_TYPE_SCRAP_OUT : BagTransitionActions.STOCK_TYPE_OUT);
        record.setOperatorId(AuthContext.require().getUserId());
        // 列宽 varchar(200)：truncate 超长会追加省略号，按 199 截断保证总长不越界
        record.setRemark(MaskUtils.truncate(remark, 199));
        record.setCreatedAt(LocalDateTime.now());
        stockRecordMapper.insert(record);
    }

    /** 组装流转命令骨架（操作人取自登录态）；调用方按事件语义补 remark/refCheckNo/targetStatus 等。 */
    private TransitionCommand.TransitionCommandBuilder newCommand(String bagCode, BagEvent event,
                                                                  Long stationId, boolean proxy) {
        LoginUser user = AuthContext.require();
        return TransitionCommand.builder()
                .bagCode(bagCode)
                .event(event)
                .operatorId(user.getUserId())
                .operatorRole(user.getRole())
                .stationId(stationId)
                .proxy(proxy);
    }

    /** 新建袋实体（编码/规格/颜色/容量/登记站点 + 初始未激活 + 默认复用上限）。 */
    private static Bag newBag(String bagCode, String spec, String color, String capacity, Long stationId) {
        Bag bag = new Bag();
        bag.setBagCode(bagCode);
        bag.setSpec(spec);
        bag.setColor(color);
        bag.setCapacity(capacity);
        bag.setStationId(stationId);
        bag.setStatus(BagStatus.INACTIVE.code());
        bag.setUseCount(0);
        bag.setMaxUseCount(DEFAULT_MAX_USE_COUNT);
        return bag;
    }

    /** 回读袋并组装详情出参（写操作统一返回袋的当前完整档案）。 */
    private BagDetailVo detailOf(String bagCode) {
        Bag bag = loadBagOrThrow(bagCode);
        Station station = bag.getStationId() == null ? null : stationMapper.selectById(bag.getStationId());
        return toDetailVo(bag, station == null ? null : station.getName());
    }

    private static BagDetailVo toDetailVo(Bag bag, String stationName) {
        BagDetailVo vo = new BagDetailVo();
        vo.setId(bag.getId());
        vo.setBagCode(bag.getBagCode());
        vo.setSpec(bag.getSpec());
        vo.setColor(bag.getColor());
        vo.setCapacity(bag.getCapacity());
        vo.setStationId(bag.getStationId());
        vo.setStationName(stationName);
        vo.setStatus(bag.getStatus());
        vo.setStatusName(statusName(bag.getStatus()));
        vo.setUseCount(bag.getUseCount());
        vo.setMaxUseCount(bag.getMaxUseCount());
        vo.setVersion(bag.getVersion());
        vo.setCreatedAt(bag.getCreatedAt());
        vo.setUpdatedAt(bag.getUpdatedAt());
        return vo;
    }

    private static BagTraceVo toTraceVo(BagTransferLog log, Map<Long, String> operatorNames, Map<Long, String> stationNames) {
        BagTraceVo vo = new BagTraceVo();
        vo.setId(log.getId());
        vo.setFromStatus(log.getFromStatus());
        vo.setFromStatusName(statusName(log.getFromStatus()));
        vo.setToStatus(log.getToStatus());
        vo.setToStatusName(statusName(log.getToStatus()));
        vo.setEvent(log.getEvent());
        vo.setEventName(eventName(log.getEvent()));
        vo.setOperatorId(log.getOperatorId());
        vo.setOperatorName(operatorNames.get(log.getOperatorId()));
        vo.setStationId(log.getStationId());
        vo.setStationName(stationNames.get(log.getStationId()));
        vo.setProxyFlag(log.getProxyFlag());
        vo.setRefCheckNo(log.getRefCheckNo());
        vo.setRemark(log.getRemark());
        vo.setCreatedAt(log.getCreatedAt());
        return vo;
    }

    // ==================== 私有：幂等与校验 ====================

    /** 写接口幂等范式（契约模块版第 4 节）：命中已完成→回放；命中处理中→可重试提示；失败→删占位。 */
    private <T> T executeIdempotent(String apiKey, String clientRequestId,
                                    Function<String, T> replayParser, Supplier<T> business) {
        assertClientRequestId(clientRequestId);
        String cached = idempotentService.findCompleted(apiKey, clientRequestId);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            return replayParser.apply(cached);
        }
        idempotentService.markProcessing(apiKey, clientRequestId);
        try {
            T result = business.get();
            idempotentService.markCompleted(apiKey, clientRequestId, JsonUtils.toJson(result));
            return result;
        } catch (RuntimeException e) {
            idempotentService.clear(apiKey, clientRequestId);
            throw e;
        }
    }

    /** 幂等头校验：缺失或非 UUID 一律 3001（契约模块版第 4 节）。 */
    private static void assertClientRequestId(String clientRequestId) {
        if (clientRequestId == null || !clientRequestId.matches(Constants.UUID_REGEX)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少或非法的 clientRequestId 请求头");
        }
    }

    /**
     * 批量数量校验：上限读 {@code sems.batch.max-size}，超限报 3001 并给出上限值（6.2）；
     * 顺带拒绝空元素（{@code {"bags":[null]}} 类畸形清单）——JSR-380 不校验 List 的 null 元素，
     * 放到循环里取袋码会变成 NPE → 500，按 6.6「参数问题一律 400+3001」在入口拦截。
     */
    private void assertBatchSize(List<?> bags, String operation) {
        int maxSize = properties.getBatch().getMaxSize();
        if (bags == null || bags.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, operation + "袋码清单不能为空");
        }
        if (bags.size() > maxSize) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    operation + "单批最多 " + maxSize + " 只，当前 " + bags.size() + " 只，请分批提交");
        }
        for (Object bag : bags) {
            if (bag == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, operation + "清单存在空元素，请检查后重试");
            }
        }
    }

    /** 装载袋行；不存在报 4106（校验顺序：先资源存在性，后归属，见契约模块版第 6 节）。 */
    private Bag loadBagOrThrow(String bagCode) {
        Bag bag = findBagByCode(bagCode);
        if (bag == null) {
            throw new BizException(ErrorCode.BAG_NOT_FOUND, "袋码不存在：" + bagCode);
        }
        return bag;
    }

    private Bag findBagByCode(String bagCode) {
        if (bagCode == null || bagCode.isBlank()) {
            return null;
        }
        return bagMapper.selectOne(new LambdaQueryWrapper<Bag>().eq(Bag::getBagCode, bagCode));
    }

    /** 数据权限（水平越权，设计书 4.4）：平台管理员全站代操作放行；站管须为本站袋，否则 2002。 */
    private void assertStationAccess(Long stationId) {
        LoginUser user = AuthContext.require();
        if (Constants.ROLE_PLATFORM_ADMIN.equals(user.getRole())) {
            return;
        }
        if (user.getStationId() == null || !user.getStationId().equals(stationId)) {
            throw new BizException(ErrorCode.NO_DATA_PERMISSION, "该袋不在您所管理的站点，无数据权限");
        }
    }

    /** 登记站点必须存在且启用（停用站不可再激活上架/取袋，见设计书 5.3/需求 FR-M4-07）。 */
    private void assertStationEnabled(Long stationId, String action) {
        Station station = stationId == null ? null : stationMapper.selectById(stationId);
        if (station == null) {
            throw new BizException(ErrorCode.BAG_UNAVAILABLE, "袋归属站点不存在，无法" + action);
        }
        if (!Constants.STATUS_ENABLED.equals(station.getStatus())) {
            throw new BizException(ErrorCode.BAG_UNAVAILABLE, "站点已停用，不可" + action);
        }
    }

    /** 建档/批量生成的登记站点校验：必须存在且启用（否则新档案无法进入循环）。 */
    private Station requireEnabledStation(Long stationId) {
        Station station = stationId == null ? null : stationMapper.selectById(stationId);
        if (station == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "登记的站点不存在");
        }
        if (!Constants.STATUS_ENABLED.equals(station.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "站点已停用，不能登记新袋");
        }
        return station;
    }

    /** 4110 前置：袋被「未出库调拨单」引用（明细待出库且单据待出库/在途）时拒绝操作。 */
    private void assertNotLockedByAllocation(String bagCode) {
        if (isLockedByAllocation(bagCode)) {
            throw new BizException(ErrorCode.BAG_LOCKED_BY_ALLOCATION,
                    "该袋已被未出库调拨单锁定，请先完成出库/核收或终止调拨单");
        }
    }

    /** 是否被未出库调拨单锁定（申领/转维修/报废/状态修正共用口径，见设计书 3.2 #2/#12/#15/#21）。 */
    private boolean isLockedByAllocation(String bagCode) {
        List<AllocationDetail> details = allocationDetailMapper.selectList(new LambdaQueryWrapper<AllocationDetail>()
                .eq(AllocationDetail::getBagCode, bagCode)
                .eq(AllocationDetail::getStatus, ALLOCATION_DETAIL_PENDING_DISPATCH));
        for (AllocationDetail detail : details) {
            AllocationOrder order = allocationOrderMapper.selectById(detail.getAllocationId());
            if (order != null
                    && (ALLOCATION_ORDER_PENDING_DISPATCH.equals(order.getStatus())
                        || ALLOCATION_ORDER_IN_TRANSIT.equals(order.getStatus()))) {
                return true;
            }
        }
        return false;
    }

    /** 是否平台管理员（全站代操作判定；代操作在流转日志标记 proxy，见契约模块版第 6 节）。 */
    private static boolean isPlatformAdmin() {
        return AuthContext.isPlatformAdmin();
    }

    private static boolean isUseCountExceeded(Bag bag) {
        return safeUseCount(bag) >= safeMaxUseCount(bag);
    }

    private static int safeUseCount(Bag bag) {
        return bag.getUseCount() == null ? 0 : bag.getUseCount();
    }

    private static int safeMaxUseCount(Bag bag) {
        return bag.getMaxUseCount() == null ? DEFAULT_MAX_USE_COUNT : bag.getMaxUseCount();
    }

    /** 查该袋最近的一张订单（含已取消：R1/R2 取消后袋仍已分配，等待 #4/#5 或 #21 闭环）。 */
    private BagOrder latestOrderOfBag(String bagCode) {
        List<BagOrder> orders = bagOrderMapper.selectList(new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getBagCode, bagCode)
                .orderByDesc(BagOrder::getId));
        return orders.isEmpty() ? null : orders.get(0);
    }

    /** 该用户是否持有此袋的进行中订单（信息最小化的归属人判定，设计书 7.4）。 */
    private boolean hasActiveOrder(Long userId, String bagCode) {
        if (userId == null) {
            return false;
        }
        Long count = bagOrderMapper.selectCount(new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getBagCode, bagCode)
                .eq(BagOrder::getUserId, userId)
                .in(BagOrder::getStatus,
                        OrderStatus.PENDING_SEND.code(),
                        OrderStatus.IN_USE.code(),
                        OrderStatus.PENDING_CONFIRM.code()));
        return count != null && count > 0;
    }

    // ==================== 私有：扫码失败限流与补充审计 ====================

    /**
     * 扫码失败计数（设计书 7.4 防枚举）：同一用户 1 分钟内失败达 {@value #SCAN_FAIL_LIMIT} 次触发 5101。
     *
     * <p>限流键只在失败路径累计；Redis 故障时降级放行（应用层限流由 Nginx 按 IP 兜底，
     * 见设计书 7.2 降级矩阵），不因限流组件故障放大错误。
     */
    private void recordScanFailure(LoginUser user) {
        boolean allowed;
        try {
            // 限额传 limit-1：计数达到 limit 的这次失败即触发（"≥10 次失败"口径）
            allowed = rateLimitService.tryAcquire(String.valueOf(user.getUserId()), SCAN_FAIL_API,
                    SCAN_FAIL_LIMIT - 1, SCAN_FAIL_WINDOW);
        } catch (Exception e) {
            log.warn("扫码失败计数异常，限流降级放行（Nginx 按 IP 兜底）：{}", e.getMessage());
            return;
        }
        if (!allowed) {
            writeSupplementAudit("扫码查询限流", "袋码查询失败达到 " + SCAN_FAIL_LIMIT + " 次/分钟，触发 5101（风控线索）");
            throw new BizException(ErrorCode.RATE_LIMITED, "袋码查询失败次数过多，请 1 分钟后重试");
        }
    }

    /**
     * 补充审计：状态机与 Controller 注解覆盖不到的关键事件走这里补记（如 #14 报废、扫码限流），
     * 使审计可按 action 检索到报废等重点核查项（设计书 7.6）。审计属旁路能力，失败只记 WARN。
     */
    private void writeSupplementAudit(String action, String detail) {
        try {
            LoginUser user = AuthContext.get();
            AuditLogEntity entity = new AuditLogEntity();
            entity.setOperatorId(user == null || user.getUserId() == null ? 0L : user.getUserId());
            entity.setOperatorRole(user == null || user.getRole() == null ? "" : user.getRole());
            entity.setModule(AUDIT_MODULE);
            entity.setAction(action);
            entity.setResult(1);
            entity.setErrorCode(0);
            entity.setCostMs(0);
            HttpServletRequest request = currentRequest();
            if (request == null) {
                entity.setIp("");
                entity.setUa("");
                entity.setRequestUri("");
            } else {
                // 列宽：ip 45 / ua 255 / uri 255（truncate 另加省略号，按列宽 −1 截断）
                entity.setIp(MaskUtils.truncate(resolveClientIp(request), 44));
                entity.setUa(MaskUtils.truncate(request.getHeader("User-Agent"), 254));
                entity.setRequestUri(MaskUtils.truncate(request.getRequestURI(), 254));
            }
            entity.setParamsMasked(MaskUtils.truncate(detail, 4095));
            auditLogService.record(entity);
        } catch (Exception e) {
            log.warn("补充审计写入失败（action={}）：{}", action, e.getMessage());
        }
    }

    /** 当前线程绑定的 HTTP 请求；异步/非 Web 上下文返回 null。 */
    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    /** 解析客户端 IP：反代场景优先 X-Forwarded-For 首个地址，缺失回退 remoteAddr（与审计切面同口径）。 */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    // ==================== 私有：批量生成打包 ====================

    /** 打包二维码压缩包：逐只 PNG（以袋码命名）+ manifest.txt（逐行袋码，打印贴标核对用）。 */
    private byte[] buildZip(List<Bag> bags) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            StringBuilder manifest = new StringBuilder();
            for (Bag bag : bags) {
                zip.putNextEntry(new ZipEntry(bag.getBagCode() + ".png"));
                zip.write(qrCodeService.generatePng(bag.getBagCode()));
                zip.closeEntry();
                manifest.append(bag.getBagCode()).append('\n');
            }
            zip.putNextEntry(new ZipEntry("manifest.txt"));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            log.error("二维码压缩包生成失败：{}", e.getMessage(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "二维码压缩包生成失败");
        }
    }

    /** 按袋码清单从库中重查袋行（保持清单顺序），供批生成幂等回放重建 ZIP。 */
    private List<Bag> reloadBags(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        Map<String, Bag> byCode = new LinkedHashMap<>();
        for (Bag bag : bagMapper.selectList(new LambdaQueryWrapper<Bag>().in(Bag::getBagCode, codes))) {
            byCode.put(bag.getBagCode(), bag);
        }
        List<Bag> ordered = new ArrayList<>(codes.size());
        for (String code : codes) {
            Bag bag = byCode.get(code);
            if (bag != null) {
                ordered.add(bag);
            }
        }
        return ordered;
    }

    // ==================== 私有：查询装配与排序 ====================

    /** 站点名映射（批量联查，避免逐行查库）。 */
    private Map<Long, String> stationNameMap(Collection<Long> stationIds) {
        List<Long> ids = stationIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (Station station : stationMapper.selectByIds(ids)) {
            names.put(station.getId(), station.getName());
        }
        return names;
    }

    /** 操作人昵称映射（追溯展示用；手工遍历避免 Collectors.toMap 对 null 值的限制）。 */
    private Map<Long, String> userNameMap(Collection<Long> userIds) {
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (SysUser user : sysUserMapper.selectByIds(ids)) {
            names.put(user.getId(), user.getNickname());
        }
        return names;
    }

    /** 排序白名单（设计书 9.4：排序字段不得直拼 SQL）；缺省按建档时间倒序。 */
    private void applyOrderBy(LambdaQueryWrapper<Bag> wrapper, String orderBy) {
        if (orderBy == null || orderBy.isBlank()) {
            wrapper.orderByDesc(Bag::getCreatedAt);
            return;
        }
        String[] parts = orderBy.split(",");
        String field = parts[0].trim();
        String direction = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "desc";
        if (!"asc".equals(direction) && !"desc".equals(direction)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "排序方向仅支持 asc/desc");
        }
        boolean asc = "asc".equals(direction);
        switch (field) {
            case "createdAt" -> wrapper.orderBy(true, asc, Bag::getCreatedAt);
            case "updatedAt" -> wrapper.orderBy(true, asc, Bag::getUpdatedAt);
            case "useCount" -> wrapper.orderBy(true, asc, Bag::getUseCount);
            case "bagCode" -> wrapper.orderBy(true, asc, Bag::getBagCode);
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "不支持的排序字段：" + field);
        }
    }

    /** 解析状态码（非法码报 3001；解析失败属请求参数问题，不落 4102——那表示合法状态间的非法跳变）。 */
    private static BagStatus parseStatus(String status) {
        try {
            return BagStatus.of(status.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知的袋状态码：" + status);
        }
    }

    /** 状态码转中文名（历史日志码值异常时回显原码，不因展示问题打断追溯视图）。 */
    private static String statusName(String code) {
        try {
            return BagStatus.of(code).displayName();
        } catch (IllegalArgumentException e) {
            return code;
        }
    }

    /** 事件码转中文名（同上容错）。 */
    private static String eventName(String code) {
        try {
            return BagEvent.of(code).displayName();
        } catch (IllegalArgumentException e) {
            return code;
        }
    }

    private static String orderStatusName(String code) {
        try {
            return OrderStatus.of(code).displayName();
        } catch (IllegalArgumentException e) {
            return code;
        }
    }

    private static String unavailableMessage(BagStatus status) {
        return switch (status) {
            case INACTIVE -> "该袋尚未激活，暂不可使用（请联系站点管理员激活上架）";
            case LOST -> "该袋已登记遗失，不可使用";
            case SCRAPPED -> "该袋已报废，不可使用";
            default -> "该袋当前不可用";
        };
    }

    private static String fallbackText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static long normalizePageNum(BagQuery query) {
        Integer pageNum = query == null ? null : query.getPageNum();
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private static long normalizePageSize(BagQuery query) {
        Integer pageSize = query == null ? null : query.getPageSize();
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    // ==================== 内部数据结构 ====================

    /**
     * 批量接口逐袋结果明细：字段名固定为 {@code bagCode / success / code / message}
     * （前端 BatchResultDialog 按此渲染，不得改名，见契约模块版第 5 节）。
     */
    public record BatchItem(String bagCode, boolean success, int code, String message) {
    }

    /** 批生成幂等回放清单：只保存首次结果的袋码清单（ZIP 二进制不入 Redis）。 */
    private record GeneratedBatch(List<String> codes) {
    }
}
