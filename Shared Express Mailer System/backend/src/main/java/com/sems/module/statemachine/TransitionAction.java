package com.sems.module.statemachine;

/**
 * 状态转移的伴随动作（契约 8.4；动作清单见《系统设计说明书》3.2「伴随动作」列）。
 *
 * <p>每个事件至多一个实现，实现类标注 {@code @Service}/{@code @Component} 后由
 * {@code BagTransitionService} 按 {@link #event()} 注册进动作表；没有实现的事件视为无伴随动作。
 *
 * <p>执行时机：条件 UPDATE 与流转日志写入之后、<b>同一事务内</b>——
 * 订单联动、Redis 计数/库存增减、业务记录表写入都在这里完成，动作抛异常则整笔流转回滚
 * （袋状态与日志一起回滚，不会出现"状态改了、订单没联动"的半截数据）。
 * 因此动作实现中<b>禁止</b>直接向 WebSocket 推送（事务可能回滚但消息已发出），
 * 推送必须包在 {@link com.sems.push.PushService#afterCommit} 中；
 * 动作内也禁止新开事务（{@code REQUIRES_NEW}），否则同样会破坏"一次流转一个原子事务"的边界。
 *
 * <p>批量接口的逐袋明细由调用方汇总，动作不需要写返回信息，只负责数据库/Redis 副作用。
 */
public interface TransitionAction {

    /** 本动作响应的事件；同一事件注册多个实现属配置错误，状态机启动时会直接失败。 */
    BagEvent event();

    /**
     * 在状态机事务内执行伴随动作。
     *
     * @param ctx 流转上下文：袋快照为<b>前态</b>，目标态与目标站点见
     *            {@link BagTransitionContext} 的类注释
     */
    void apply(BagTransitionContext ctx);
}
