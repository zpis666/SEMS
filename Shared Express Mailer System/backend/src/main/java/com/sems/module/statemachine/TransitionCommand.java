package com.sems.module.statemachine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 状态流转命令：调用方（模块 Service / 定时任务）向状态机入口提交的一次流转请求
 * （契约 8.4；字段口径见《系统设计说明书》3.2/3.3）。
 *
 * <p>为什么用"命令"对象而不是一长串方法参数：流转的上下文字段多且后续可能扩展，
 * 命令对象让所有调用点用 {@code Builder} 显式写明意图，避免位置参数错配
 * （尤其 {@code stationId}/{@code targetStationId} 两个站点字段容易混淆）。
 *
 * <p>{@code targetStatus} 仅 {@code STATUS_CORRECT} 使用，{@code targetStationId} 仅站点变更类事件使用，
 * 其余字段按需填写；白名单校验、幂等由 {@code BagTransitionService} 统一执行，
 * 调用方不得先自行改袋状态再调本命令（状态字段的唯一写入口是状态机）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransitionCommand {

    /** 袋编码（SJB- 定长码，全局唯一） */
    private String bagCode;

    /** 触发事件（20 个事件之一；未注册的 (前态, 事件) 组合一律报 4102） */
    private BagEvent event;

    /** 操作人用户ID（登录用户或管理员，写流转日志与审计） */
    private Long operatorId;

    /** 操作人角色码（USER/STATION_ADMIN/PLATFORM_ADMIN；伴随动作按角色细分联动行为时使用） */
    private String operatorRole;

    /** 操作站点（事件发生地，写 {@code bag_transfer_log.station_id}；平台代操作时为事发站点） */
    private Long stationId;

    /** 平台管理员代操作标记（写 {@code bag_transfer_log.proxy_flag}；代操作仍走同一校验链路） */
    private boolean proxy;

    /** 备注（修正原因/找回说明等，写流转日志） */
    private String remark;

    /** 客户端幂等ID：与袋码组成逐袋幂等键（批量接口逐袋独立；缺失时状态机不做逐袋去重） */
    private String clientRequestId;

    /** 目标态：仅 {@code STATUS_CORRECT} 使用（白名单 {IDLE, REPAIRING, LOST, SCRAPPED} 由状态机校验） */
    private BagStatus targetStatus;

    /** 目标站点：跨站修正/调拨入库等站点变更事件指定（未指定时取 {@link #stationId}） */
    private Long targetStationId;

    /** 扩展参数：供 {@link TransitionAction} 取用的业务字段（如订单号、消毒批次号、"曾寄出"标记等） */
    private Map<String, Object> attributes;
}
