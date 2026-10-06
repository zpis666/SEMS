package com.sems.module.statemachine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 状态流转结果（契约 8.4）：状态机入口 {@code BagTransitionService.fire(...)} 的返回值，
 * 供单只接口回显与批量接口逐袋明细使用（批量明细格式见契约 10 节统一约定）。
 *
 * <p>幂等回放（{@link #replayed}=true）时本对象由"回查袋当前状态"构造：
 * 首次流转的前态不在任何地方留存（流转日志按袋码另存，不按幂等键索引），
 * 因此 {@link #fromStatus} 置空，调用方应以 {@link #toStatus} 为袋的当前权威状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BagTransitionResult {

    /** 袋编码 */
    private String bagCode;

    /** 流转前状态；幂等回放时为 null（首次流转的前态不可还原，见类注释） */
    private BagStatus fromStatus;

    /** 流转后状态；幂等回放时为回查到的袋当前状态 */
    private BagStatus toStatus;

    /** 是否幂等命中：true=本次未执行流转，结果为回查所得，不再重复写日志/推送 */
    private boolean replayed;

    /** 流转后的袋归属站点（事务内由状态机确定的权威值） */
    private Long stationId;
}
