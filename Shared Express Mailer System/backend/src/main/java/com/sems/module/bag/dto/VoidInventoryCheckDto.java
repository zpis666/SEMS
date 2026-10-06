package com.sems.module.bag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 盘点单作废入参（{@code POST /api/v1/inventory-checks/{id}/void}，
 * 见《系统设计说明书》5.4 与需求 FR-M4-17）。
 *
 * <p>盘点单不可删除，仅可作废重盘；仅「待修正」状态可作废（否则 4105）。
 * 作废原因（重盘说明）必填并留痕（void_reason / voided_at / voided_by）。
 */
@Data
public class VoidInventoryCheckDto {

    /** 作废原因（重盘说明，必填，≤255 字符，与库列 varchar(255) 一致） */
    @NotBlank(message = "作废原因不能为空")
    @Size(max = 255, message = "作废原因长度不能超过 255 字符")
    private String voidReason;
}
