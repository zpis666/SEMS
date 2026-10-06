package com.sems.module.bag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 快递袋状态流转日志实体，对应表 {@code bag_transfer_log}（表概要与设计出处见《系统设计说明书》8.2 与 3.2；
 * 字段级定义见《数据库设计说明书》V1.1 第 4 章，与 {@code deploy/sql/V1__init_schema.sql} 逐列一致）。
 *
 * <p>只插入不更新不删除：每次流转由状态机入口统一写一条，保证全程可追溯、可审计（见设计书 3.3）。
 * {@code ref_check_no} 仅 #21 状态修正必填，关联盘点差异单号；{@code proxy_flag} 记录平台管理员代操作（见设计书 3.2）。
 *
 * <p>状态字段用 {@code String} 而非枚举：与 DDL 的 {@code varchar} 码值一致，业务层用 {@code BagStatus}/{@code BagEvent}
 * 转换（{@code BagStatus.of()}、{@code BagEvent.of()}），避免 ORM 枚举映射与码值两份口径。
 */
@Data
@TableName("bag_transfer_log")
public class BagTransferLog {

    /** 主键ID（自增，写入有序） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 快递袋编码（追溯关联，不设外键） */
    private String bagCode;

    /** 流转前状态（11 态枚举码） */
    private String fromStatus;

    /** 流转后状态（11 态枚举码） */
    private String toStatus;

    /** 触发事件（BagEvent 码，与设计书 3.2 的 21 条转移一一对应） */
    private String event;

    /** 操作人用户ID（登录用户或管理员） */
    private Long operatorId;

    /** 操作站点（事件发生地） */
    private Long stationId;

    /** 平台管理员代操作：0 否 / 1 是 */
    private Integer proxyFlag;

    /** 盘点差异单号（#21 状态修正必填） */
    private String refCheckNo;

    /** 备注（修正原因/找回说明等） */
    private String remark;

    /** 流转时间（写入即发生时间） */
    private LocalDateTime createdAt;
}
