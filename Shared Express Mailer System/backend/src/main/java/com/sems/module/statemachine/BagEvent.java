package com.sems.module.statemachine;

/**
 * 快递袋流转触发事件（21 个（含建档日志与历史兼容事件），见《系统设计说明书》3.2 状态转移表的「触发事件」列；
 * 码值与 {@code bag_transfer_log.event} 列注释、DDL 逐字一致）。
 *
 * <p>事件是状态机的输入：{@code (当前状态, 事件)} 在 {@link TransitionRegistry} 中至多映射一条
 * {@link TransitionRule}，未注册的组合由 {@code BagTransitionService} 拒绝并报 4102。
 * 同一事件在不同前态下允许走向不同目标态（如 RECOVER_CONFIRM 在 #5/#8 分别去空闲/待消毒方向），
 * 因此伴随动作实现里要区分前态时必须读上下文中的袋快照，不能只看事件。
 *
 * <p>新增事件必须同步修改设计书 3.2、注册表与 {@code bag_transfer_log.event} 列注释。
 */
public enum BagEvent {

    /** 新袋建档即入库；仅建档流程写日志，不注册为已有袋的流转事件。 */
    CREATE("建档入库"),

    /** 激活上架：新袋贴标上架，进入空闲可申领（#1） */
    ACTIVATE("激活上架"),
    /** 扫码申领：登录用户扫码领袋，生成待寄件订单（#2） */
    BORROW("扫码申领"),
    /** 提交寄件：用户填写快递公司/运单号（#3） */
    SHIP("提交寄件"),
    /** 标记归还：用户标记已投递，等待站点确认（#6） */
    RETURN_MARK("标记归还"),
    /** 未使用回收：订单已取消/不存在，袋未离站回库（#4） */
    RECOVER_UNUSED("未使用回收"),
    /** 回收确认：站点扫码确认；#5（已分配）与 #8（使用中）共用本事件 */
    RECOVER_CONFIRM("回收确认"),
    /** 回收确认（直投）：用户未标记归还直接投回，站点扫码确认（#7） */
    RECOVER_DIRECT("回收确认（直投）"),
    /** 消毒完成（合格）：直接入库为空闲（#9） */
    DISINFECT_PASS("消毒完成（合格）"),
    /** 消毒完成（不合格）：登记缺陷并转维修（#10） */
    DISINFECT_FAIL("消毒完成（不合格）"),
    /** 上架入库：历史事件，新模型不再允许单独上架（#11） */
    SHELVE("上架入库"),
    /** 转维修：登记破损，进入维修中（#12） */
    REPAIR_START("转维修"),
    /** 修复入库：维修完成转待消毒（#13） */
    REPAIR_DONE("修复入库"),
    /** 报废：维修无法修复或超复用上限（#14） */
    SCRAP("报废"),
    /** 直接报废：非维修路径登记报废原因（#15） */
    SCRAP_DIRECT("直接报废"),
    /** 调拨出库：进入站间调拨在途（#16） */
    ALLOCATE_OUT("调拨出库"),
    /** 调拨入库：到达目标站并上架（#17） */
    ALLOCATE_IN("调拨入库"),
    /** 确认遗失：平台管理员确认遗失并写责任记录（#18） */
    LOST_CONFIRM("确认遗失"),
    /** 在途遗失确认：调拨超时未核收（#19） */
    LOST_IN_TRANSIT("在途遗失确认"),
    /** 找回：重新消毒后回流循环（#20） */
    FOUND("找回"),
    /** 状态修正：平台管理员按盘点差异单人工修正（#21，目标态由命令指定） */
    STATUS_CORRECT("状态修正");

    /** 中文名（与设计书 3.2 事件列逐字一致，供日志、提示语与前端展示使用） */
    private final String displayName;

    BagEvent(String displayName) {
        this.displayName = displayName;
    }

    /** 枚举码：与 {@code bag_transfer_log.event} 列一致。 */
    public String code() {
        return name();
    }

    /**
     * 按码值解析事件。
     *
     * <p>与 {@link BagStatus#of(String)} 同口径：未知码值意味着数据/口径异常，直接抛
     * {@link IllegalArgumentException} 暴露，不静默降级。
     */
    public static BagEvent of(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("快递袋事件码不能为空");
        }
        for (BagEvent event : values()) {
            if (event.name().equals(code)) {
                return event;
            }
        }
        throw new IllegalArgumentException("未知的快递袋事件码：" + code);
    }

    /** 中文名（与设计书 3.2 一致）。 */
    public String displayName() {
        return displayName;
    }
}
