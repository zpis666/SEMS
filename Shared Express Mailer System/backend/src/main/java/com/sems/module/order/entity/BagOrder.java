package com.sems.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 快递订单（对应表 {@code bag_order}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>取用闭环主记录：一次申领建一单，绑定一只袋与一个取袋站点。订单六态
 * （PENDING_SEND 待寄件 / IN_USE 使用中 / PENDING_CONFIRM 待确认 / FINISHED 已完成 /
 * CANCELLED 已取消 / LOST 遗失）由状态机联动推进，业务层用 OrderStatus.of(code) 转换。
 *
 * <p>三处业务约束落在唯一键上：order_no（对外单号）、client_request_id（申领落库幂等兜底，
 * 与 Redis 幂等键互为双保险）、(express_company, tracking_no)（防同一运单重复绑定）。
 * version 承载乐观锁，防止并发下重复推进订单状态。
 *
 * <p>隐私约定：收件人手机号只存掩码（展示）与 HMAC 哈希（留存证据），不存明文（见设计书 8.2/9.5）。
 */
@Data
@TableName("bag_order")
public class BagOrder {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务订单号（对外唯一，BizNoUtils.orderNo() 生成） */
    private String orderNo;

    /** 客户端幂等ID（申领落库防重，取自请求头 clientRequestId 的 UUID） */
    private String clientRequestId;

    /** 申领用户ID（订单归属人）；查询与鉴权时一律取自 token，不接受前端传参（防水平越权） */
    private Long userId;

    /** 快递袋编码（SJB 定长码） */
    private String bagCode;

    /** 取袋站点（申领发生站点） */
    private Long stationId;

    /** 归还站点（标记归还时写入；跨站归还时与 stationId 不同，回收确认按此追溯） */
    private Long returnStationId;

    /** 订单状态（6 态枚举，初始 PENDING_SEND，业务层用 OrderStatus.of(code) 转换） */
    private String status;

    /** 快递公司（寄件时写入） */
    private String expressCompany;

    /** 运单号（8-20 位，寄件时写入；与快递公司联合唯一） */
    private String trackingNo;

    /** 收件人手机号掩码（如 138****1234，仅展示用，由 MaskUtils 生成） */
    private String recipientPhoneMask;

    /** 收件人手机号 HMAC-SHA256 哈希（含密钥版本）；仅作留存证据，不提供比对入口（见设计书 8.2/9.5） */
    private String recipientPhoneHash;

    /** 超时标记（寄件后超过留置时限未还置 1，口径见设计书 3.5） */
    private Integer timeoutFlag;

    /** 超时标记时间（置 1 时写入） */
    private LocalDateTime timeoutTime;

    /** 寄件提交时间（遗失认定等天数基准，见设计书 3.5） */
    private LocalDateTime shippedAt;

    /** 标记归还时间（归还相关时限基准，见设计书 3.5） */
    private LocalDateTime returnMarkAt;

    /** 归还提醒已发时间（防重推，保证提醒只发一次） */
    private LocalDateTime returnRemindAt;

    /** 回收确认时间（订单完成） */
    private LocalDateTime confirmedAt;

    /** 取消时间（撤回/超时/回收） */
    private LocalDateTime cancelledAt;

    /** 遗失确认时间（订单终态） */
    private LocalDateTime lostAt;

    /** 乐观锁版本（条件更新防并发重复推进订单状态） */
    @Version
    private Integer version;

    /** 申领建单时间（取消时限基准，见设计书 3.5） */
    private LocalDateTime createdAt;

    /** 更新时间（变更自动刷新） */
    private LocalDateTime updatedAt;
}
