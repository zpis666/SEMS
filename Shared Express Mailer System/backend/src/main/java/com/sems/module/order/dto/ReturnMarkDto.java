package com.sems.module.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 标记归还入参（见《系统设计说明书》5.2「标记'已投放回收箱'（选择站点，记 return_station_id）」、
 * 3.2 #6 与 3.4.2 辅助路径）。
 *
 * <p>只记订单的 {@code return_station_id}，<b>不改变袋归属</b>——袋的 {@code station_id}
 * 在站点回收确认事务中才改写为确认站点（见设计书 3.2 站点归属变更规则）。
 */
@Data
public class ReturnMarkDto {

    /** 投放的回收箱所属站点ID（必填；站点存在且启用由 Service 层校验） */
    @NotNull(message = "投放站点不能为空")
    private Long stationId;
}
