package com.sems.module.stats.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异常与风控告警清单项（{@code GET /api/v1/alerts} 的数组元素，
 * 见《系统设计说明书》5.6「异常与风控告警清单……按类型/时间筛选，可跳转处置」）。
 *
 * <p>七类告警的 {@code type} 取值固定为（与前端 {@code ALERT_TYPE_META} 的键一致）：
 * CANCELLED_NOT_RECOVERED / RETURNING_TIMEOUT / ALLOCATION_TIMEOUT / SHIP_TIMEOUT /
 * AUDIT_DROP / STOCK_RECONCILE_DIFF / RISK_CONFIRM_FREQUENCY。
 *
 * <p>{@code level} 取 HIGH/MEDIUM/LOW（前端映射为高/中/低展示）；
 * {@code occurredAt} 为该告警对应事件的发生时间（系统指标类为指标最后更新时刻或检测时刻）。
 */
@Data
public class AlertVo {

    /** 告警类型码（七类之一，见类头说明；用于 /alerts?type= 筛选与前端分类展示） */
    private String type;

    /** 类型中文名（与 type 一一对应，供接口消费方直接展示） */
    private String typeLabel;

    /** 告警标题（含订单号/袋码/站点等可辨识信息，供清单直接阅读） */
    private String title;

    /** 目标单号：订单类为 orderNo、调拨类为 allocationNo、风控类为操作人 ID；
     *  系统指标类（审计丢弃/对账差异）无单据号，为 null */
    private String targetNo;

    /** 关联站点ID（风控类为触发站点；系统指标类无站点，为 null） */
    private Long stationId;

    /** 关联站点名称（供清单直接展示；站点已不存在时可能为 null） */
    private String stationName;

    /** 发生时间：①②③④为事件时间，⑤⑥为指标最后更新时刻/检测时刻，⑦为检测时刻 */
    private LocalDateTime occurredAt;

    /** 级别码：HIGH=高 / MEDIUM=中 / LOW=低（前端映射颜色） */
    private String level;

    /** 前端跳转路径（hash 路由，如 /platform/orders?status=CANCELLED），供「去处置」直达对应单据 */
    private String actionUrl;
}
