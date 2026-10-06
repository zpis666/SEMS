package com.sems.module.statemachine;

/**
 * 一条状态转移规则（见《系统设计说明书》3.2 状态转移表 / 3.3 状态机实现设计）。
 *
 * <p>用 {@code record} 表达：规则是纯数据（前态、事件、目标态、说明），一旦注册不可变；
 * 21 条编号规则在 {@link TransitionRegistry} 中逐条注册，多前态规则（如 #12/#15/#18）展开为多条记录，
 * 因此记录条数大于 21。
 *
 * @param from        流转前状态
 * @param event       触发事件
 * @param to          流转后状态（{@code STATUS_CORRECT} 不注册规则，其目标态由命令指定）
 * @param description 规则说明（对应设计书 3.2 表，便于日志排查与自检）
 */
public record TransitionRule(BagStatus from, BagEvent event, BagStatus to, String description) {
}
