package com.sems.module.station.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 停用/启用站点入参（见《系统设计说明书》5.3 PATCH /api/v1/stations/{id}/status）。
 *
 * <p>停用前置（5.3 表后说明）：可用库存为 0 且（非终态实物为 0 或已指定接收站）；
 * 接收站经 {@code handoverStationId} 指定，Service 层校验其存在且非本站后写入
 * {@code station.handover_station_id}。
 */
@Data
public class StationStatusDto {

    @NotBlank(message = "目标状态不能为空")
    @Pattern(regexp = "ENABLED|DISABLED", message = "状态仅支持 ENABLED（启用）或 DISABLED（停用）")
    private String status;

    /** 停用时指定的接收站ID（可空；站点仍有非终态实物时必填） */
    private Long handoverStationId;
}
