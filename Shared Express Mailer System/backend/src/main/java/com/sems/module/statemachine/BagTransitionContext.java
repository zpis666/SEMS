package com.sems.module.statemachine;

import com.sems.module.bag.entity.Bag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 伴随动作上下文（契约 8.4）：状态机在一次成功转移后、同一个事务内传给 {@link TransitionAction} 的快照。
 *
 * <p>{@link #bag} 是<b>条件 UPDATE 之前的袋快照</b>（前态），不是流转后的状态——
 * 同一事件在不同前态下的伴随动作不同（典型：RECOVER_CONFIRM 按前态区分 #5 未寄件回收与 #8 在途归还），
 * 动作只有在拿到前态时才能正确分流；目标态可由
 * {@code TransitionRegistry.lookup(bag.getStatus(), cmd.getEvent())} 求得
 * （{@code STATUS_CORRECT} 取 {@code cmd.getTargetStatus()}），无需重新查库。
 *
 * <p>流转后袋的站点归属：本事件属六个站点变更事件且命令指定了站点时，新站为
 * {@code cmd.targetStationId}（缺省 {@code cmd.stationId}），否则站点保持不变。
 *
 * <p>本对象只在同一次调用内使用，动作实现不得把它持有到事务提交之后
 * （袋快照是旧值，事务外应重新查库；推送类副作用统一在 {@code PushService.afterCommit} 中执行）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BagTransitionContext {

    /** 原始命令：动作从这里取扩展参数、操作人、备注与站点 */
    private TransitionCommand cmd;

    /** 流转前的袋快照（条件 UPDATE 前装载的行） */
    private Bag bag;

    /** 操作人用户ID（同 {@code cmd.operatorId}，便于动作直接使用） */
    private Long operatorId;

    /** 平台管理员代操作标记（同 {@code cmd.proxy}） */
    private boolean proxy;
}
