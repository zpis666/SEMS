package com.sems.module.station.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调拨单列表视图（见《系统设计说明书》5.3「GET /api/v1/allocations」）。
 *
 * <p>列表行附带明细进度计数（待出库/已出库/已核收/已剔除/已遗失），前端无需再逐单查询明细；
 * 在途单另带 {@code timeout} 超时标注（5.3：在途满 48 小时标注超时提示，阈值读
 * {@code sems.alloc.timeout-hours}，不写死）。
 */
@Data
public class AllocationVo {

    /** 调拨单ID */
    private Long id;

    /** 调拨单号（AL 开头） */
    private String allocationNo;

    /** 源站点ID */
    private Long fromStationId;

    /** 源站点名称 */
    private String fromStationName;

    /** 目标站点ID */
    private Long toStationId;

    /** 目标站点名称 */
    private String toStationName;

    /** 状态码：PENDING_DISPATCH/IN_TRANSIT/COMPLETED/ABNORMAL/CANCELLED/TERMINATED */
    private String status;

    /** 状态中文名 */
    private String statusName;

    /** 备注 */
    private String remark;

    /** 明细总数 */
    private Integer totalCount;

    /** 待出库明细数 */
    private Integer pendingCount;

    /** 已出库（在途）明细数 */
    private Integer dispatchedCount;

    /** 已核收明细数 */
    private Integer receivedCount;

    /** 已剔除明细数 */
    private Integer removedCount;

    /** 已遗失明细数 */
    private Integer lostCount;

    /** 首只出库时间（在途超时起算基准） */
    private LocalDateTime dispatchAt;

    /** 全部核收完成时间 */
    private LocalDateTime completedAt;

    /** 置异常时间（在途遗失/部分核收） */
    private LocalDateTime abnormalAt;

    /** 取消或异常后终止时间 */
    private LocalDateTime cancelledAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 在途超时标注（仅 IN_TRANSIT 且首只出库已满配置小时数时为 true） */
    private Boolean timeout;
}
