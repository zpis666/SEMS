package com.sems.module.station.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增站点入参（见《系统设计说明书》5.3 POST /api/v1/stations：名称、地址、开放时段、预警阈值）。
 *
 * <p>名称全平台唯一（{@code station.uk_station_name}），由 Service 层查重后拒绝重复；
 * 新站默认 {@code ENABLED}，{@code warn_threshold} 缺省 0（0=不预警）。
 * 开放时段仅作展示与引导，不做硬校验（3.5「开放时段」一行）。
 */
@Data
public class CreateStationDto {

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
