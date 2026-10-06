package com.sems.module.station.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 剔除调拨明细入参（见《系统设计说明书》5.3「POST /api/v1/allocations/{id}/remove-detail」与 3.4.3）。
 *
 * <p>仅「待出库 / 在途」单据中的待出库明细、且袋仍为空闲时可剔除（明细置 REMOVED、袋解锁回空闲）；
 * 袋已非空闲（被转维修/报废/状态修正等）时无法剔除，须由平台管理员先终止调拨单。
 */
@Data
public class AllocationRemoveDto {

    /** 要剔除的快递袋编码 */
    @NotBlank(message = "袋码不能为空")
    private String bagCode;

    /** 剔除原因 / 备注（可空，落 allocation_detail.remark） */
    @Size(max = 255, message = "备注长度不能超过 255 字")
    private String remark;
}
