package com.sems.module.bag.service.action;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sems.common.util.MaskUtils;
import com.sems.infra.CounterService;
import com.sems.infra.StockService;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.entity.DisinfectDetail;
import com.sems.module.bag.entity.RepairRecord;
import com.sems.module.bag.entity.ScrapRecord;
import com.sems.module.bag.entity.StockRecord;
import com.sems.module.bag.mapper.DisinfectDetailMapper;
import com.sems.module.bag.mapper.RepairRecordMapper;
import com.sems.module.bag.mapper.ScrapRecordMapper;
import com.sems.module.bag.mapper.StockRecordMapper;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.entity.LiabilityRecord;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.order.mapper.LiabilityRecordMapper;
import com.sems.module.station.entity.AllocationDetail;
import com.sems.module.station.entity.AllocationOrder;
import com.sems.module.station.mapper.AllocationDetailMapper;
import com.sems.module.station.mapper.AllocationOrderMapper;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.statemachine.BagTransitionContext;
import com.sems.module.statemachine.OrderStatus;
import com.sems.module.statemachine.TransitionAction;
import com.sems.push.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 快递袋模块的伴随动作集合（见《系统设计说明书》3.2 状态转移表「伴随动作」列、
 * 契约模块版第 2 节「一事件只能有一个实现 Bean」）。
 *
 * <p>本类负责的事件：{@code ACTIVATE / SHELVE / REPAIR_START / REPAIR_DONE / SCRAP /
 * SCRAP_DIRECT / DISINFECT_PASS / DISINFECT_FAIL / FOUND / STATUS_CORRECT} 共十个；
 * 其余事件（申领/寄件/归还/回收/调拨）由 order 与 station 模块实现，互不越界。
 *
 * <p><b>职责边界</b>：状态机已完成「袋状态条件 UPDATE + 写流转日志」；本类只做其余伴生副作用
 * （业务记录表写入、业务记录闭环、Redis 库存/计数增减）。<b>不改袋状态</b>，也不重复注册推送——
 * 可用库存主题的推送由状态机按前后态统一登记（{@code BagTransitionService#registerStockPush}）；
 * 唯一例外是 #21 跨站修正：状态机只登记库存真实增减（±1）的一侧，本类补推增量为 0 的一侧，
 * 两侧条件互补、同一站点只会收到一条消息（3.2 #21「跨站一律…推送两站」与 7.3 推送时机）。
 *
 * <p><b>为什么是内部类而不是一个大 Bean：</b>契约要求「一事件一个 {@link TransitionAction} Bean」，
 * 而 {@link TransitionAction#event()} 只能声明单个事件；本文件用「外侧共享实现 + 十个内部 Bean」
 * 的结构，既满足注册表约束（每个事件恰好一个实现），又让同一模块的伴随动作集中在单文件内演进。
 *
 * <p>关于 in-transaction 与 Redis：伴随动作与状态流转同事务执行；Redis 的库存增减先落 Redis、
 * DB 事务失败时按设计书 7.2 的回补路径（R0–R4）收敛，故此处直接调用 {@link StockService}。
 */
@Component
public class BagTransitionActions {

    private static final Logger log = LoggerFactory.getLogger(BagTransitionActions.class);

    /** stock_record.type 码值（与 DDL 注释一致）：IN 入库 / OUT 出库 / SCRAP_OUT 报废出库。 */
    public static final String STOCK_TYPE_IN = "IN";
    public static final String STOCK_TYPE_OUT = "OUT";
    public static final String STOCK_TYPE_SCRAP_OUT = "SCRAP_OUT";

    /** repair_record.result 码值（与 DDL 注释一致）：修复 / 无法修复。 */
    public static final String REPAIR_RESULT_FIXED = "修复";
    public static final String REPAIR_RESULT_UNFIXABLE = "无法修复";

    /** 流转命令 attributes 中承载"维修结果"的键（仅本模块 Service 与动作之间使用）。 */
    public static final String ATTR_REPAIR_RESULT = "repairResult";

    /** disinfect_detail.result 码值（与 DDL 注释一致）。 */
    private static final String DISINFECT_RESULT_FAIL = "不合格";

    /** liability_record.biz_type / type / status 码值（与 DDL 注释一致）。 */
    private static final String LIABILITY_BIZ_TYPE_ORDER = "ORDER";
    private static final String LIABILITY_TYPE_LOST = "遗失";
    private static final String LIABILITY_TYPE_LOST_IN_TRANSIT = "在途遗失";
    private static final String LIABILITY_STATUS_CONFIRMED = "CONFIRMED";

    /** allocation_detail 袋级状态码值（#20 回溯在途遗失责任记录用，见设计书 8.2）。 */
    private static final String ALLOCATION_DETAIL_LOST = "LOST";

    /** 备注/描述列的落库截断长度：truncate 会另加省略号，故按列宽 −1 传（见 MaskUtils 口径）。 */
    private static final int REMARK_MAX = 199;
    private static final int TEXT_MAX = 254;

    /** 时序文案格式（仅用于责任记录注记的可读时间）。 */
    private static final DateTimeFormatter NOTE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StockService stockService;
    private final CounterService counterService;
    private final StockRecordMapper stockRecordMapper;
    private final RepairRecordMapper repairRecordMapper;
    private final ScrapRecordMapper scrapRecordMapper;
    private final DisinfectDetailMapper disinfectDetailMapper;
    private final BagOrderMapper bagOrderMapper;
    private final LiabilityRecordMapper liabilityRecordMapper;
    private final AllocationDetailMapper allocationDetailMapper;
    private final AllocationOrderMapper allocationOrderMapper;
    private final PushService pushService;

    public BagTransitionActions(StockService stockService,
                                CounterService counterService,
                                StockRecordMapper stockRecordMapper,
                                RepairRecordMapper repairRecordMapper,
                                ScrapRecordMapper scrapRecordMapper,
                                DisinfectDetailMapper disinfectDetailMapper,
                                BagOrderMapper bagOrderMapper,
                                LiabilityRecordMapper liabilityRecordMapper,
                                AllocationDetailMapper allocationDetailMapper,
                                AllocationOrderMapper allocationOrderMapper,
                                PushService pushService) {
        this.stockService = stockService;
        this.counterService = counterService;
        this.stockRecordMapper = stockRecordMapper;
        this.repairRecordMapper = repairRecordMapper;
        this.scrapRecordMapper = scrapRecordMapper;
        this.disinfectDetailMapper = disinfectDetailMapper;
        this.bagOrderMapper = bagOrderMapper;
        this.liabilityRecordMapper = liabilityRecordMapper;
        this.allocationDetailMapper = allocationDetailMapper;
        this.allocationOrderMapper = allocationOrderMapper;
        this.pushService = pushService;
    }

    /** 事件分派入口：switch 到各事件的处理方法（十个事件之外的输入属配置错误，直接失败）。 */
    void dispatch(BagEvent event, BagTransitionContext ctx) {
        switch (event) {
            case ACTIVATE -> onActivate(ctx);
            case SHELVE -> onShelve(ctx);
            case REPAIR_START -> onRepairStart(ctx);
            case REPAIR_DONE -> onRepairDone(ctx);
            case SCRAP -> onScrap(ctx);
            case SCRAP_DIRECT -> onScrapDirect(ctx);
            case DISINFECT_PASS -> onDisinfectPass(ctx);
            case DISINFECT_FAIL -> onDisinfectFail(ctx);
            case FOUND -> onFound(ctx);
            case STATUS_CORRECT -> onStatusCorrect(ctx);
            default -> throw new IllegalStateException("BagTransitionActions 未覆盖的事件：" + event.code());
        }
    }

    // ==================== #1 激活上架 ====================

    /**
     * 激活上架（未激活→空闲）：写入库流水 + 站点可用库存 +1。
     * 推送由状态机按「非空闲 → 空闲」统一登记（设计书 7.3 站点库存主题）。
     */
    private void onActivate(BagTransitionContext ctx) {
        Bag bag = ctx.getBag();
        writeStockRecord(bag.getStationId(), bag.getBagCode(), STOCK_TYPE_IN, ctx.getOperatorId(), "激活上架");
        stockService.increaseAvailable(bag.getStationId(), 1);
    }

    // ==================== #11 上架入库 ====================

    /**
     * 上架入库（可复用→空闲）：写入库流水 + 站点可用库存 +1。
     * {@code use_count ≥ max_use_count} 的拒绝（4108）由 Service 层前置校验，动作不重复判断。
     */
    private void onShelve(BagTransitionContext ctx) {
        Bag bag = ctx.getBag();
        writeStockRecord(bag.getStationId(), bag.getBagCode(), STOCK_TYPE_IN, ctx.getOperatorId(), "上架入库");
        stockService.increaseAvailable(bag.getStationId(), 1);
    }

    // ==================== #12 转维修 ====================

    /**
     * 转维修（空闲/待消毒/可复用→维修中）：写维修登记（破损描述取命令备注）；
     * 前态=空闲时站点可用库存 −1（设计书 3.2 #12）。
     */
    private void onRepairStart(BagTransitionContext ctx) {
        Bag bag = ctx.getBag();
        RepairRecord record = new RepairRecord();
        record.setBagCode(bag.getBagCode());
        record.setReporterId(ctx.getOperatorId());
        record.setDescription(truncateOr(description(ctx, "破损转维修"), TEXT_MAX));
        record.setStartTime(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());
        repairRecordMapper.insert(record);

        if (BagStatus.of(bag.getStatus()) == BagStatus.IDLE) {
            stockService.increaseAvailable(bag.getStationId(), -1);
        }
    }

    // ==================== #13 修复入库 ====================

    /**
     * 修复入库（维修中→可复用）：回填维修记录的完成时间、结果与完成操作人。
     * 结果取命令 attributes 的 {@code repairResult}（Service 传入实际选择），缺省按"修复"。
     */
    private void onRepairDone(BagTransitionContext ctx) {
        String result = attributeString(ctx, ATTR_REPAIR_RESULT, REPAIR_RESULT_FIXED);
        updateOpenRepairRecord(ctx.getBag().getBagCode(), result, ctx.getOperatorId());
    }

    // ==================== #14 报废（维修路径） ====================

    /**
     * 报废（维修中→报废）：先回填维修完成记录（结果=无法修复；超限强制报废时取实际维修结果），
     * 再写报废记录。报废属审计重点核查项（设计书 7.6）。
     */
    private void onScrap(BagTransitionContext ctx) {
        Bag bag = ctx.getBag();
        String result = attributeString(ctx, ATTR_REPAIR_RESULT, REPAIR_RESULT_UNFIXABLE);
        updateOpenRepairRecord(bag.getBagCode(), result, ctx.getOperatorId());
        writeScrapRecord(ctx, bag.getStationId(), "无法修复或复用超限");
        if (BagStatus.of(bag.getStatus()) == BagStatus.IDLE) {
            // #14 前态为维修中，此分支实际不可达；保留判断与 #15 同口径，防御异常数据
            stockService.increaseAvailable(bag.getStationId(), -1);
        }
    }

    // ==================== #15 直接报废 ====================

    /**
     * 直接报废（空闲/待消毒/可复用→报废）：写报废记录；前态=空闲时站点可用库存 −1。
     * 这是审计重点核查项（设计书 7.6），Controller 侧以 action="直接报废" 入审计。
     */
    private void onScrapDirect(BagTransitionContext ctx) {
        Bag bag = ctx.getBag();
        writeScrapRecord(ctx, bag.getStationId(), "直接报废登记");
        if (BagStatus.of(bag.getStatus()) == BagStatus.IDLE) {
            stockService.increaseAvailable(bag.getStationId(), -1);
        }
    }

    // ==================== #9 消毒完成（合格） ====================

    /**
     * 消毒完成（合格）（待消毒→可复用）：无额外伴随动作——消毒批次与明细
     * （含合格结果）已由消毒登记流程在触发本事件前落库（契约模块版 8.3），
     * 库存不增减（可复用不计入可用库存，见设计书 3.1）。本 Bean 仅显式声明归属，保持注册表完整。
     */
    private void onDisinfectPass(BagTransitionContext ctx) {
        log.debug("消毒合格流转无额外伴随动作：袋={}", ctx.getBag().getBagCode());
    }

    // ==================== #10 消毒完成（不合格） ====================

    /**
     * 消毒完成（不合格）（待消毒→维修中）：补写维修登记，破损描述取该袋最新一条不合格消毒明细的
     * 缺陷说明（明细由消毒登记流程预写，设计书 3.2 #10 伴随动作）。
     */
    private void onDisinfectFail(BagTransitionContext ctx) {
        Bag bag = ctx.getBag();
        String defect = latestDefectDescription(bag.getBagCode());
        String description = defect != null ? defect : description(ctx, "消毒不合格转维修");

        RepairRecord record = new RepairRecord();
        record.setBagCode(bag.getBagCode());
        record.setReporterId(ctx.getOperatorId());
        record.setDescription(truncateOr(description, TEXT_MAX));
        record.setStartTime(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());
        repairRecordMapper.insert(record);
    }

    // ==================== #20 找回 ====================

    /**
     * 找回（已遗失→待消毒）：给责任记录追加「已找回」注记（写 note，不改写既有结论，
     * 见设计书 3.2 #20 与 8.2）；站点归属由状态机改为找回登记站点；库存不变（转待消毒尚未可用），
     * 复用次数不重复计。
     *
     * <p>{@code liability_record} 没有袋码列，只能按业务单号回溯：先找该袋的遗失订单（#18），
     * 再回退到在途遗失的调拨单（#19）。两条链路都找不到时记 WARN 跳过，不影响找回本身。
     */
    private void onFound(BagTransitionContext ctx) {
        String bagCode = ctx.getBag().getBagCode();
        LiabilityLocator locator = locateLiability(bagCode);
        if (locator == null) {
            log.warn("袋 {} 找回：未按遗失订单/在途遗失调拨单定位到责任记录，跳过注记", bagCode);
            return;
        }
        List<LiabilityRecord> records = liabilityRecordMapper.selectList(new LambdaQueryWrapper<LiabilityRecord>()
                .eq(LiabilityRecord::getBizNo, locator.bizNo())
                .eq(LiabilityRecord::getType, locator.type())
                .orderByDesc(LiabilityRecord::getId));
        if (records.isEmpty()) {
            log.warn("袋 {} 找回：责任记录不存在（bizNo={}, type={}），跳过注记",
                    bagCode, locator.bizNo(), locator.type());
            return;
        }
        LiabilityRecord record = records.get(0);
        LiabilityRecord update = new LiabilityRecord();
        update.setId(record.getId());
        update.setNote(truncateOr(appendRecoveredNote(record.getNote(), ctx), TEXT_MAX));
        liabilityRecordMapper.updateById(update);
    }

    // ==================== #21 状态修正 ====================

    /**
     * 状态修正（任意非终态→白名单指定态）：库存按归属站点是否变更判定（设计书 3.2 #21）；
     * 源态∈{已分配,使用中,回收在途}时闭环订单并释放在持袋数；按目标态补写对应业务记录。
     *
     * <p>库存公式（源站/目标站各自判定，可为 0；仅同站且同态不动）：
     * <pre>
     * 源站 −1  当且仅当 源态=空闲 且（跨站 或 目标态≠空闲）
     * 目标站 +1 当且仅当 目标态=空闲 且（跨站 或 源态≠空闲）
     * </pre>
     * 推送：状态机按同一公式登记「库存真实增减」的一侧（±1，见 {@code registerStockPush}）；
     * #21 要求跨站修正一律推送两站（3.2 #21 / 7.3），因此增量为 0 的一侧由本类补推，互不重复。
     */
    private void onStatusCorrect(BagTransitionContext ctx) {
        Bag bag = ctx.getBag();
        BagStatus from = BagStatus.of(bag.getStatus());
        BagStatus to = ctx.getCmd().getTargetStatus();
        Long sourceStation = bag.getStationId();
        Long targetStation = ctx.getCmd().getTargetStationId() != null
                ? ctx.getCmd().getTargetStationId() : sourceStation;
        boolean stationMoved = !Objects.equals(sourceStation, targetStation);

        // 1) 两站库存调整（#21 公式）
        int sourceDelta = from.countsAsAvailable() && (stationMoved || !to.countsAsAvailable()) ? -1 : 0;
        int targetDelta = to.countsAsAvailable() && (stationMoved || !from.countsAsAvailable()) ? 1 : 0;
        // 源站永远改源站的数；目标站增量：跨站改目标站，同站改回源站（如同站 维修中→空闲 的 +1）。
        // delta=0 时 increaseAvailable 内部直接返回，不会产生多余写。
        stockService.increaseAvailable(sourceStation, sourceDelta);
        stockService.increaseAvailable(stationMoved ? targetStation : sourceStation, targetDelta);

        // 1.1) 跨站一律推送两站（3.2 #21 表后说明、7.3「状态修正…跨站推送两站」）：状态机只推
        //      「库存真实增减」的一侧（推 −1 则源站已覆盖、推 +1 则目标站已覆盖），这里补推增量为 0
        //      的一侧。两侧条件互补：同一站点只会收到一条消息，不产生重复推送（推送纪律见类注释）
        if (stationMoved && !from.countsAsAvailable()) {
            Map<String, Object> sourceData = stockCorrectionData(ctx, sourceStation, 0);
            pushService.afterCommit(() -> pushService.pushStationStock(sourceStation, sourceData));
        }
        if (stationMoved && !to.countsAsAvailable()) {
            Map<String, Object> targetData = stockCorrectionData(ctx, targetStation, 0);
            pushService.afterCommit(() -> pushService.pushStationStock(targetStation, targetData));
        }

        // 2) 源态∈三态 → 闭环绑定订单并释放在持袋数（订单转已遗失或转已取消，见 #21 口径）
        BagOrder bindingOrder = null;
        if (from == BagStatus.ALLOCATED || from == BagStatus.IN_USE || from == BagStatus.RETURNING) {
            bindingOrder = findBindingOrder(bag.getBagCode());
            if (bindingOrder != null) {
                updateOrderForCorrection(bindingOrder, to);
                // 在持袋数 −1 一律走 CounterService（内部做下限截断，见契约模块版第 3 节）
                counterService.decrementOpenFloor0(bindingOrder.getUserId());
            } else {
                log.warn("袋 {} 源态为 {} 但未找到绑定订单，跳过订单闭环与在持袋数递减（#21）",
                        bag.getBagCode(), from.code());
            }
        }

        // 3) 按目标态补写业务记录
        if (to == BagStatus.REPAIRING) {
            writeRepairRecordForCorrection(ctx);
        } else if (to == BagStatus.SCRAPPED) {
            writeScrapRecord(ctx, sourceStation, "状态修正转入报废");
        } else if (to == BagStatus.LOST) {
            writeLiabilityRecordForCorrection(ctx, bindingOrder);
        }
    }

    // ==================== 内部实现 ====================

    /** 写出入库流水（只插入；备注按列宽截断，超长自动加省略号）。 */
    private void writeStockRecord(Long stationId, String bagCode, String type, Long operatorId, String remark) {
        StockRecord record = new StockRecord();
        record.setStationId(stationId);
        record.setBagCode(bagCode);
        record.setType(type);
        record.setOperatorId(operatorId == null ? 0L : operatorId);
        record.setRemark(truncateOr(remark, REMARK_MAX));
        record.setCreatedAt(LocalDateTime.now());
        stockRecordMapper.insert(record);
    }

    /** 写报废记录（报废站点=流转前袋所在站；原因取命令备注，缺省用兜底文案）。 */
    private void writeScrapRecord(BagTransitionContext ctx, Long stationId, String fallbackReason) {
        ScrapRecord record = new ScrapRecord();
        record.setBagCode(ctx.getBag().getBagCode());
        record.setStationId(stationId);
        record.setReason(truncateOr(description(ctx, fallbackReason), TEXT_MAX));
        record.setScrapTime(LocalDateTime.now());
        record.setOperatorId(ctx.getOperatorId() == null ? 0L : ctx.getOperatorId());
        record.setCreatedAt(LocalDateTime.now());
        scrapRecordMapper.insert(record);
    }

    /** #21 转入维修：登记人=修正操作人，破损描述取修正原因（设计书 3.2 #21）。 */
    private void writeRepairRecordForCorrection(BagTransitionContext ctx) {
        RepairRecord record = new RepairRecord();
        record.setBagCode(ctx.getBag().getBagCode());
        record.setReporterId(ctx.getOperatorId());
        record.setDescription(truncateOr(description(ctx, "状态修正转入维修"), TEXT_MAX));
        record.setStartTime(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());
        repairRecordMapper.insert(record);
    }

    /** #21 转入遗失：写责任记录（有绑定订单才有责任主体；结论取修正原因，认定人=修正操作人）。 */
    private void writeLiabilityRecordForCorrection(BagTransitionContext ctx, BagOrder order) {
        if (order == null) {
            log.warn("袋 {} 状态修正转入遗失但没有绑定订单，跳过责任记录（无责任主体可关联）",
                    ctx.getBag().getBagCode());
            return;
        }
        LiabilityRecord record = new LiabilityRecord();
        record.setBizType(LIABILITY_BIZ_TYPE_ORDER);
        record.setBizNo(order.getOrderNo());
        record.setType(LIABILITY_TYPE_LOST);
        record.setResponsibleUserId(order.getUserId());
        record.setStatus(LIABILITY_STATUS_CONFIRMED);
        record.setConclusion(truncateOr(description(ctx, "状态修正转入遗失"), TEXT_MAX));
        record.setDecidedBy(ctx.getOperatorId());
        record.setDecidedAt(LocalDateTime.now());
        record.setNote(truncateOr("状态修正转入遗失（差异单号 " + nullToDash(ctx.getCmd().getRefCheckNo()) + "）", TEXT_MAX));
        record.setCreatedAt(LocalDateTime.now());
        liabilityRecordMapper.insert(record);
    }

    /**
     * 组装 #21 跨站修正补推的库存推送载荷：字段与状态机 {@code registerStockPush} 的登记口径
     * 保持一致（bagCode/event/fromStatus/toStatus/stationId/stockDelta），前端按同一形状消费。
     */
    private static Map<String, Object> stockCorrectionData(BagTransitionContext ctx, Long stationId, int delta) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bagCode", ctx.getCmd().getBagCode());
        data.put("event", ctx.getCmd().getEvent().code());
        data.put("fromStatus", ctx.getBag().getStatus());
        data.put("toStatus", ctx.getCmd().getTargetStatus() == null ? null : ctx.getCmd().getTargetStatus().code());
        data.put("stationId", stationId);
        data.put("stockDelta", delta);
        return data;
    }

    /**
     * 闭环绑定订单（#21）：目标态=已遗失 → 订单转已遗失（含已取消订单终态覆盖，口径同 #18）；
     * 其余目标态（空闲/维修中/报废）→ 订单转已取消（终结后才允许修正）。
     *
     * <p>订单可能已被 R1/R2 取消（袋仍在用户手上、在持额度不释放）：此时状态无需改写，
     * 但"袋离开三态"的计数释放仍由调用方执行。乐观锁冲突只记 WARN，由条件更新与人工核查兜底，
     * 不阻断状态修正本身。
     */
    private void updateOrderForCorrection(BagOrder order, BagStatus target) {
        LocalDateTime now = LocalDateTime.now();
        OrderStatus current = OrderStatus.of(order.getStatus());
        BagOrder update = new BagOrder();
        update.setId(order.getId());
        update.setVersion(order.getVersion());

        if (target == BagStatus.LOST) {
            if (current == OrderStatus.LOST) {
                return;
            }
            update.setStatus(OrderStatus.LOST.code());
            update.setLostAt(now);
        } else {
            if (current == OrderStatus.CANCELLED) {
                return;
            }
            if (current == OrderStatus.PENDING_SEND
                    || current == OrderStatus.IN_USE
                    || current == OrderStatus.PENDING_CONFIRM) {
                update.setStatus(OrderStatus.CANCELLED.code());
                update.setCancelledAt(now);
            } else {
                log.warn("订单 {} 状态为 {}，与袋源态不一致，跳过订单状态改写（#21）",
                        order.getOrderNo(), current.code());
                return;
            }
        }
        if (bagOrderMapper.updateById(update) == 0) {
            log.warn("订单 {} 状态改写未命中（并发变更或版本不一致），交由条件更新与人工核查兜底",
                    order.getOrderNo());
        }
    }

    /** 查绑定该袋的最近订单（含已取消：R1/R2 取消后袋仍已分配、等待 #4/#5 或 #21 闭环）。 */
    private BagOrder findBindingOrder(String bagCode) {
        List<BagOrder> orders = bagOrderMapper.selectList(new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getBagCode, bagCode)
                .in(BagOrder::getStatus,
                        OrderStatus.PENDING_SEND.code(),
                        OrderStatus.IN_USE.code(),
                        OrderStatus.PENDING_CONFIRM.code(),
                        OrderStatus.CANCELLED.code())
                .orderByDesc(BagOrder::getId));
        return orders.isEmpty() ? null : orders.get(0);
    }

    /** 回填未完成维修记录的完成字段；记录不存在（历史数据）时只记 WARN，不阻断流转。 */
    private void updateOpenRepairRecord(String bagCode, String result, Long operatorId) {
        List<RepairRecord> openRecords = repairRecordMapper.selectList(new LambdaQueryWrapper<RepairRecord>()
                .eq(RepairRecord::getBagCode, bagCode)
                .isNull(RepairRecord::getEndTime)
                .orderByDesc(RepairRecord::getId));
        if (openRecords.isEmpty()) {
            log.warn("袋 {} 未找到未完成的维修记录，跳过维修完成回填", bagCode);
            return;
        }
        RepairRecord open = openRecords.get(0);
        RepairRecord update = new RepairRecord();
        update.setId(open.getId());
        update.setEndTime(LocalDateTime.now());
        update.setResult(result);
        update.setOperatorId(operatorId);
        repairRecordMapper.updateById(update);
    }

    /** 取该袋最新一条不合格消毒明细的缺陷说明（消毒明细由消毒登记流程在触发事件前写入）。 */
    private String latestDefectDescription(String bagCode) {
        List<DisinfectDetail> details = disinfectDetailMapper.selectList(new LambdaQueryWrapper<DisinfectDetail>()
                .eq(DisinfectDetail::getBagCode, bagCode)
                .eq(DisinfectDetail::getResult, DISINFECT_RESULT_FAIL)
                .orderByDesc(DisinfectDetail::getId));
        for (DisinfectDetail detail : details) {
            if (detail.getDefectDesc() != null && !detail.getDefectDesc().isBlank()) {
                return detail.getDefectDesc();
            }
        }
        return null;
    }

    /** 责任记录定位结果（业务单号 + 责任类型）。 */
    private record LiabilityLocator(String bizNo, String type) {
    }

    /** 按业务链路回溯责任记录：先遗失订单（#18），再在途遗调的调拨单（#19）。 */
    private LiabilityLocator locateLiability(String bagCode) {
        List<BagOrder> lostOrders = bagOrderMapper.selectList(new LambdaQueryWrapper<BagOrder>()
                .eq(BagOrder::getBagCode, bagCode)
                .eq(BagOrder::getStatus, OrderStatus.LOST.code())
                .orderByDesc(BagOrder::getId));
        if (!lostOrders.isEmpty()) {
            return new LiabilityLocator(lostOrders.get(0).getOrderNo(), LIABILITY_TYPE_LOST);
        }
        List<AllocationDetail> lostDetails = allocationDetailMapper.selectList(new LambdaQueryWrapper<AllocationDetail>()
                .eq(AllocationDetail::getBagCode, bagCode)
                .eq(AllocationDetail::getStatus, ALLOCATION_DETAIL_LOST)
                .orderByDesc(AllocationDetail::getId));
        if (!lostDetails.isEmpty()) {
            AllocationOrder order = allocationOrderMapper.selectById(lostDetails.get(0).getAllocationId());
            if (order != null) {
                return new LiabilityLocator(order.getAllocationNo(), LIABILITY_TYPE_LOST_IN_TRANSIT);
            }
        }
        return null;
    }

    /** 追加「已找回」注记：保留既有处置结论，注记按时间累积（设计书 8.2「追加说明，不改写结论」）。 */
    private String appendRecoveredNote(String existing, BagTransitionContext ctx) {
        String addition = "已找回：" + description(ctx, "—")
                + "（操作人 " + ctx.getOperatorId() + "，" + LocalDateTime.now().format(NOTE_TIME) + "）";
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        return existing + "；" + addition;
    }

    /** 取命令备注；缺省时回退为兜底文案（NOT NULL 列不能落空值）。 */
    private static String description(BagTransitionContext ctx, String fallback) {
        String remark = ctx.getCmd().getRemark();
        return remark == null || remark.isBlank() ? fallback : remark;
    }

    /** 取命令 attributes 中的字符串参数；缺失时回退默认值。 */
    private static String attributeString(BagTransitionContext ctx, String key, String fallback) {
        Map<String, Object> attributes = ctx.getCmd().getAttributes();
        Object value = attributes == null ? null : attributes.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** null 归一为短横线（拼接文案用，避免出现 "null"）。 */
    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** 先归一非空再按列宽截断（MaskUtils.truncate 对 null 返回 null，NOT NULL 列不能落空）。 */
    private static String truncateOr(String value, int max) {
        return MaskUtils.truncate(value == null || value.isBlank() ? "-" : value, max);
    }

    // ==================== 一事件一个 Bean（契约模块版第 2 节） ====================

    /** 激活上架（#1）。 */
    @Component
    static final class ActivateAction implements TransitionAction {
        private final BagTransitionActions actions;

        ActivateAction(BagTransitionActions actions) {
            this.actions = actions;
        }

        @Override
        public BagEvent event() {
            return BagEvent.ACTIVATE;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            actions.dispatch(BagEvent.ACTIVATE, ctx);
        }
    }

    /** 上架入库（#11）。 */
    @Component
    static final class ShelveAction implements TransitionAction {
        private final BagTransitionActions actions;

        ShelveAction(BagTransitionActions actions) {
            this.actions = actions;
        }

        @Override
        public BagEvent event() {
            return BagEvent.SHELVE;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            actions.dispatch(BagEvent.SHELVE, ctx);
        }
    }

    /** 转维修（#12）。 */
    @Component
    static final class RepairStartAction implements TransitionAction {
        private final BagTransitionActions actions;

        RepairStartAction(BagTransitionActions actions) {
            this.actions = actions;
        }

        @Override
        public BagEvent event() {
            return BagEvent.REPAIR_START;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            actions.dispatch(BagEvent.REPAIR_START, ctx);
        }
    }

    /** 修复入库（#13）。 */
    @Component
    static final class RepairDoneAction implements TransitionAction {
        private final BagTransitionActions actions;

        RepairDoneAction(BagTransitionActions actions) {
            this.actions = actions;
        }

        @Override
        public BagEvent event() {
            return BagEvent.REPAIR_DONE;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            actions.dispatch(BagEvent.REPAIR_DONE, ctx);
        }
    }

    /** 报废（#14，维修路径）。 */
    @Component
    static final class ScrapAction implements TransitionAction {
        private final BagTransitionActions actions;

        ScrapAction(BagTransitionActions actions) {
            this.actions = actions;
        }

        @Override
        public BagEvent event() {
            return BagEvent.SCRAP;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            actions.dispatch(BagEvent.SCRAP, ctx);
        }
    }

    /** 直接报废（#15）。 */
    @Component
    static final class ScrapDirectAction implements TransitionAction {
        private final BagTransitionActions actions;

        ScrapDirectAction(BagTransitionActions actions) {
            this.actions = actions;
        }

        @Override
        public BagEvent event() {
            return BagEvent.SCRAP_DIRECT;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            actions.dispatch(BagEvent.SCRAP_DIRECT, ctx);
        }
    }

    /** 消毒完成（合格）（#9）。 */
    @Component
    static final class DisinfectPassAction implements TransitionAction {
        private final BagTransitionActions actions;

        DisinfectPassAction(BagTransitionActions actions) {
            this.actions = actions;
        }

        @Override
        public BagEvent event() {
            return BagEvent.DISINFECT_PASS;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            actions.dispatch(BagEvent.DISINFECT_PASS, ctx);
        }
    }

    /** 消毒完成（不合格）（#10）。 */
    @Component
    static final class DisinfectFailAction implements TransitionAction {
        private final BagTransitionActions actions;

        DisinfectFailAction(BagTransitionActions actions) {
            this.actions = actions;
        }

        @Override
        public BagEvent event() {
            return BagEvent.DISINFECT_FAIL;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            actions.dispatch(BagEvent.DISINFECT_FAIL, ctx);
        }
    }

    /** 找回（#20）。 */
    @Component
    static final class FoundAction implements TransitionAction {
        private final BagTransitionActions actions;

        FoundAction(BagTransitionActions actions) {
            this.actions = actions;
        }

        @Override
        public BagEvent event() {
            return BagEvent.FOUND;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            actions.dispatch(BagEvent.FOUND, ctx);
        }
    }

    /** 状态修正（#21）。 */
    @Component
    static final class StatusCorrectAction implements TransitionAction {
        private final BagTransitionActions actions;

        StatusCorrectAction(BagTransitionActions actions) {
            this.actions = actions;
        }

        @Override
        public BagEvent event() {
            return BagEvent.STATUS_CORRECT;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            actions.dispatch(BagEvent.STATUS_CORRECT, ctx);
        }
    }
}
