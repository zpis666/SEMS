package com.sems.module.station.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站点间调拨单（对应表 {@code allocation_order}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>调拨六态：PENDING_DISPATCH 待出库 → IN_TRANSIT 在途 → COMPLETED 已完成，
 * 异常分支 ABNORMAL（在途遗失/部分核收），以及 CANCELLED 已取消、TERMINATED 已终止。
 * 取消仅限待出库状态（见设计书 3.2 调拨状态机）。
 *
 * <p>version 承载乐观锁：出库/核收均为条件 UPDATE（WHERE version=?），防止并发下
 * 重复出库或重复核收把在途数量算错（见设计书 8.2）。
 */
@Data
@TableName("allocation_order")
public class AllocationOrder {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 调拨单号（业务唯一编号，BizNoUtils.allocationNo() 生成） */
    private String allocationNo;

    /** 源站点ID（调出方） */
    private Long fromStationId;

    /** 目标站点ID（调入方） */
    private Long toStationId;

    /** 状态：PENDING_DISPATCH待出库/IN_TRANSIT在途/COMPLETED已完成/ABNORMAL异常/CANCELLED已取消/TERMINATED已终止 */
    private String status;

    /** 首只出库时间（在途超时扫描的起算基准；超时阈值读 SemsProperties.Alloc.timeoutHours，不写死） */
    private LocalDateTime dispatchAt;

    /** 全部核收完成时间 */
    private LocalDateTime completedAt;

    /** 置异常时间（在途遗失/部分核收） */
    private LocalDateTime abnormalAt;

    /** 取消或异常后终止时间 */
    private LocalDateTime cancelledAt;

    /** 取消或异常后终止原因（取消仅限待出库状态） */
    private String cancelReason;

    /** 异常后终止操作人ID（平台管理员） */
    private Long terminatedBy;

    /** 创建人用户ID（平台管理员） */
    private Long createdBy;

    /** 备注 */
    private String remark;

    /** 乐观锁版本号：条件 UPDATE 防并发重复出库/核收（见设计书 8.2） */
    @Version
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（自动刷新） */
    private LocalDateTime updatedAt;
}
