package com.sems.module.station.dto;

import lombok.Data;

import java.util.Map;

/**
 * 站点库存视图出参（见《系统设计说明书》5.3 GET /api/v1/stations/{id}/stock：
 * 站点各状态库存实时数，Redis 优先读取）。
 *
 * <p>{@code statusCounts} 覆盖全部 11 个袋状态（无记录补 0），键为 {@code BagStatus} 码值，
 * 便于前端库存四色卡片稳定渲染；{@code availableStock} 为可用库存
 * （Redis 优先、DB 权威兜底，口径见 3.1 与 7.2）；{@code warning} 为
 * 「可用库存低于预警阈值」的实时判定（阈值 0 表示不预警）。
 * 预警列表场景可不回传 {@code statusCounts}（字段为空时不序列化）。
 */
@Data
public class StationStockVo {

    /** 站点ID */
    private Long stationId;

    /** 站点名称 */
    private String stationName;

    /** 站点地址（预警列表供调拨调度判断用） */
    private String address;

    /** 各状态计数：状态码 → 袋数（8 态全量，无记录补 0） */
    private Map<String, Long> statusCounts;

    /** 可用库存（该站点空闲袋数） */
    private Integer availableStock;

    /** 库存预警阈值（0=不预警） */
    private Integer warnThreshold;

    /** 是否处于预警（可用库存 < 阈值且阈值 > 0） */
    private Boolean warning;
}
