package com.sems.module.station.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑站点入参（见《系统设计说明书》5.3 PUT /api/v1/stations/{id}：名称、地址、开放时段、预警阈值等）。
 *
 * <p>名称与地址为站点档案核心字段，必填（名称需保持全平台唯一）；
 * 开放时段与预警阈值缺省时保持原值（兼容前端编辑表单的部分提交）。
 */
@Data
public class UpdateStationDto {

    @NotBlank(message = "站点名称不能为空")
    @Size(max = 50, message = "站点名称不能超过 50 个字符")
    private String name;

    @NotBlank(message = "站点地址不能为空")
    @Size(max = 200, message = "站点地址不能超过 200 个字符")
    private String address;

    @Size(max = 50, message = "开放时段不能超过 50 个字符")
    private String openTime;

    @Min(value = 0, message = "预警阈值不能为负数")
    private Integer warnThreshold;
}
