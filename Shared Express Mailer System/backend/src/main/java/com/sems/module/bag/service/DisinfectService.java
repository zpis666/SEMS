package com.sems.module.bag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.common.BatchResult;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.common.PageResult;
import com.sems.common.util.BizNoUtils;
import com.sems.common.util.JsonUtils;
import com.sems.config.SemsProperties;
import com.sems.infra.IdempotentService;
import com.sems.infra.RedisKeys;
import com.sems.module.bag.dto.CreateDisinfectDto;
import com.sems.module.bag.dto.DisinfectCreateVo;
import com.sems.module.bag.dto.DisinfectDetailVo;
import com.sems.module.bag.dto.DisinfectQuery;
import com.sems.module.bag.dto.DisinfectVo;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.entity.DisinfectDetail;
import com.sems.module.bag.entity.DisinfectRecord;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.bag.mapper.DisinfectDetailMapper;
import com.sems.module.bag.mapper.DisinfectRecordMapper;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagTransitionService;
import com.sems.module.statemachine.TransitionCommand;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.StationMapper;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 消毒批次登记与查询服务（快递袋管理模块 M4；见《系统设计说明书》5.4、3.2 #9/#10、
 * 3.4.2「归还—消毒—复用流程」与需求 FR-M4-11）。
 *
 * <p><b>登记流程</b>（{@code POST /api/v1/disinfections}）：
 * <ol>
 *   <li>接口级幂等（clientRequestId，契约模块版 4）；</li>
 *   <li>请求级校验：消毒方式白名单、起止时间先后、明细非空且 ≤ {@code sems.batch.disinfect-max}
 *       （200 只，批量上限的例外值，超限 3001 并写明上限）；</li>
 *   <li>站点判定与「本站」数据权限（4.4：站点管理员默认本站，平台管理员代操作须显式指定站点，日志标 proxy）；</li>
 *   <li>风控计数（3.5：同站点同操作人 10 分钟内确认类操作 ≥20 次触发 5101）；</li>
 *   <li>先写消毒批次记录（汇总先按入参声明计算），再逐袋写明细并触发状态机
 *       （合格 {@link BagEvent#DISINFECT_PASS} → 可复用；不合格 {@link BagEvent#DISINFECT_FAIL} → 维修中）；</li>
 *   <li>批次计数按实际写入的明细回填（口径见消毒记录实体注释），返回统一批量信封
 *       {@link DisinfectCreateVo}（批次头 + items 逐袋处理结果，元素字段固定为
 *       bagCode/success/code/message；查询接口的 DisinfectVo 明细键 details 保持不动）。</li>
 * </ol>
 *
 * <p><b>单只失败不影响其余</b>（设计书 3.2 表后说明）：逐袋单独捕获业务异常（4102/4106 等）记为失败项，
 * 继续处理下一袋。批次的每一只袋在独立事务内完成「状态流转 + 消毒明细写入」
 * （{@link TransactionTemplate}，REQUIRES_NEW）——绝不能把整个批量方法包进一个事务：
 * 状态机入口本身是 {@code @Transactional(REQUIRED)}，内层抛出的业务异常会把共用事务标记为
 * rollback-only，外层即使捕获异常也会在提交时抛 UnexpectedRollbackException，导致整批回滚、
 * 违背「单只失败不影响其余」。批次记录插入为单条语句（自带原子性），不需要外层事务。
 */
@Service
public class DisinfectService {

    private static final Logger log = LoggerFactory.getLogger(DisinfectService.class);

    /** 幂等键：HTTP 方法 + 路径模板（契约模块版 4，全站唯一）。 */
    private static final String API_KEY_CREATE = "POST /disinfections";

    /** 逐袋结果码值（与 disinfect_detail.result 的 DDL 中文码值一致）。 */
    private static final String RESULT_PASS = "合格";
    private static final String RESULT_FAIL = "不合格";
    /** 批次汇总的混合取值（disinfect_record.result）。 */
    private static final String RESULT_PARTIAL = "部分不合格";

    /** 消毒方式白名单（与 disinfect_record.method 的 DDL 注释口径一致：紫外线/臭氧/高温/喷洒）。 */
    private static final Set<String> ALLOWED_METHODS = Set.of("紫外线", "臭氧", "高温", "喷洒");

    /** 缺陷说明长度上限（库列 defect_desc varchar(255)）。 */
    private static final int DEFECT_DESC_MAX = 255;

    /** 风控窗口与阈值（设计书 3.5：同站点同操作人 10 分钟内确认类操作 ≥20 次触发 5101 并列入风控线索）。 */
    private static final Duration RISK_WINDOW = Duration.ofMinutes(10);
    private static final int RISK_CONFIRM_LIMIT = 20;

    /** 分页上限（契约 10：pageSize 最大 100）。 */
    private static final int PAGE_SIZE_MAX = 100;

    private final DisinfectRecordMapper disinfectRecordMapper;
    private final DisinfectDetailMapper disinfectDetailMapper;
    private final BagMapper bagMapper;
    private final StationMapper stationMapper;
    private final BagTransitionService bagTransitionService;
    private final IdempotentService idempotentService;
    private final SemsProperties semsProperties;
    private final StringRedisTemplate redis;

    /** 逐袋独立事务（REQUIRES_NEW）：状态流转与明细写入同生共死，且单只失败不污染其余袋。 */
    private final TransactionTemplate perBagTransaction;

    public DisinfectService(DisinfectRecordMapper disinfectRecordMapper,
                            DisinfectDetailMapper disinfectDetailMapper,
                            BagMapper bagMapper,
                            StationMapper stationMapper,
                            BagTransitionService bagTransitionService,
                            IdempotentService idempotentService,
                            SemsProperties semsProperties,
                            StringRedisTemplate redis,
                            PlatformTransactionManager transactionManager) {
        this.disinfectRecordMapper = disinfectRecordMapper;
        this.disinfectDetailMapper = disinfectDetailMapper;
        this.bagMapper = bagMapper;
        this.stationMapper = stationMapper;
        this.bagTransitionService = bagTransitionService;
        this.idempotentService = idempotentService;
        this.semsProperties = semsProperties;
        this.redis = redis;
        this.perBagTransaction = new TransactionTemplate(transactionManager);
        this.perBagTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 消毒批次登记（见类注释的流程与事务口径）。
     *
     * <p>出参为统一批量信封 {@link DisinfectCreateVo}（批次头 + items 逐袋结果）；
     * 刻意声明 {@code NOT_SUPPORTED}：本方法不得参与外层事务（原因见类注释——
     * 批量部分成功要求每只袋的事务独立成败），逐袋事务由 {@link #perBagTransaction} 显式创建。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DisinfectCreateVo register(CreateDisinfectDto dto, String clientRequestId) {
        requireClientRequestId(clientRequestId);
        String cached = idempotentService.findCompleted(API_KEY_CREATE, clientRequestId);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            // 幂等命中：回放首次结果，不重复登记、不重复流转
            return JsonUtils.parse(cached, DisinfectCreateVo.class);
        }
        idempotentService.markProcessing(API_KEY_CREATE, clientRequestId);
        try {
            DisinfectCreateVo vo = doRegister(dto, clientRequestId);
            idempotentService.markCompleted(API_KEY_CREATE, clientRequestId, JsonUtils.toJson(vo));
            return vo;
        } catch (RuntimeException e) {
            // 任何失败立即删占位，保证客户端重试不被自己的「处理中」挡住（契约模块版 4）
            idempotentService.clear(API_KEY_CREATE, clientRequestId);
            throw e;
        }
    }

    /** 消毒批次分页查询（含批次明细逐袋结果；本站站点管理员限本站，见 5.4 与 4.4）。 */
    public PageResult<DisinfectVo> page(DisinfectQuery query) {
        LoginUser operator = AuthContext.require();
        Long stationId = resolvePageStation(query.getStationId(), operator);
        String resultFilter = trimToNull(query.getResult());
        if (resultFilter != null && !RESULT_PASS.equals(resultFilter)
                && !RESULT_FAIL.equals(resultFilter) && !RESULT_PARTIAL.equals(resultFilter)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "批次结果筛选取值非法：" + resultFilter);
        }

        LambdaQueryWrapper<DisinfectRecord> wrapper = Wrappers.lambdaQuery(DisinfectRecord.class)
                .eq(stationId != null, DisinfectRecord::getStationId, stationId)
                .eq(resultFilter != null, DisinfectRecord::getResult, resultFilter)
                // 时间筛选口径：登记时间（created_at），与 idx_dr_station_time 索引对齐
                .ge(query.getBeginTime() != null, DisinfectRecord::getCreatedAt, query.getBeginTime())
                .le(query.getEndTime() != null, DisinfectRecord::getCreatedAt, query.getEndTime());
        applyOrder(wrapper, query.getOrderBy());

        Page<DisinfectRecord> page = disinfectRecordMapper.selectPage(
                new Page<>(normalizePageNum(query.getPageNum()), normalizePageSize(query.getPageSize())), wrapper);
        return PageResult.of(toVoList(page.getRecords()), page.getTotal(), page.getSize());
    }

    /* ------------------------------------------------------------------ 登记主流程 */

    private DisinfectCreateVo doRegister(CreateDisinfectDto dto, String clientRequestId) {
        validateRequest(dto);
        LoginUser operator = AuthContext.require();
        Long stationId = resolveOperationStation(dto.getStationId(), operator);
        assertConfirmRisk(stationId, operator.getUserId());

        List<CreateDisinfectDto.BagItem> items = dto.getBags();
        // 1) 先写批次记录：汇总先按入参声明的结果计算，处理完成后再按实际明细回填
        //    （消毒记录实体注释：bag_count/pass_count/fail_count 由 disinfect_detail 明细汇总回填）
        DisinfectRecord record = new DisinfectRecord();
        record.setStationId(stationId);
        record.setBatchNo(BizNoUtils.batchNo());
        record.setMethod(dto.getMethod().trim());
        record.setStartTime(dto.getStartTime());
        record.setEndTime(dto.getEndTime());
        record.setOperatorId(operator.getUserId());
        record.setBagCount(items.size());
        int declaredPass = countDeclared(items, RESULT_PASS);
        int declaredFail = countDeclared(items, RESULT_FAIL);
        record.setPassCount(declaredPass);
        record.setFailCount(declaredFail);
        record.setResult(summarize(declaredPass, declaredFail, items.size()));
        record.setRemark(trimToNull(dto.getRemark()));
        record.setCreatedAt(LocalDateTime.now());
        insertRecordWithRetry(record);

        // 2) 再逐袋写明细并触发状态机（逐袋独立成败，输入顺序即响应顺序）
        List<DisinfectDetailVo> details = new ArrayList<>(items.size());
        Set<String> seenBagCodes = new HashSet<>();
        for (CreateDisinfectDto.BagItem item : items) {
            details.add(processBag(item, seenBagCodes, record, operator, clientRequestId));
        }

        // 3) 批次计数按实际写入的明细回填（含单只失败后的真实结果）
        backfillRecordCounts(record);

        DisinfectCreateVo vo = toCreateVo(record, details);
        log.info("消毒批次登记完成：batchNo={}，站点={}，袋数={}，合格={}，不合格={}",
                record.getBatchNo(), stationId, record.getBagCount(), record.getPassCount(), record.getFailCount());
        return vo;
    }

    /**
     * 单只袋处理：校验 → 本站归属校验 → 状态机流转 + 明细写入（同一事务）。
     *
     * <p>失败只体现在该袋的返回项（success=false + 错误码 + 原因），不抛出、不中断整批；
     * 明细与流转在同一事务内同生共死（先写明细、后触发状态机，见方法体：不合格流转的伴随动作
     * 需要在事务内读到本袋的缺陷说明），流转被拒则该袋不落明细，重试由前端按袋码发起。
     */
    private DisinfectDetailVo processBag(CreateDisinfectDto.BagItem item, Set<String> seenBagCodes,
                                         DisinfectRecord record, LoginUser operator, String clientRequestId) {
        if (item == null) {
            return failItem(null, ErrorCode.PARAM_INVALID, "袋码明细项不能为空");
        }
        String rawCode = item.getBagCode() == null ? null : item.getBagCode().trim().toUpperCase();
        if (rawCode == null || rawCode.isEmpty()) {
            return failItem(null, ErrorCode.PARAM_INVALID, "袋码不能为空");
        }
        if (!rawCode.matches(Constants.BAG_CODE_REGEX)) {
            return failItem(rawCode, ErrorCode.PARAM_INVALID, "袋码格式不合法，形如 SJB-L-2609-00001-4");
        }
        if (!seenBagCodes.add(rawCode)) {
            // 批次内重复登记：只判该条失败，先出现的一条照常处理（DB 唯一键 (disinfect_id, bag_code) 兜底）
            return failItem(rawCode, ErrorCode.PARAM_INVALID, "批次内重复登记同一袋码");
        }
        String result = trimToNull(item.getResult());
        if (!RESULT_PASS.equals(result) && !RESULT_FAIL.equals(result)) {
            return failItem(rawCode, ErrorCode.PARAM_INVALID, "逐袋结果只能为「合格」或「不合格」");
        }
        String defectDesc = trimToNull(item.getDefectDesc());
        if (RESULT_FAIL.equals(result) && defectDesc == null) {
            // 不合格必须有缺陷说明（3.2 #10 前置校验），缺失只判该袋失败
            return failItem(rawCode, ErrorCode.PARAM_INVALID, "不合格必须填写缺陷说明");
        }
        if (defectDesc != null && defectDesc.length() > DEFECT_DESC_MAX) {
            return failItem(rawCode, ErrorCode.PARAM_INVALID, "缺陷说明长度不能超过 255 字符");
        }

        // 本站校验（设计书 4.4 ①：操作对象在站点内的动作，校验对象当前 station_id 与批次站点一致）；
        // 平台管理员代操作时同样按「批次站点」校验，跨站袋不允许混入本批
        Bag bag = bagMapper.selectOne(Wrappers.lambdaQuery(Bag.class).eq(Bag::getBagCode, rawCode));
        if (bag == null) {
            return failItem(rawCode, ErrorCode.BAG_NOT_FOUND, "袋码不存在");
        }
        if (!Objects.equals(bag.getStationId(), record.getStationId())) {
            return failItem(rawCode, ErrorCode.NO_DATA_PERMISSION, "该袋不属于本批次站点");
        }

        BagEvent event = RESULT_PASS.equals(result) ? BagEvent.DISINFECT_PASS : BagEvent.DISINFECT_FAIL;
        try {
            perBagTransaction.execute(status -> {
                // 先写消毒明细再触发状态机（同一事务）：#10 的伴随动作（补写维修登记）要在事务内
                // 读取「该袋最新一条不合格明细的缺陷说明」（见 BagTransitionActions#onDisinfectFail）；
                // 若流转被拒，整个每袋事务回滚，明细与流转同生共死，不会留下无流转的孤儿明细
                insertDetail(record.getId(), rawCode, result, defectDesc);
                bagTransitionService.fire(TransitionCommand.builder()
                        .bagCode(rawCode)
                        .event(event)
                        .operatorId(operator.getUserId())
                        .operatorRole(operator.getRole())
                        .stationId(record.getStationId())
                        .proxy(AuthContext.isPlatformAdmin())
                        // 不合格时把缺陷说明写入流转日志备注，供追溯与维修登记取用
                        .remark(RESULT_FAIL.equals(result) ? defectDesc : null)
                        .clientRequestId(clientRequestId)
                        .attributes(Map.of(
                                "batchNo", record.getBatchNo(),
                                "disinfectId", record.getId(),
                                "result", result,
                                "defectDesc", defectDesc == null ? "" : defectDesc))
                        .build());
                return null;
            });
        } catch (BizException e) {
            // 状态机拒绝（4102 非法跳变、4106 袋不存在、4103 并发竞争等）：该袋失败，其余袋继续
            return failItem(rawCode, e.getErrorCode(), e.getMessage());
        }
        return successItem(rawCode, result, defectDesc);
    }

    /** 写消毒明细；唯一键 (disinfect_id, bag_code) 冲突视为已写入（重放/重试路径幂等），不覆盖。 */
    private void insertDetail(Long disinfectId, String bagCode, String result, String defectDesc) {
        DisinfectDetail detail = new DisinfectDetail();
        detail.setDisinfectId(disinfectId);
        detail.setBagCode(bagCode);
        detail.setResult(result);
        detail.setDefectDesc(RESULT_FAIL.equals(result) ? defectDesc : null);
        detail.setCreatedAt(LocalDateTime.now());
        try {
            disinfectDetailMapper.insert(detail);
        } catch (DuplicateKeyException e) {
            // 同一批次同一袋已登记过（幂等重放）：保留首次记录，本次不再插入
            log.debug("消毒明细已存在，跳过重复写入：batchId={}，bagCode={}", disinfectId, bagCode);
        }
    }

    /** 批次计数回填：以实际写入的明细为准（实体注释口径），全部处理失败时明细为空、计数为 0。 */
    private void backfillRecordCounts(DisinfectRecord record) {
        List<DisinfectDetail> rows = disinfectDetailMapper.selectList(
                Wrappers.lambdaQuery(DisinfectDetail.class).eq(DisinfectDetail::getDisinfectId, record.getId()));
        int pass = 0;
        int fail = 0;
        for (DisinfectDetail row : rows) {
            if (RESULT_PASS.equals(row.getResult())) {
                pass++;
            } else if (RESULT_FAIL.equals(row.getResult())) {
                fail++;
            }
        }
        int total = pass + fail;
        String summary = summarize(pass, fail, total);
        // 只更新汇总列（MyBatis-Plus 默认忽略 null 字段），批次头其余字段不可变
        DisinfectRecord update = new DisinfectRecord();
        update.setId(record.getId());
        update.setBagCount(total);
        update.setPassCount(pass);
        update.setFailCount(fail);
        update.setResult(summary);
        disinfectRecordMapper.updateById(update);
        record.setBagCount(total);
        record.setPassCount(pass);
        record.setFailCount(fail);
        record.setResult(summary);
    }

    /**
     * 结果汇总（设计书 5.4/消毒记录表口径）：全合格写「合格」、全不合格写「不合格」、混合写「部分不合格」。
     * 明细为空（提交的袋全部处理失败）时既非全合格也非全不合格，按「部分不合格」记录以提示该批存在异常。
     */
    private static String summarize(int pass, int fail, int total) {
        if (total > 0 && pass == total) {
            return RESULT_PASS;
        }
        if (total > 0 && fail == total) {
            return RESULT_FAIL;
        }
        return RESULT_PARTIAL;
    }

    /* ------------------------------------------------------------------ 校验与权限 */

    private void validateRequest(CreateDisinfectDto dto) {
        if (dto == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        String method = trimToNull(dto.getMethod());
        if (method == null || !ALLOWED_METHODS.contains(method)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "消毒方式不合法，允许值：紫外线/臭氧/高温/喷洒");
        }
        if (dto.getStartTime() == null || dto.getEndTime() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "消毒起止时间不能为空");
        }
        if (dto.getEndTime().isBefore(dto.getStartTime())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "消毒结束时间不能早于开始时间");
        }
        if (dto.getBags() == null || dto.getBags().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "消毒袋码明细不能为空");
        }
        int max = semsProperties.getBatch().getDisinfectMax();
        if (dto.getBags().size() > max) {
            // 超限文案必须写明上限值（契约 10 / 模块版 5），前端先拦、后端为权威
            throw new BizException(ErrorCode.PARAM_INVALID, "单批消毒袋码不得超过 " + max + " 只");
        }
    }

    /**
     * 判定本次操作的消毒站点（4.4「本站」数据权限）：
     * 站点管理员缺省取本人绑定站点、显式传他站报 2002；平台管理员可全站代操作但必须显式指定站点。
     */
    private Long resolveOperationStation(Long requested, LoginUser operator) {
        Long stationId = requested != null ? requested : operator.getStationId();
        if (stationId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请指定消毒站点（平台管理员代操作须显式指定）");
        }
        if (!AuthContext.isPlatformAdmin() && !stationId.equals(operator.getStationId())) {
            throw new BizException(ErrorCode.NO_DATA_PERMISSION, "只能登记本站的消毒批次");
        }
        Station station = stationMapper.selectById(stationId);
        if (station == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "站点不存在：" + stationId);
        }
        // 停用站点仍允许消毒（设计书 5.3：停用后仍须完成收尾操作，含消毒），故不校验 station.status
        return stationId;
    }

    /** 查询场景的站点范围：站点管理员强制本站（显式他站报 2002），平台管理员不传即全站。 */
    private Long resolvePageStation(Long requested, LoginUser operator) {
        if (AuthContext.isPlatformAdmin()) {
            return requested;
        }
        if (requested != null && !requested.equals(operator.getStationId())) {
            throw new BizException(ErrorCode.NO_DATA_PERMISSION, "只能查询本站的消毒批次");
        }
        if (operator.getStationId() == null) {
            throw new BizException(ErrorCode.NO_DATA_PERMISSION, "当前账号未绑定站点");
        }
        return operator.getStationId();
    }

    /**
     * 风控计数（设计书 3.5：同站点同操作人 10 分钟内确认类操作 ≥20 次 → 5101 并列入 /alerts 风控线索）。
     * 固定窗口：窗口内首次计数时设置 TTL，之后只增不减、到期自动清零。
     */
    private void assertConfirmRisk(Long stationId, Long userId) {
        String key = RedisKeys.auditRisk(String.valueOf(stationId), String.valueOf(userId));
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, RISK_WINDOW);
        }
        if (count != null && count > RISK_CONFIRM_LIMIT) {
            throw new BizException(ErrorCode.RATE_LIMITED, "同站点确认类操作频次异常，已触发风控");
        }
    }

    private void requireClientRequestId(String clientRequestId) {
        if (clientRequestId == null || !clientRequestId.matches(Constants.UUID_REGEX)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少或非法的 clientRequestId 请求头");
        }
    }

    /* ------------------------------------------------------------------ 查询与转换 */

    private List<DisinfectVo> toVoList(List<DisinfectRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        List<Long> ids = records.stream().map(DisinfectRecord::getId).toList();
        // 批量取明细后按批次分组，避免逐批次 N+1 查询
        List<DisinfectDetail> details = disinfectDetailMapper.selectList(
                Wrappers.lambdaQuery(DisinfectDetail.class)
                        .in(DisinfectDetail::getDisinfectId, ids)
                        .orderByAsc(DisinfectDetail::getId));
        Map<Long, List<DisinfectDetail>> grouped = details.stream()
                .collect(Collectors.groupingBy(DisinfectDetail::getDisinfectId));
        List<DisinfectVo> vos = new ArrayList<>(records.size());
        for (DisinfectRecord record : records) {
            List<DisinfectDetailVo> detailVos = new ArrayList<>();
            for (DisinfectDetail detail : grouped.getOrDefault(record.getId(), List.of())) {
                DisinfectDetailVo item = new DisinfectDetailVo();
                item.setBagCode(detail.getBagCode());
                item.setResult(detail.getResult());
                item.setDefectDesc(detail.getDefectDesc());
                detailVos.add(item);
            }
            vos.add(toVo(record, detailVos));
        }
        return vos;
    }

    private DisinfectVo toVo(DisinfectRecord record, List<DisinfectDetailVo> details) {
        DisinfectVo vo = new DisinfectVo();
        vo.setId(record.getId());
        vo.setBatchNo(record.getBatchNo());
        vo.setStationId(record.getStationId());
        vo.setMethod(record.getMethod());
        vo.setStartTime(record.getStartTime());
        vo.setEndTime(record.getEndTime());
        vo.setOperatorId(record.getOperatorId());
        vo.setResult(record.getResult());
        vo.setBagCount(record.getBagCount());
        vo.setPassCount(record.getPassCount());
        vo.setFailCount(record.getFailCount());
        vo.setRemark(record.getRemark());
        vo.setCreatedAt(record.getCreatedAt());
        vo.setDetails(details);
        return vo;
    }

    /**
     * 登记接口出参装配（统一批量信封，契约模块版第 5 节）：total/success/failed/items 由
     * {@link BatchResult#of} 按逐袋处理结果的 success 标志汇总（successItem/failItem 逐条写入该标志），
     * 再补批次头字段。查询接口的批次装配继续走 {@link #toVo}（明细键 details 保持不动）。
     */
    private static DisinfectCreateVo toCreateVo(DisinfectRecord record, List<DisinfectDetailVo> details) {
        BatchResult<DisinfectDetailVo> counted = BatchResult.of(details,
                item -> Boolean.TRUE.equals(item.getSuccess()));
        DisinfectCreateVo vo = new DisinfectCreateVo();
        vo.setTotal(counted.getTotal());
        vo.setSuccess(counted.getSuccess());
        vo.setFailed(counted.getFailed());
        vo.setItems(counted.getItems());
        vo.setId(record.getId());
        vo.setBatchNo(record.getBatchNo());
        vo.setResult(record.getResult());
        vo.setStationId(record.getStationId());
        vo.setStartTime(record.getStartTime());
        vo.setEndTime(record.getEndTime());
        vo.setMethod(record.getMethod());
        return vo;
    }

    private DisinfectDetailVo successItem(String bagCode, String result, String defectDesc) {
        DisinfectDetailVo vo = new DisinfectDetailVo();
        vo.setBagCode(bagCode);
        vo.setResult(result);
        vo.setDefectDesc(RESULT_FAIL.equals(result) ? defectDesc : null);
        vo.setSuccess(true);
        // 成功项也必须显式写 code=0：application.yml 开了 default-property-inclusion: non_null，
        // 留 null 会让 code 字段整个从 JSON 里消失，逐袋明细的字段集合时有时无，
        // 与「元素固定含 bagCode/success/code/message」的契约（设计书 6.2）不符，前端与接口测试都没法写死断言。
        vo.setCode(ErrorCode.SUCCESS.getCode());
        vo.setMessage(RESULT_PASS.equals(result) ? "消毒合格，已转可复用" : "消毒不合格，已转维修中");
        return vo;
    }

    private DisinfectDetailVo failItem(String bagCode, ErrorCode code, String message) {
        DisinfectDetailVo vo = new DisinfectDetailVo();
        vo.setBagCode(bagCode);
        vo.setSuccess(false);
        vo.setCode(code.getCode());
        vo.setMessage(message);
        return vo;
    }

    /* ------------------------------------------------------------------ 通用小工具 */

    private void insertRecordWithRetry(DisinfectRecord record) {
        try {
            disinfectRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 批次号 = 时间戳 + 6 位安全随机段，碰撞概率极低；唯一键冲突时换号重试一次（BizNoUtils 约定）
            log.warn("消毒批次号碰撞，换号重试：{}", record.getBatchNo());
            record.setBatchNo(BizNoUtils.batchNo());
            disinfectRecordMapper.insert(record);
        }
    }

    private static int countDeclared(List<CreateDisinfectDto.BagItem> items, String result) {
        int count = 0;
        for (CreateDisinfectDto.BagItem item : items) {
            if (item != null && result.equals(trimToNull(item.getResult()))) {
                count++;
            }
        }
        return count;
    }

    /** 排序字段白名单（契约 11：动态排序一律走白名单，未登记取值直接拒绝）。 */
    private static void applyOrder(LambdaQueryWrapper<DisinfectRecord> wrapper, String orderBy) {
        if (orderBy == null || orderBy.isBlank() || "createdAt".equals(orderBy)) {
            wrapper.orderByDesc(DisinfectRecord::getCreatedAt).orderByDesc(DisinfectRecord::getId);
            return;
        }
        switch (orderBy) {
            case "startTime" -> wrapper.orderByDesc(DisinfectRecord::getStartTime).orderByDesc(DisinfectRecord::getId);
            case "endTime" -> wrapper.orderByDesc(DisinfectRecord::getEndTime).orderByDesc(DisinfectRecord::getId);
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "不支持的排序字段：" + orderBy);
        }
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
