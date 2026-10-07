package com.sems.module.statemachine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态转移注册表：{@code (前态, 事件) → 目标态} 的唯一权威映射
 * （见《系统设计说明书》3.2 状态转移表、3.3 状态机实现设计）。
 *
 * <p>注册范围＝设计书 3.2 的 21 条编号规则；多前态规则（#12/#15/#18 各 3 个前态、#21 除外）
 * 展开后共 <b>21 条</b>规则记录。除 {@code STATUS_CORRECT} 外，任何未注册的 {@code (状态, 事件)}
 * 组合都属非法跳变，由 {@code BagTransitionService} 统一拒绝（4102）。
 *
 * <p><b>{@code STATUS_CORRECT} 为什么不在表里：</b>#21 的目标态不由图决定，而由命令携带
 * （白名单 {空闲, 维修中, 已遗失, 报废} 由流转服务校验），因此这里刻意不给它注册规则，
 * {@link #resolve} 对它返回 null，流转服务据此转入"命令指定目标态"分支，而不是误判为非法跳变。
 *
 * <p>为什么用静态表：规则是启动即冻结的纯数据、查询是无状态纯函数，静态注册后全程只读，
 * 单元测试与工具类无须 Spring 容器即可直接使用；键格式固定为 {@code from.name() + "#" + event.name()}，
 * 与设计书 3.2 表号无关、可读且不会与状态名混淆。
 */
public final class TransitionRegistry {

    /** 键 = 前态码 + "#" + 事件码；注册顺序即设计书 3.2 的表序，{@link #all()} 按此顺序返回。 */
    private static final Map<String, TransitionRule> RULES = new LinkedHashMap<>();

    static {
        // #1 新袋建档即空闲，不再单独激活
        // #2 空闲 → 已分配（扫码申领）
        register(BagStatus.IDLE, BagEvent.BORROW, BagStatus.ALLOCATED,
                "扫码申领：空闲袋被用户领取并生成待寄件订单");
        // #3 已分配 → 使用中（提交寄件）
        register(BagStatus.ALLOCATED, BagEvent.SHIP, BagStatus.IN_USE,
                "提交寄件：用户填写快递公司/运单号，袋进入寄送环节");
        // #4 已分配 → 空闲（未使用回收）
        register(BagStatus.ALLOCATED, BagEvent.RECOVER_UNUSED, BagStatus.IDLE,
                "未使用回收：订单已取消或不存在，袋未离站回库上架");
        // #5 已分配 → 待消毒（回收确认，袋已被取走但未寄件）
        register(BagStatus.ALLOCATED, BagEvent.RECOVER_CONFIRM, BagStatus.TO_DISINFECT,
                "回收确认：袋已取走未寄件即投回回收箱，关闭订单并送消毒");
        // #6 使用中 → 使用中（标记归还；只记订单归还站点，不改袋归属）
        register(BagStatus.IN_USE, BagEvent.RETURN_MARK, BagStatus.IN_USE,
                "标记归还：用户标记已投递，等待站点扫码确认");
        // #7 使用中 → 待消毒（回收确认-直投，用户未标记）
        register(BagStatus.IN_USE, BagEvent.RECOVER_DIRECT, BagStatus.TO_DISINFECT,
                "回收确认（直投）：未标记归还直接投回，站点扫码确认并完成订单");
        // #8 使用中 → 待消毒（回收确认）
        register(BagStatus.IN_USE, BagEvent.RECOVER_CONFIRM, BagStatus.TO_DISINFECT,
                "回收确认：站点扫码确认在途归还，复用量计入");
        // #9 待消毒 → 空闲（消毒合格）
        register(BagStatus.TO_DISINFECT, BagEvent.DISINFECT_PASS, BagStatus.IDLE,
                "消毒完成（合格）：检验通过，直接入库并补回可用库存");
        // #10 待消毒 → 维修中（消毒不合格）
        register(BagStatus.TO_DISINFECT, BagEvent.DISINFECT_FAIL, BagStatus.REPAIRING,
                "消毒完成（不合格）：登记缺陷并转维修");
        // #11 消毒合格自动入库，不再单独上架
        // #12 空闲/待消毒 → 维修中（转维修）
        register(BagStatus.IDLE, BagEvent.REPAIR_START, BagStatus.REPAIRING,
                "转维修：空闲袋登记破损后下架（可用库存同步 −1）");
        register(BagStatus.TO_DISINFECT, BagEvent.REPAIR_START, BagStatus.REPAIRING,
                "转维修：待消毒袋登记破损后转维修");
        // #13 维修中 → 待消毒（修复入库）
        register(BagStatus.REPAIRING, BagEvent.REPAIR_DONE, BagStatus.TO_DISINFECT,
                "修复入库：维修完成，转待消毒，合格后自动入库");
        // #14 维修中 → 报废（无法修复或超复用上限）
        register(BagStatus.REPAIRING, BagEvent.SCRAP, BagStatus.SCRAPPED,
                "报废：无法修复或复用次数达上限，退出循环（审计重点）");
        // #15 空闲/待消毒 → 报废（直接报废）
        register(BagStatus.IDLE, BagEvent.SCRAP_DIRECT, BagStatus.SCRAPPED,
                "直接报废：空闲袋登记报废原因后退出循环（可用库存同步 −1，审计重点）");
        register(BagStatus.TO_DISINFECT, BagEvent.SCRAP_DIRECT, BagStatus.SCRAPPED,
                "直接报废：待消毒袋登记报废原因后退出循环（审计重点）");
        // #16 空闲 → 调拨在途（调拨出库）
        register(BagStatus.IDLE, BagEvent.ALLOCATE_OUT, BagStatus.TRANSFERRING,
                "调拨出库：进入站间调拨在途，源站可用库存 −1");
        // #17 调拨在途 → 空闲（调拨入库）
        register(BagStatus.TRANSFERRING, BagEvent.ALLOCATE_IN, BagStatus.IDLE,
                "调拨入库：到达目标站并上架，归属改为目标站、可用库存 +1");
        // #18 已分配/使用中 → 已遗失（确认遗失）
        register(BagStatus.ALLOCATED, BagEvent.LOST_CONFIRM, BagStatus.LOST,
                "确认遗失：已分配留置超期，平台管理员确认遗失并写责任记录");
        register(BagStatus.IN_USE, BagEvent.LOST_CONFIRM, BagStatus.LOST,
                "确认遗失：寄件后超期未还，平台管理员确认遗失并写责任记录");
        // #19 调拨在途 → 已遗失（在途遗失确认）
        register(BagStatus.TRANSFERRING, BagEvent.LOST_IN_TRANSIT, BagStatus.LOST,
                "在途遗失确认：调拨超时未核收，写调拨异常与责任记录");
        // #20 已遗失 → 待消毒（找回）
        register(BagStatus.LOST, BagEvent.FOUND, BagStatus.TO_DISINFECT,
                "找回：登记找回说明与站点，重新消毒后回流循环（不重复计复用量）");
        // #21 STATUS_CORRECT 不注册：目标态由命令指定，见类注释
    }

    /** 注册一条规则：键 = 前态码 + "#" + 事件码；重复登记同一组合属编码错误，初始化即失败。 */
    private static void register(BagStatus from, BagEvent event, BagStatus to, String description) {
        String key = from.name() + "#" + event.name();
        if (RULES.putIfAbsent(key, new TransitionRule(from, event, to, description)) != null) {
            throw new IllegalStateException("重复注册状态转移规则：" + key);
        }
    }

    private TransitionRegistry() {
    }

    /**
     * 查规则（未注册返回 null）。
     *
     * @param from  流转前状态
     * @param event 触发事件
     * @return 注册的规则；{@code STATUS_CORRECT} 与非法跳变均返回 null
     */
    public static TransitionRule lookup(BagStatus from, BagEvent event) {
        if (from == null || event == null) {
            return null;
        }
        return RULES.get(from.name() + "#" + event.name());
    }

    /**
     * 查合法目标态（未注册返回 null；{@code STATUS_CORRECT} 恒返回 null）。
     *
     * <p>调用方拿到 null 时不要默认"目标态未知、放行"，而应按语境处理：
     * 事件为 {@code STATUS_CORRECT} 时改用命令指定的目标态（白名单校验由流转服务负责），
     * 其余情况即非法跳变，应抛 {@code BizException(BAG_ILLEGAL_TRANSITION)}。
     */
    public static BagStatus resolve(BagStatus from, BagEvent event) {
        TransitionRule rule = lookup(from, event);
        return rule == null ? null : rule.to();
    }

    /** 全部规则（只读，按设计书 3.2 表序）；供自检与单元测试核对 21 条注册是否齐全。 */
    public static List<TransitionRule> all() {
        return List.copyOf(RULES.values());
    }
}
