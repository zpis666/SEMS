package com.sems.module.order.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单列表项出参（见《系统设计说明书》5.2「本人订单分页查询」与 5.5「全量订单查询」）。
 *
 * <p>脱敏口径（见设计书 9.5）：收件人手机号只回掩码（{@code recipientPhoneMask}），
 * 摘要与明文一律不出参。站点列另附名称，避免前端为展示再逐单查询。
 */
@Data
public class OrderVo {

    /** 业务订单号 */
    private String orderNo;

    /** 快递袋编码 */
    private String bagCode;

    /** 订单状态码（OrderStatus.code()） */
    private String status;

    /** 订单状态中文名（OrderStatus.displayName()，展示用） */
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

    /** 收件人手机号掩码（仅掩码，如 138****1234） */
    private String recipientPhoneMask;

    /** 超时标记：0 正常 / 1 寄件后超期未归还（见设计书 3.5/3.6） */
    private Integer timeoutFlag;

    /** 寄件提交时间 */
    private LocalDateTime shippedAt;

    /** 标记归还时间 */
    private LocalDateTime returnMarkAt;

    /** 回收确认时间 */
    private LocalDateTime confirmedAt;

    /** 取消时间（撤回/超时/回收闭环） */
    private LocalDateTime cancelledAt;

    /** 遗失确认时间 */
    private LocalDateTime lostAt;

    /** 建单时间 */
    private LocalDateTime createdAt;
}
