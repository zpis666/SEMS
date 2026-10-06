package com.sems.module.station.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调拨单袋级明细（对应表 {@code allocation_detail}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>建单即锁定袋（唯一键 allocation_id + bag_code 防同一单重复加入）；被锁定的袋在
 * 申领/转维修/报废/状态修正时抛 BAG_LOCKED_BY_ALLOCATION（见设计书 3.2）。
 *
 * <p>袋级五态：PENDING_DISPATCH 待出库 / DISPATCHED 已出库 / RECEIVED 已核收 /
 * REMOVED 已剔除 / LOST 已遗失——单只袋单独推进，异常时可按袋定位与处置。
 */
@Data
@TableName("allocation_detail")
public class AllocationDetail {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属调拨单ID（allocation_order.id） */
    private Long allocationId;

    /** 快递袋编码（SJB 定长码） */
    private String bagCode;

    /** 状态：PENDING_DISPATCH待出库/DISPATCHED已出库/RECEIVED已核收/REMOVED已剔除/LOST已遗失 */
    private String status;

    /** 该袋出库时间 */
    private LocalDateTime dispatchAt;

    /** 该袋核收时间 */
    private LocalDateTime receiveAt;

    /** 剔除（解锁）时间 */
    private LocalDateTime removedAt;

    /** 备注 */
    private String remark;

    /** 创建时间（建单即锁定该袋） */
    private LocalDateTime createdAt;

    /** 更新时间（自动刷新） */
    private LocalDateTime updatedAt;
}
