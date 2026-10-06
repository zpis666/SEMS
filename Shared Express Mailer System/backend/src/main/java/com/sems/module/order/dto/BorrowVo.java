package com.sems.module.order.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 申领结果出参（见《系统设计说明书》3.4.1 图 3-2「返回订单号」与契约 9 第 12 步字段口径）。
 *
 * <p>字段固定为 {@code orderNo / bagCode / stationId / stationName / status / createdAt / expiresHint}：
 * 前端凭订单号进入寄件页，凭 {@code expiresHint} 提示用户 24h 内提交寄件（见设计书 3.5 R2 口径）。
 *
 * <p>状态取 {@code OrderStatus.code()} 字符串（契约 0：状态字段一律枚举 code 的字符串形式）。
 */
@Data
public class BorrowVo {

    /** 业务订单号（BO 前缀，前端后续寄件/撤回/查询的凭据） */
    private String orderNo;

    /** 快递袋编码 */
    private String bagCode;

    /** 取袋站点ID */
    private Long stationId;

    /** 取袋站点名称（展示用） */
    private String stationName;

    /** 订单状态码（申领成功时为 PENDING_SEND 待寄件） */
    private String status;

    /** 建单时间 */
    private LocalDateTime createdAt;

    /** 时限提示文案（如"请在 yyyy-MM-dd HH:mm:ss 前提交寄件信息，逾期订单将自动取消"） */
    private String expiresHint;
}
