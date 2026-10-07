package com.sems.module.statemachine;

/**
 * 快递袋全生命周期状态（8 态（4 个正常态 + 4 个扩展态），见《系统设计说明书》3.1「状态定义」与 3.2 状态图）。
 *
 * <p>码值与库列 {@code bag.status}（varchar）逐字一致，业务层一律经 {@link #of(String)} 转换——
 * 库里存码、代码用枚举，既保证 SQL 可读性，又避免 ORM 枚举映射与码值两份口径（契约 0 节）。
 *
 * <p>本枚举是状态机的状态口径：转移表中出现的状态必须在此有定义，
 * 新增状态必须同步修改设计书 3.1/3.2、{@code bag.status} 注释与 {@link TransitionRegistry}。
 *
 * <p>两个派生口径：
 * <ul>
 *   <li>{@link #isTerminal()}：仅 {@link #SCRAPPED}——报废后退出循环，不再参与任何流转；</li>
 *   <li>{@link #countsAsAvailable()}：仅 {@link #IDLE}——站点可用库存的权威口径就是该站点 IDLE 袋数（见 3.1），
 *       库存推送与 #21 增减判定都以本方法为准，禁止在别处另写一份"哪些状态算库存"的判断。</li>
 * </ul>
 */
public enum BagStatus {

    /** 空闲：已上架，可被扫码申领（唯一计入站点可用库存的状态） */
    IDLE("空闲"),
    /** 已分配：用户扫码申领成功，尚未提交寄件信息（库存已预扣） */
    ALLOCATED("已分配"),
    /** 使用中：已提交寄件，处于寄送/使用环节 */
    IN_USE("使用中"),
    /** 待消毒：回收确认完成，进入待消毒区 */
    TO_DISINFECT("待消毒"),
    /** 调拨在途：处于站点间调拨运输中（扩展态，"调"环节） */
    TRANSFERRING("调拨在途"),
    /** 维修中：破损维修登记后（扩展态） */
    REPAIRING("维修中"),
    /** 已遗失：超期未还/在途丢失，经平台管理员确认遗失（扩展态） */
    LOST("已遗失"),
    /** 报废：达到复用次数上限或无法修复，退出循环（唯一终态） */
    SCRAPPED("报废");

    /** 中文名（与设计书 3.1 状态表逐字一致，供日志、提示语与前端展示使用） */
    private final String displayName;

    BagStatus(String displayName) {
        this.displayName = displayName;
    }

    /** 枚举码：与 {@code bag.status}、{@code bag_transfer_log.from_status/to_status} 列一致。 */
    public String code() {
        return name();
    }

    /**
     * 按码值解析状态。
     *
     * <p>解析失败直接抛 {@link IllegalArgumentException} 而不返回 null：调用点几乎都是拿库里的权威数据做判断，
     * 未知码值意味着数据已损坏或码值口径漂移，必须立刻暴露（静默当 null 会把故障带到更远的分支上）。
     * 请求参数中的状态筛选应在 Controller 层先用枚举/正则校验，不要依赖本方法兜底。
     */
    public static BagStatus of(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("快递袋状态码不能为空");
        }
        for (BagStatus status : values()) {
            if (status.name().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的快递袋状态码：" + code);
    }

    /** 中文名（与设计书 3.1 一致）。 */
    public String displayName() {
        return displayName;
    }

    /** 是否终态：仅报废。终态不再参与任何流转（含 #21 状态修正），流转服务会先行拒绝。 */
    public boolean isTerminal() {
        return this == SCRAPPED;
    }

    /** 是否计入站点可用库存：仅空闲（见设计书 3.1 与 7.2 R4 对账口径）。 */
    public boolean countsAsAvailable() {
        return this == IDLE;
    }
}
