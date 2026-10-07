package com.sems.module.statemachine;

/**
 * 快递订单状态（6 态，见《系统设计说明书》3.6「订单状态设计」）。
 *
 * <p>码值与库列 {@code bag_order.status}（varchar）逐字一致，业务层经 {@link #of(String)} 转换；
 * 订单状态与袋状态严格按 3.6 的联动表变化，联动由状态机转移的伴随动作完成，
 * <b>禁止任何代码单独改订单状态</b>——这是"袋状态为权威事实、订单跟随联动"的落点。
 *
 * <p>注意"超时"不是订单状态，而是 {@code timeout_flag}/{@code timeout_time} 标记字段：
 * 寄件后 7 天未归还只置位并提醒，状态仍是 {@link #IN_USE}，直至归还或遗失确认（见 3.5/3.6）。
 */
public enum OrderStatus {

    /** 待寄件：扫码申领成功（转移 #2，袋=已分配） */
    PENDING_SEND("待寄件"),
    /** 使用中：提交寄件（#3，袋=使用中） */
    IN_USE("使用中"),
    /** 待回收确认：用户标记已投放，袋仍为使用中（#6） */
    PENDING_CONFIRM("待回收确认"),
    /** 已完成：回收确认（#7/#8，袋进待消毒） */
    FINISHED("已完成"),
    /** 已取消：用户撤回 / 24h 超时取消 / 未使用袋回收确认（#5；袋保持已分配，在待 #4/#5） */
    CANCELLED("已取消"),
    /** 已遗失：平台管理员遗失确认（#18，含已取消订单的终态覆盖） */
    LOST("已遗失");

    /** 中文名（与设计书 3.6 订单状态表逐字一致） */
    private final String displayName;

    OrderStatus(String displayName) {
        this.displayName = displayName;
    }

    /** 枚举码：与 {@code bag_order.status} 列一致。 */
    public String code() {
        return name();
    }

    /**
     * 按码值解析订单状态。
     *
     * <p>与 {@link BagStatus#of(String)} 同口径：未知码值直接抛 {@link IllegalArgumentException}，
     * 不静默降级为 null。
     */
    public static OrderStatus of(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("订单状态码不能为空");
        }
        for (OrderStatus status : values()) {
            if (status.name().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的订单状态码：" + code);
    }

    /** 中文名（与设计书 3.6 一致）。 */
    public String displayName() {
        return displayName;
    }
}
