package com.sems.module.bag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 直接报废登记入参（见《系统设计说明书》5.4「POST /bags/{bagCode}/scrap」、3.2 #15）。
 *
 * <p>报废原因必填（需求 FR-M4-14），写入 {@code scrap_record.reason}；
 * 报废为终态且属审计重点核查项（设计书 7.6），接口审计动作固定为「直接报废」。
 * 维修中袋无法修复时不走本接口，由 repair-complete 按 #14 报废（见 5.4 表说明）。
 */
@Data
public class ScrapDto {

    /** 报废原因（严重破损/超限/无法修复等） */
    @NotBlank(message = "报废原因不能为空")
    @Size(max = 255, message = "报废原因长度不能超过 255")
    private String reason;
}
