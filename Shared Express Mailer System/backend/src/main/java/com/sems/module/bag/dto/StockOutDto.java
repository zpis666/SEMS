package com.sems.module.bag.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 出库登记入参（批量）（见《系统设计说明书》5.4「POST /bags/stock-out」与
 * 《需求规格说明书》FR-M4-10/UC-S04：仅用于报废袋实物出库与经批准的盘点差异出库）。
 *
 * <p>逐袋校验场景白名单：报废袋直接放行（写 SCRAP_OUT 流水）；非报废袋必须携带
 * 盘点差异单号（写 OUT 流水，无单号报 4105）；维修中袋一律拒绝并引导走维修接口；
 * 在循环中的袋（已分配/使用中/回收在途/调拨在途）不得出库。出库只登记实物离站，
 * 不重复扣减库存（库存增减只由 3.2 状态转移表规定的事件触发）。
 */
@Data
public class StockOutDto {

    /** 逐袋项（每项以袋码为键）；数量上限读 {@code sems.batch.max-size}，在 Service 层校验并给出上限值 */
    @Valid
    @NotEmpty(message = "袋码清单不能为空")
    private List<Item> bags;

    /** 单袋项 */
    @Data
    public static class Item {

        /** 快递袋编码（SJB 定长码） */
        @NotBlank(message = "袋码不能为空")
        @Pattern(regexp = "^SJB-[A-Z]-\\d{4}-\\d{5}-[0-9A-Z]$", message = "袋码格式不正确")
        private String bagCode;

        /** 出库原因（如"报废移交""盘亏核销"） */
        @Size(max = 200, message = "出库原因长度不能超过 200")
        private String reason;

        /** 盘点差异单号（非报废袋必填，见需求 FR-M4-10「无盘点单号的盘点差异出库被拒」） */
        @Size(max = 32, message = "盘点单号长度不能超过 32")
        private String refCheckNo;
    }
}
