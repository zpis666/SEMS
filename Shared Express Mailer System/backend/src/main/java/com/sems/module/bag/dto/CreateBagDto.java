package com.sems.module.bag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建袋档案入参（见《系统设计说明书》5.4「POST /api/v1/bags」与 7.4 编码规则）。
 *
 * <p>编码由后端自动生成（{@code SJB-规格-年月-流水-校验}），不由前端传入；
 * 规格码同时是袋码的规格段，限定为 1 位大写字母（与 {@code BagCodeUtils} 的口径一致）。
 * 新袋初始状态为「空闲」，登记站点写入 {@code bag.station_id}（设计书 3.2 站点归属说明）。
 */
@Data
public class CreateBagDto {

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

    /** 登记站点 ID（建袋即写入袋归属，激活上架时按此校验本站） */
    @NotNull(message = "登记站点不能为空")
    private Long stationId;
}
