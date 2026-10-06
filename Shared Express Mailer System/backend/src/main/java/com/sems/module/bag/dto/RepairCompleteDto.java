package com.sems.module.bag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 维修完成入参（见《系统设计说明书》5.4「POST /bags/{bagCode}/repair-complete」、3.2 #13/#14）。
 *
 * <p>结果=修复 → 可复用（#13）；结果=无法修复 → 报废（#14）。
 * 另有强制报废分支：结果=修复但 {@code use_count ≥ max_use_count} 时按 #14 报废，
 * 不得回可复用（需求 FR-M4-13 备选流 6a；上限口径见设计书 3.5）。
 */
@Data
public class RepairCompleteDto {

    /** 维修结果：修复 / 无法修复 */
    @NotBlank(message = "维修结果不能为空")
    @Pattern(regexp = "^(修复|无法修复)$", message = "维修结果只能为「修复」或「无法修复」")
    private String result;

    /** 维修说明（可选，写入流转日志） */
    @Size(max = 255, message = "维修说明长度不能超过 255")
    private String remark;
}
