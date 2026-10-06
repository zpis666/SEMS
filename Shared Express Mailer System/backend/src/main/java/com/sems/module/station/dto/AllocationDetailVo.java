package com.sems.module.station.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调拨单详情视图（见《系统设计说明书》5.3「GET /api/v1/allocations/{id}」：含明细逐袋核收进度）。
 *
 * <p>单头字段与 {@link AllocationVo} 一致，额外携带明细列表；明细逐袋给出状态与出库/核收/剔除时间，
 * 供目标站按袋核收、平台管理员按袋剔除或确认在途遗失。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AllocationDetailVo extends AllocationVo {

    /** 明细逐袋核收进度（按明细ID升序） */
    private List<DetailItem> details;

    /** 单只袋的明细进度 */
    @Data
    public static class DetailItem {

        /** 明细ID */
        private Long id;

        /** 快递袋编码 */
        private String bagCode;

        /** 明细状态码：PENDING_DISPATCH/DISPATCHED/RECEIVED/REMOVED/LOST */
        private String status;

        /** 明细状态中文名 */
        private String statusName;

        /** 该袋出库时间 */
        private LocalDateTime dispatchAt;

        /** 该袋核收时间 */
        private LocalDateTime receiveAt;

        /** 剔除（解锁）时间 */
        private LocalDateTime removedAt;

        /** 备注 */
        private String remark;
    }
}
