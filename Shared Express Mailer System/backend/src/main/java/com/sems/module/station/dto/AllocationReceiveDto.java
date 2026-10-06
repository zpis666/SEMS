package com.sems.module.station.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 调拨核收入库入参（见《系统设计说明书》5.3「POST /api/v1/allocations/{id}/receive」与 3.4.3 调拨流程）。
 *
 * <p>目标站站点管理员逐只扫码核收：袋 调拨在途→空闲（归属改目标站）、目标站库存 +1；
 * 全部明细核收完成时单据转「已完成」。逐袋独立处理、逐袋返回结果明细，单只失败不影响其余。
 */
@Data
public class AllocationReceiveDto {

    /** 本次核收的袋码清单（每项以 bagCode 为键，支持逐袋备注） */
    @NotEmpty(message = "袋码清单不能为空")
    @Valid
    private List<BagItem> bags;

    /** 整批备注（可空） */
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
