package com.sems.module.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.common.util.BizNoUtils;
import com.sems.common.util.JsonUtils;
import com.sems.common.util.MaskUtils;
import com.sems.config.SemsProperties;
import com.sems.infra.IdempotentService;
import com.sems.infra.RateLimitService;
import com.sems.infra.StockService;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.dto.BorrowDto;
import com.sems.module.order.dto.BorrowVo;
import com.sems.module.order.dto.OrderDetailVo;
import com.sems.module.order.dto.ShippingDto;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.entity.StockCompensationLog;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.order.mapper.StockCompensationLogMapper;
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
import com.sems.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 共享快递袋服务：申领、提交寄件、撤回（见《系统设计说明书》5.2「共享快递袋服务模块（M2）」、
 * 3.4.1「取袋—寄件流程」与 7.2「Redis 库存并发扣减方案」；契约 9「POST /borrows 完整流程」逐条实现）。
 *
 * <p><b>两阶段与回补路径的落点</b>（7.2）：
 * <ul>
 *   <li>阶段一（申领）：限流 → 袋/站点/调拨校验 → {@link StockService#tryDeduct} Lua 原子扣减
 *       （在持袋数 +1、日频次 +1、库存 −1、预扣凭据，均由 Lua 完成）→ 状态机事务（袋→已分配 + 建单）；
 *       DB 事务失败按 <b>R0 同步补偿</b>（先回查订单是否已提交，未提交才补偿并写 {@code stock_compensation_log}）；</li>
 *   <li>阶段二（寄件）：状态机事务内订单→使用中 + 袋→使用中，事务成功后 {@code deleteHold} 删预扣凭据；</li>
 *   <li>R1（撤回）：仅订单→已取消 + 删预扣凭据，<b>不改袋状态、不减在持袋数</b>（袋仍在用户手上）。</li>
 * </ul>
 *
 * <p><b>为什么 borrow / shipping 不加 {@code @Transactional}</b>：事务边界由状态机
 * {@code BagTransitionService.fire} 承担（订单联动与袋状态必须同一事务）。若在外层再包事务：
 * ① 申领按 R0 补偿时补偿日志会随外层回滚一起丢失（补偿必须发生在失败事务完整回滚之后）；
 * ② 寄件的删预扣凭据按 7.2 阶段二必须在 DB 事务提交之后执行，包在外层事务里就会提前删。
 * 因此这两个方法以「无外层事务 + 状态机内事务」实现，其余写方法按契约模块版 11 标注事务注解。
 */
@Service
public class BorrowService {

    private static final Logger log = LoggerFactory.getLogger(BorrowService.class);

    /** 接口级幂等键：HTTP 方法 + 路径模板（契约模块版第 4 节）。 */
    private static final String API_BORROW = "POST /borrows";
    private static final String API_SHIPPING = "POST /borrows/{orderNo}/shipping";
    private static final String API_WITHDRAW = "DELETE /borrows/{orderNo}";

    /**
     * 申领后未寄件自动取消时限：24 小时（设计书 3.5；SemsProperties 未登记该键，
     * 按契约模块版 11 以模块内具名常量固化，仅供 {@code expiresHint} 文案使用，实际取消由定时任务 R2 执行）。
     */
    private static final Duration PENDING_SEND_TTL = Duration.ofHours(24);

    /** 取袋应用层限流：固定窗口 1 分钟 10 次（契约 9 第 2 步；窗口口径见设计书 3.5）。 */
    private static final int BORROW_RATE_LIMIT_PER_MINUTE = 10;

    /** 限流窗口：1 分钟（设计书 3.5「应用层限流窗口」）。 */
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    /** 提示文案时间格式。 */
    private static final DateTimeFormatter HINT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 调拨明细「待出库」状态：建单即锁定该袋（设计书 3.4.3）。 */
    private static final String ALLOCATION_DETAIL_PENDING_DISPATCH = "PENDING_DISPATCH";

    /** 调拨单「待出库」状态。 */
    private static final String ALLOCATION_STATUS_PENDING_DISPATCH = "PENDING_DISPATCH";

    /** 调拨单「在途」状态。 */
    private static final String ALLOCATION_STATUS_IN_TRANSIT = "IN_TRANSIT";

    /**
     * 在持袋数口径：袋处于已分配/使用中两态的订单才计入（设计书 3.5）。
     * 降级路径用袋状态判定；正常路径由 Lua 计数保证。
     */
    private static final Set<String> THREE_STATE_BAG_CODES = Set.of(
            BagStatus.ALLOCATED.code(), BagStatus.IN_USE.code());

    private final BagMapper bagMapper;
    private final StationMapper stationMapper;
    private final AllocationDetailMapper allocationDetailMapper;
    private final AllocationOrderMapper allocationOrderMapper;
    private final BagOrderMapper bagOrderMapper;
    private final StockCompensationLogMapper stockCompensationLogMapper;
    private final StockService stockService;
    private final IdempotentService idempotentService;
    private final RateLimitService rateLimitService;
    private final BagTransitionService bagTransitionService;
    private final OrderService orderService;
    private final SemsProperties properties;
    /** 降级路径的显式事务边界（校验与建单必须同一事务，契约模块版第 10 节）。 */
    private final TransactionTemplate transactionTemplate;

    public BorrowService(BagMapper bagMapper,
                         StationMapper stationMapper,
                         AllocationDetailMapper allocationDetailMapper,
                         AllocationOrderMapper allocationOrderMapper,
                         BagOrderMapper bagOrderMapper,
                         StockCompensationLogMapper stockCompensationLogMapper,
                         StockService stockService,
                         IdempotentService idempotentService,
                         RateLimitService rateLimitService,
                         BagTransitionService bagTransitionService,
                         OrderService orderService,
                         SemsProperties properties,
                         PlatformTransactionManager transactionManager) {
        this.bagMapper = bagMapper;
        this.stationMapper = stationMapper;
        this.allocationDetailMapper = allocationDetailMapper;
        this.allocationOrderMapper = allocationOrderMapper;
        this.bagOrderMapper = bagOrderMapper;
        this.stockCompensationLogMapper = stockCompensationLogMapper;
        this.stockService = stockService;
        this.idempotentService = idempotentService;
        this.rateLimitService = rateLimitService;
        this.bagTransitionService = bagTransitionService;
        this.orderService = orderService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ==================== 申领（POST /borrows，契约 9 的 12 步） ====================

    /**
     * 扫码申领取袋（设计书 5.2、3.4.1；契约 9 逐条实现）。
     *
     * @param dto              申领入参（袋码）
     * @param clientRequestId  幂等请求头（必填 UUID）
     * @return 申领结果（订单号、袋码、站点、时限提示）
     */
    public BorrowVo borrow(BorrowDto dto, String clientRequestId) {
        String crid = requireClientRequestId(clientRequestId);

        // 1) 接口级幂等（契约模块版第 4 节范式）：命中已完成即回放首次结果
        //    Redis 异常时按 7.2 降级矩阵放行（幂等退化为 DB 唯一键兜底），否则熔断期请求会
        //    在到达 isDegraded() 判定之前就全部 5000，降级路径永远无法激活
        String cached = findCompletedQuietly(API_BORROW, crid);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            return JsonUtils.parse(cached, BorrowVo.class);
        }
        markProcessingQuietly(API_BORROW, crid);
        try {
            BorrowVo vo = executeBorrow(dto, crid);
            // 业务已成功，幂等结果写失败不能反过来把成功请求报成失败（记 WARN，客户端重试由状态机兜底）
            markCompletedQuietly(API_BORROW, crid, JsonUtils.toJson(vo));
            return vo;
        } catch (RuntimeException e) {
            // 任何失败立即删占位，保证客户端重试不被自己的「处理中」挡死；删占位失败不能掩盖原始异常
            clearQuietly(API_BORROW, crid);
            throw e;
        }
    }

    /** 申领主流程（步骤 2–10）；事务边界：正常路径为状态机内部事务，降级路径为显式事务模板。 */
    private BorrowVo executeBorrow(BorrowDto dto, String crid) {
        Long uid = AuthContext.userId();
        String role = AuthContext.role();
        String bagCode = dto.getBagCode();

        // 2) 应用层限流：固定窗口 1 分钟 10 次（超限 5101）。
        //    Redis 异常时按 7.2 降级矩阵放行（应用层限流退化为 Nginx 按 IP 限流兜底），
        //    否则熔断期申领会在到达降级路径之前被限流服务挡住
        try {
            rateLimitService.assertAcquire(String.valueOf(uid), "borrow", BORROW_RATE_LIMIT_PER_MINUTE, RATE_WINDOW);
        } catch (BizException e) {
            throw e;                                    // 5101 限流命中：照常拦截
        } catch (RuntimeException e) {
            log.warn("取袋限流计数不可用（Redis 异常），按降级矩阵放行，由 Nginx 限流兜底：uid={}, 原因={}",
                    uid, e.getMessage());
        }

        // 3) 查袋：不存在 → 4106（扫码识别失败）
        Bag bag = bagMapper.selectOne(new LambdaQueryWrapper<Bag>().eq(Bag::getBagCode, bagCode));
        if (bag == null) {
            throw new BizException(ErrorCode.BAG_NOT_FOUND, "袋码不存在：" + bagCode);
        }

        // 4) 袋状态校验：不可用状态报 4107（文案区分未激活/已遗失/已报废），非空闲报 4102
        BagStatus status = BagStatus.of(bag.getStatus());
        if (status == BagStatus.LOST) {
            throw new BizException(ErrorCode.BAG_UNAVAILABLE, "该快递袋已遗失，暂不可申领");
        }
        if (status == BagStatus.SCRAPPED) {
            throw new BizException(ErrorCode.BAG_UNAVAILABLE, "该快递袋已报废，已退出循环");
        }
        if (status != BagStatus.IDLE) {
            throw new BizException(ErrorCode.BAG_ILLEGAL_TRANSITION,
                    "快递袋当前状态为「" + status.displayName() + "」，不可申领");
        }

        // 5) 调拨锁定校验：袋被未出库调拨单引用 → 4110（设计书 3.2 #2 前置）
        assertBagNotLockedByAllocation(bagCode);

        // 6) 站点校验：停用站不可取袋 → 4107
        Long stationId = bag.getStationId();
        Station station = stationId == null ? null : stationMapper.selectById(stationId);
        if (station == null) {
            throw new BizException(ErrorCode.BAG_UNAVAILABLE, "快递袋所属站点不存在，暂不可申领");
        }
        if (!Constants.STATUS_ENABLED.equals(station.getStatus())) {
            throw new BizException(ErrorCode.BAG_UNAVAILABLE, "快递袋所在站点已停用，暂不可申领");
        }

        // 7) 生成订单号
        String orderNo = BizNoUtils.orderNo();

        // 8) 预扣凭据 JSON（orderNo/stationId/bagCode/userId，见契约 9 第 8 步）
        String holdJson = JsonUtils.toJson(Map.of(
                "orderNo", orderNo, "stationId", stationId, "bagCode", bagCode, "userId", uid));

        // 9) 路径选择：熔断打开时跳过 Lua，改走 DB 行级降级路径（契约模块版第 10 节）
        if (stockService.isDegraded()) {
            log.warn("库存熔断打开，申领改走 DB 行级降级路径：uid={}, orderNo={}, bagCode={}", uid, orderNo, bagCode);
            return borrowDegraded(orderNo, crid, uid, role, bag, station);
        }

        // 9a) 正常路径：Lua 原子扣减；失败时 Lua 未扣减、未自增，无需补偿（7.2 失败分支矩阵）
        stockService.tryDeduct(orderNo, stationId, uid, holdJson);

        // 10) 状态机事务：袋 空闲→已分配 + 建订单 + 流转日志；失败按 R0 处置
        BagTransitionResult result;
        try {
            result = bagTransitionService.fire(TransitionCommand.builder()
                    .bagCode(bagCode)
                    .event(BagEvent.BORROW)
                    .operatorId(uid)
                    .operatorRole(role)
                    .stationId(stationId)
                    .targetStationId(stationId)
                    .clientRequestId(crid)
                    .attributes(Map.of("orderNo", orderNo, "stationId", stationId, "userId", uid))
                    .build());
        } catch (RuntimeException ex) {
            handleR0(ex, orderNo, crid, stationId, uid);
            throw ex;
        }

        // 逐袋幂等命中：状态机按 clientRequestId + bagCode 去重，命中时未执行任何写操作。
        // 「订单已存在」= 本请求此前已成功 → 按成功回放；「订单不存在」= 同 ID 的历史失败尝试
        // 占用了幂等键（失败尝试不释放该键，窗口 10 分钟）→ 本次未落单，按 R0 释放本次预扣并提示重试
        if (result.isReplayed()) {
            BagOrder replayOrder = findOrderByClientRequestId(crid);
            if (replayOrder != null) {
                log.info("申领幂等回放：clientRequestId={} 对应订单 {}", crid, replayOrder.getOrderNo());
                return buildBorrowVo(replayOrder, station.getName());
            }
            BizException busy = new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
            handleR0(busy, orderNo, crid, stationId, uid);
            throw busy;
        }

        // 11) 结果装配（12 步的「返回 BorrowVo」；推送已在 BORROW 伴随动作内以 afterCommit 登记，
        //     见 OrderTransitionActions#pushOrderAndStock，此处不重复推送）
        BagOrder persisted = loadPersistedOrder(orderNo, crid);
        return buildBorrowVo(persisted, station.getName());
    }

    /**
     * R0 同步补偿（设计书 7.2；契约 9 第 10 步）：<b>先按 order_no / client_request_id 回查订单</b>，
     * <b>仅本次 order_no 命中</b>才视为「本次已提交」不再补偿；确认本次未提交才执行
     * 「凭据存在才 DEL + 库存 +1 + 在持袋数 −1」的补偿 Lua，补偿成功写 {@code stock_compensation_log}
     * （client_request_id 唯一键防重复日志，撞键时忽略）；补偿失败记 ERROR 告警，
     * 交由 R4 与每日开单计数对账兜底。
     */
    private void handleR0(RuntimeException cause, String orderNo, String crid, Long stationId, Long uid) {
        // 7.2 R0「先按 client_request_id/order_no 回查」：只有本次尝试的订单号命中才算「本次已提交」。
        // 同一 clientRequestId 在幂等窗口（10 分钟）过期后的重试会生成新的 order_no——此时回查命中的
        // 是更早一次尝试的历史订单，不能据此判为已提交跳过补偿，否则本次 Lua 预扣（库存 −1、在持 +1、
        // 新 orderNo 凭据）无人回收，须等 R4 与每日对账、孤儿凭据要等 25h TTL。
        BagOrder byOrderNo = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getOrderNo, orderNo));
        if (byOrderNo != null) {
            log.warn("R0 回查确认本次订单已提交，不再补偿：orderNo={}（失败原因：{}）",
                    orderNo, cause.getMessage());
            return;
        }
        BagOrder byCrid = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getClientRequestId, crid));
        if (byCrid != null) {
            log.warn("R0 回查命中的是同 clientRequestId 的历史订单（orderNo={}，本次 orderNo={}）："
                            + "本次未提交，继续补偿本次预扣",
                    byCrid.getOrderNo(), orderNo);
        }
        boolean compensated = stockService.compensate(orderNo, stationId, uid);
        if (!compensated) {
            log.error("R0 库存同步补偿未执行（预扣凭据不存在或 Redis 异常），交由 R4 与每日开单计数对账兜底："
                    + "orderNo={}, stationId={}, uid={}", orderNo, stationId, uid);
            return;
        }
        writeCompensationLog(crid, orderNo, stationId);
    }

    /**
     * 写库存补偿日志（{@code stock_compensation_log}，审计用途）。运行在失败事务回滚之后，
     * 单条 INSERT 自动提交；client_request_id 唯一键撞车说明重复补偿，忽略即可。
     */
    private void writeCompensationLog(String crid, String orderNo, Long stationId) {
        StockCompensationLog record = new StockCompensationLog();
        record.setClientRequestId(crid);
        record.setOrderNo(orderNo);
        record.setStationId(stationId);
        record.setReason(BizNoUtils.compensationReason());
        LocalDateTime now = LocalDateTime.now();
        record.setCompensatedAt(now);
        record.setCreatedAt(now);
        try {
            stockCompensationLogMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.info("库存补偿日志已存在（重复补偿幂等），跳过写入：clientRequestId={}", crid);
        } catch (RuntimeException e) {
            // 补偿日志是审计用途，写失败不能掩盖原始业务异常
            log.error("库存补偿日志写入失败：orderNo={}, clientRequestId={}", orderNo, crid, e);
        }
    }

    /**
     * 降级路径（契约模块版第 10 节）：Redis 熔断期不改写 Redis 计数，限额退化为 DB 计数校验。
     *
     * <p><b>校验与建单必须并入同一事务</b>：先 {@code SELECT ... FOR UPDATE} 锁定该用户在持订单行
     * （并发请求在此串行化，防限额被击穿），再做条件 INSERT；库存不扣 Redis，
     * 正确性由状态机内袋的条件 UPDATE 保证，恢复后由 R4 重建计数。
     */
    private BorrowVo borrowDegraded(String orderNo, String crid, Long uid, String role, Bag bag, Station station) {
        log.warn("降级期申领（DB 行级路径）：uid={}, orderNo={}, bagCode={}, stationId={}",
                uid, orderNo, bag.getBagCode(), station.getId());
        BorrowVo vo = transactionTemplate.execute(status -> {
            // 锁定该用户可能仍持有袋的订单行：待寄件/使用中/待回收确认订单对应的袋仍处于已分配/使用中（设计书 3.6 联动表），
            // 已取消同样锁定——撤回（R1）/24h 超时取消（R2）不释放在持袋数额度（3.5），袋仍在其手上
            List<BagOrder> holdingCandidates = bagOrderMapper.selectList(new LambdaQueryWrapper<BagOrder>()
                    .eq(BagOrder::getUserId, uid)
                    .in(BagOrder::getStatus,
                            OrderStatus.PENDING_SEND.code(),
                            OrderStatus.IN_USE.code(),
                            OrderStatus.PENDING_CONFIRM.code(),
                            OrderStatus.CANCELLED.code())
                    .last("FOR UPDATE"));
            long openCount = countHeldBags(uid, holdingCandidates);
            if (openCount >= properties.getBorrow().getOpenLimit()) {
                throw new BizException(ErrorCode.HOLDING_LIMIT);
            }
            // 日频次：当日全部订单数（含已撤回/已取消，设计书 3.5）
            Long todayCount = bagOrderMapper.selectCount(new LambdaQueryWrapper<BagOrder>()
                    .eq(BagOrder::getUserId, uid)
                    .ge(BagOrder::getCreatedAt, LocalDate.now().atStartOfDay()));
            if (todayCount != null && todayCount >= properties.getBorrow().getDailyLimit()) {
                throw new BizException(ErrorCode.BORROW_DAILY_LIMIT);
            }

            BagTransitionResult result = bagTransitionService.fire(TransitionCommand.builder()
                    .bagCode(bag.getBagCode())
                    .event(BagEvent.BORROW)
                    .operatorId(uid)
                    .operatorRole(role)
                    .stationId(station.getId())
                    .targetStationId(station.getId())
                    .clientRequestId(crid)
                    .attributes(Map.of("orderNo", orderNo, "stationId", station.getId(), "userId", uid))
                    .build());

            // 逐袋幂等命中：同 ID 成功重放则返回既有订单；被历史失败尝试占键则本次未执行，提示重试
            if (result.isReplayed()) {
                BagOrder replayOrder = findOrderByClientRequestId(crid);
                if (replayOrder != null) {
                    return buildBorrowVo(replayOrder, station.getName());
                }
                throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
            }

            return buildBorrowVo(loadPersistedOrder(orderNo, crid), station.getName());
        });
        if (vo == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "申领处理结果为空，请稍后重试");
        }
        return vo;
    }

    /**
     * 在持袋数（降级口径，设计书 3.5；7.2 降级矩阵「COUNT 袋处于已分配/使用中两态的订单数」）：
     * 统计该用户当前真正持有的袋——袋处于 {已分配, 使用中}，且订单是该袋的<b>当前持有订单</b>
     * （同袋 id 最大的订单）。
     *
     * <p>两个方向的校正：① 不能按订单状态过滤——撤回（R1）/超时取消（R2）不释放额度（3.5），
     * 其订单已为已取消但袋仍是已分配，必须计入；② 不能把同一袋的历史订单重复计入——袋归还后
     * 再次流转（或已被他人借出）时旧订单仍指向该袋码，而 3.5 的账是「申领 +1、袋离开已分配/使用中才 −1」，
     * 旧订单已随袋离开这两种状态抵扣过，不能随袋重新进入时再次计入。
     */
    private long countHeldBags(Long uid, List<BagOrder> holdingCandidates) {
        if (holdingCandidates.isEmpty()) {
            return 0L;
        }
        Set<String> bagCodes = holdingCandidates.stream().map(BagOrder::getBagCode).collect(Collectors.toSet());
        List<Bag> bags = bagMapper.selectList(new LambdaQueryWrapper<Bag>().in(Bag::getBagCode, bagCodes));
        Set<String> threeStateBags = bags.stream()
                .filter(bag -> THREE_STATE_BAG_CODES.contains(bag.getStatus()))
                .map(Bag::getBagCode)
                .collect(Collectors.toSet());
        if (threeStateBags.isEmpty()) {
            return 0L;
        }
        // 当前持有订单 = 该袋 id 最大的订单：新订单只能在袋回到空闲后创建，故最大 id 即现持有者
        List<BagOrder> allOrdersOnBags = bagOrderMapper.selectList(new LambdaQueryWrapper<BagOrder>()
                .select(BagOrder::getId, BagOrder::getBagCode, BagOrder::getUserId)
                .in(BagOrder::getBagCode, bagCodes));
        Map<String, BagOrder> latestByBag = new HashMap<>();
        for (BagOrder order : allOrdersOnBags) {
            latestByBag.merge(order.getBagCode(), order, (a, b) -> a.getId() >= b.getId() ? a : b);
        }
        return latestByBag.values().stream()
                .filter(latest -> threeStateBags.contains(latest.getBagCode())
                        && Objects.equals(uid, latest.getUserId()))
                .count();
    }

    // ==================== 提交寄件（POST /borrows/{orderNo}/shipping，#3） ====================

    /**
     * 提交寄件信息（设计书 5.2、3.2 #3；契约 9 转移 #3）。
     *
     * <p>收件人手机号只在内存中过渡：落库仅存掩码与带密钥版本的 HMAC-SHA256 摘要（设计书 8.2/9.5）。
     * 成功后删预扣凭据（阶段二，7.2）；运单号联合唯一冲突转 3001 业务提示，不抛 500。
     */
    public OrderDetailVo shipping(String orderNo, ShippingDto dto, String clientRequestId) {
        String crid = requireClientRequestId(clientRequestId);
        String cached = findCompletedQuietly(API_SHIPPING, crid);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            return JsonUtils.parse(cached, OrderDetailVo.class);
        }
        markProcessingQuietly(API_SHIPPING, crid);
        try {
            Long uid = AuthContext.userId();
            String role = AuthContext.role();

            BagOrder order = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                    .eq(BagOrder::getOrderNo, orderNo));
            // 先存在性再归属，均归 2002：不向调用方泄露他人订单是否存在（防枚举）
            if (order == null || !Objects.equals(order.getUserId(), uid)) {
                throw new BizException(ErrorCode.NO_DATA_PERMISSION, "订单不存在或无权操作");
            }
            if (!OrderStatus.PENDING_SEND.code().equals(order.getStatus())) {
                throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "订单当前状态不允许提交寄件");
            }

            // 运单号预检（设计书 3.2 #3：格式合法且未重复）；并发窗口由联合唯一键 + 下方 catch 兜底
            Long duplicated = bagOrderMapper.selectCount(new LambdaQueryWrapper<BagOrder>()
                    .eq(BagOrder::getExpressCompany, dto.getExpressCompany())
                    .eq(BagOrder::getTrackingNo, dto.getTrackingNo()));
            if (duplicated != null && duplicated > 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "该运单号已被登记");
            }

            // 隐私口径：只把掩码与摘要交给下游，明文不出本方法（设计书 8.2/9.5）
            String phoneMask = MaskUtils.maskPhone(dto.getRecipientPhone());
            String phoneHash = MaskUtils.hmacPhone(dto.getRecipientPhone(),
                    properties.getSecurity().getRecipientHmacKey(),
                    properties.getSecurity().getRecipientHmacKeyVersion());

            Map<String, Object> attributes = new HashMap<>();
            attributes.put("orderNo", orderNo);
            attributes.put("expressCompany", dto.getExpressCompany());
            attributes.put("trackingNo", dto.getTrackingNo());
            attributes.put("recipientPhoneMask", phoneMask);
            attributes.put("recipientPhoneHash", phoneHash);
            attributes.put("userId", uid);

            BagTransitionResult result;
            try {
                result = bagTransitionService.fire(TransitionCommand.builder()
                        .bagCode(order.getBagCode())
                        .event(BagEvent.SHIP)
                        .operatorId(uid)
                        .operatorRole(role)
                        .stationId(order.getStationId())
                        .clientRequestId(crid)
                        .attributes(attributes)
                        .build());
            } catch (DuplicateKeyException e) {
                // (express_company, tracking_no) 联合唯一：并发重复登记 → 业务提示
                throw new BizException(ErrorCode.PARAM_INVALID, "该运单号已被登记");
            }

            // 逐袋幂等命中：订单已不在待寄件 = 寄件此前已生效（回放）；仍是待寄件 =
            // 同 ID 的历史失败尝试占用了幂等键，本次未执行，提示稍后重试（不把未执行报成成功）
            if (result.isReplayed()) {
                BagOrder current = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                        .eq(BagOrder::getOrderNo, orderNo));
                if (current == null || OrderStatus.PENDING_SEND.code().equals(current.getStatus())) {
                    throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
                }
            }

            // 阶段二（7.2）：DB 事务成功后删预扣凭据
            stockService.deleteHold(orderNo);

            OrderDetailVo vo = orderService.detail(orderNo);
            markCompletedQuietly(API_SHIPPING, crid, JsonUtils.toJson(vo));
            return vo;
        } catch (RuntimeException e) {
            clearQuietly(API_SHIPPING, crid);
            throw e;
        }
    }

    // ==================== 撤回申领（DELETE /borrows/{orderNo}，R1） ====================

    /**
     * 撤回申领（设计书 5.2「撤回申领」、3.2 订单级操作说明、7.2 R1）。
     *
     * <p>只把订单置已取消并写取消时间、删预扣凭据：<b>不改袋状态、不减在持袋数额度</b>——
     * 袋仍在用户手上，等待站点按 #4 未使用回收 / #5 回收确认闭环时才计数 −1。
     */
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(String orderNo, String clientRequestId) {
        String crid = requireClientRequestId(clientRequestId);
        String cached = findCompletedQuietly(API_WITHDRAW, crid);
        if (IdempotentService.PROCESSING.equals(cached)) {
            throw new BizException(ErrorCode.STOCK_RECONCILING, "请求处理中，请稍后重试");
        }
        if (cached != null) {
            return;                         // 幂等回放：首次已撤回，无需重复执行
        }
        markProcessingQuietly(API_WITHDRAW, crid);
        try {
            Long uid = AuthContext.userId();
            BagOrder order = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                    .eq(BagOrder::getOrderNo, orderNo));
            if (order == null || !Objects.equals(order.getUserId(), uid)) {
                throw new BizException(ErrorCode.NO_DATA_PERMISSION, "订单不存在或无权操作");
            }
            if (!OrderStatus.PENDING_SEND.code().equals(order.getStatus())) {
                throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "仅待寄件订单可撤回");
            }

            BagOrder update = new BagOrder();
            update.setId(order.getId());
            update.setStatus(OrderStatus.CANCELLED.code());
            update.setCancelledAt(LocalDateTime.now());
            update.setVersion(order.getVersion());
            if (bagOrderMapper.updateById(update) == 0) {
                throw new BizException(ErrorCode.ORDER_STATE_NOT_ALLOWED, "订单状态已被其他请求变更，请刷新后重试");
            }

            // R1：仅删预扣凭据；不回补库存、不减在持袋数（7.2）
            stockService.deleteHold(orderNo);
            markCompletedQuietly(API_WITHDRAW, crid,
                    JsonUtils.toJson(Map.of("orderNo", orderNo, "status", OrderStatus.CANCELLED.code())));
        } catch (RuntimeException e) {
            clearQuietly(API_WITHDRAW, crid);
            throw e;
        }
    }

    // ==================== 私有工具 ====================

    /** 调拨锁定校验：袋被「待出库/在途」调拨单的待出库明细引用时抛 4110（设计书 3.2 #2）。 */
    private void assertBagNotLockedByAllocation(String bagCode) {
        List<AllocationDetail> details = allocationDetailMapper.selectList(new LambdaQueryWrapper<AllocationDetail>()
                .eq(AllocationDetail::getBagCode, bagCode)
                .eq(AllocationDetail::getStatus, ALLOCATION_DETAIL_PENDING_DISPATCH));
        if (details.isEmpty()) {
            return;
        }
        List<Long> allocationIds = details.stream()
                .map(AllocationDetail::getAllocationId)
                .distinct()
                .toList();
        Long locked = allocationOrderMapper.selectCount(new LambdaQueryWrapper<AllocationOrder>()
                .in(AllocationOrder::getId, allocationIds)
                .in(AllocationOrder::getStatus, ALLOCATION_STATUS_PENDING_DISPATCH, ALLOCATION_STATUS_IN_TRANSIT));
        if (locked != null && locked > 0) {
            throw new BizException(ErrorCode.BAG_LOCKED_BY_ALLOCATION);
        }
    }

    /** 按幂等 ID 回查订单（状态机幂等回放判定用）：命中说明本请求此前已成功落单。 */
    private BagOrder findOrderByClientRequestId(String crid) {
        return bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getClientRequestId, crid));
    }

    /** 装载刚提交/回放的订单：先按订单号、再按幂等 ID 回查；都查不到属数据异常。 */
    private BagOrder loadPersistedOrder(String orderNo, String crid) {
        BagOrder order = bagOrderMapper.selectOne(new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getOrderNo, orderNo));
        if (order == null) {
            order = findOrderByClientRequestId(crid);
        }
        if (order == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "订单落库结果不可见，请稍后重试");
        }
        return order;
    }

    /** 组装申领出参（契约 9 第 12 步字段口径）。 */
    private BorrowVo buildBorrowVo(BagOrder order, String stationName) {
        BorrowVo vo = new BorrowVo();
        vo.setOrderNo(order.getOrderNo());
        vo.setBagCode(order.getBagCode());
        vo.setStationId(order.getStationId());
        vo.setStationName(stationName);
        vo.setStatus(order.getStatus());
        vo.setCreatedAt(order.getCreatedAt());
        LocalDateTime deadline = order.getCreatedAt() == null
                ? LocalDateTime.now().plus(PENDING_SEND_TTL)
                : order.getCreatedAt().plus(PENDING_SEND_TTL);
        vo.setExpiresHint(String.format(Locale.ROOT,
                "请在 %s 前提交寄件信息，逾期订单将自动取消", deadline.format(HINT_TIME_FORMATTER)));
        return vo;
    }

    /** 取幂等请求头并校验 UUID 格式；缺失或格式错 → 3001（契约模块版第 4 节）。 */
    private static String requireClientRequestId(String clientRequestId) {
        if (clientRequestId == null || !clientRequestId.matches(Constants.UUID_REGEX)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少或非法的 clientRequestId 请求头");
        }
        return clientRequestId;
    }

    // ==================== 幂等层的降级包装（Redis 异常时不阻断申领主链路） ====================

    /**
     * 幂等查询（降级口径见设计书 7.2「幂等（clientRequestId）：由 bag_order.client_request_id
     * 与 stock_compensation_log 唯一键兜底」）：Redis 异常记 WARN 并按未命中处理，
     * 让请求继续走到 {@code isDegraded()} 判定——否则熔断期降级路径永远无法激活。
     */
    private String findCompletedQuietly(String api, String crid) {
        try {
            return idempotentService.findCompleted(api, crid);
        } catch (RuntimeException e) {
            log.warn("幂等查询不可用（Redis 异常），按未命中继续，由 DB 唯一键兜底：api={}, crid={}, 原因={}",
                    api, crid, e.getMessage());
            return null;
        }
    }

    /** 幂等占位写入：Redis 异常记 WARN 跳过，不阻断业务。 */
    private void markProcessingQuietly(String api, String crid) {
        try {
            idempotentService.markProcessing(api, crid);
        } catch (RuntimeException e) {
            log.warn("幂等占位写入失败（Redis 异常），跳过：api={}, crid={}, 原因={}", api, crid, e.getMessage());
        }
    }

    /** 幂等结果写回：业务已成功，写失败只记 WARN，绝不把成功响应翻转为失败。 */
    private void markCompletedQuietly(String api, String crid, String resultJson) {
        try {
            idempotentService.markCompleted(api, crid, resultJson);
        } catch (RuntimeException e) {
            log.warn("幂等结果写回失败（Redis 异常），不影响本次成功响应：api={}, crid={}, 原因={}",
                    api, crid, e.getMessage());
        }
    }

    /** 失败清占位：删除失败只记 WARN，不能掩盖原始业务异常。 */
    private void clearQuietly(String api, String crid) {
        try {
            idempotentService.clear(api, crid);
        } catch (RuntimeException e) {
            log.warn("幂等占位清理失败（Redis 异常）：api={}, crid={}, 原因={}", api, crid, e.getMessage());
        }
    }
}
