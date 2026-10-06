package com.sems.module.order.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单详情出参（见《系统设计说明书》5.2「本人订单详情（校验归属，非本人报 2002）」）。
 *
 * <p>在列表项 {@link OrderVo} 字段之外补充：超时标记时间、归还提醒已发时间、更新时间，
 * 以及袋当前状态（订单是"谁在用"的记录，袋状态是"实物在哪一环"的事实，详情页两者都展示，
 * 口径见设计书 3.6 订单状态与袋状态联动表）。
 */
@Data
public class OrderDetailVo {

    /** 业务订单号 */
    private String orderNo;

    /** 快递袋编码 */
    private String bagCode;

    /** 袋当前状态码（BagStatus.code()，展示实物环节） */
    private String bagStatus;

    /** 袋当前状态中文名（BagStatus.displayName()） */
    private String bagStatusName;

    /** 订单状态码（OrderStatus.code()） */
    private String status;

    /** 订单状态中文名（OrderStatus.displayName()） */
    private String statusName;

    /** 取袋站点ID */
    private Long stationId;

    /** 取袋站点名称 */
    private String stationName;

    /** 归还站点ID（标记归还后才有值） */
    private Long returnStationId;

    /** 归还站点名称 */
    private String returnStationName;

    /** 快递公司（寄件后才有值） */
    private String expressCompany;

    /** 运单号（寄件后才有值） */
    private String trackingNo;

    /** 收件人手机号掩码（仅掩码，不提供明文与哈希比对入口，见设计书 8.2/9.5） */
    private String recipientPhoneMask;

    /** 超时标记：0 正常 / 1 寄件后超期未归还 */
    private Integer timeoutFlag;

    /** 超时标记时间 */
    private LocalDateTime timeoutTime;

    /** 寄件提交时间（14 天遗失认定基准） */
    private LocalDateTime shippedAt;

    /** 标记归还时间（24h/72h 回收时限基准） */
    private LocalDateTime returnMarkAt;

    /** 归还提醒已发时间（防重推字段，可空） */
    private LocalDateTime returnRemindAt;

    /** 回收确认时间 */
    private LocalDateTime confirmedAt;

    /** 取消时间 */
    private LocalDateTime cancelledAt;

    /** 遗失确认时间 */
    private LocalDateTime lostAt;

    /** 建单时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
