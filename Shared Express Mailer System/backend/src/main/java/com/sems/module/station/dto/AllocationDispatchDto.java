package com.sems.module.station.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 调拨出库入参（见《系统设计说明书》5.3「POST /api/v1/allocations/{id}/dispatch」与 3.4.3 调拨流程）。
 *
 * <p>源站站点管理员逐只扫码出库：袋 空闲→调拨在途、源站库存 −1；首只出库时单据转「在途」。
 * 逐袋独立处理、逐袋返回结果明细（{@code bagCode/success/code/message}），单只失败不影响其余。
 */
@Data
public class AllocationDispatchDto {

    /** 本次出库的袋码清单（每项以 bagCode 为键，支持逐袋备注） */
    @NotEmpty(message = "袋码清单不能为空")
    @Valid
    private List<BagItem> bags;

    /** 整批备注（可空，落袋单级备注的另一来源） */
    @Size(max = 255, message = "备注长度不能超过 255 字")
    private String remark;

    /** 单袋项 */
    @Data
    public static class BagItem {

        /** 快递袋编码（SJB 定长码） */
        @NotBlank(message = "袋码不能为空")
        private String bagCode;

        /** 该袋备注（可空） */
        @Size(max = 255, message = "备注长度不能超过 255 字")
        private String remark;
    }
}
