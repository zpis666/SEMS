package com.sems.module.bag.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 批量生成袋档案（二维码压缩包）入参（见《系统设计说明书》5.4「POST /api/v1/bags/batch-generate」）。
 *
 * <p>整批原子（一个事务）：任一只落库失败则整批回滚，避免出现"打印了标签却没有档案"的批次；
 * 数量上限由 {@code sems.batch.max-size} 配置（默认 500，见设计书 6.2），
 * 因此不在 DTO 上写死 @Max，而是在 Service 层读配置校验并给出含上限值的提示。
 */
@Data
public class BatchGenerateDto {

    /** 规格码：1 位大写字母，嵌入袋码（如 L） */
    @NotBlank(message = "规格不能为空")
    @Pattern(regexp = "^[A-Z]$", message = "规格须为 1 位大写字母（如 L）")
    private String spec;

    /** 颜色（实物描述，展示用） */
    @NotBlank(message = "颜色不能为空")
    @Size(max = 20, message = "颜色长度不能超过 20")
    private String color;

    /** 容量（实物描述，展示用） */
    @NotBlank(message = "容量不能为空")
    @Size(max = 50, message = "容量长度不能超过 50")
    private String capacity;

    /** 登记站点 ID（本批全部袋的初始归属站点） */
    @NotNull(message = "登记站点不能为空")
    private Long stationId;

    /** 本批生成数量（≥1，且不超过 {@code sems.batch.max-size}） */
    @NotNull(message = "生成数量不能为空")
    @Min(value = 1, message = "生成数量须大于 0")
    private Integer count;
}
