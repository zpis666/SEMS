package com.sems.module.station.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 回收箱待确认袋出参（见《系统设计说明书》5.3 GET /api/v1/stations/{id}/recycle-bin：
 * 回收箱待确认清单，袋列表，含「未标记但状态为使用中」的袋）。
 *
 * <p>两类来源（FR-M3-07 处理要点）：
 * ① 已标记投放本站的回收在途袋（订单 {@code return_station_id}=本站 且订单为待回收确认），{@code marked=true}；
 * ② 未标记但状态为使用中、袋台账在本站的袋（用户未标记即投放的候选清单），{@code marked=false}。
 * 清单仅作清点辅助，扫码确认接口不要求袋先出现在清单中（跨站未标记直投同样可确认，见 4.4 第④条）。
 */
@Data
public class RecycleBinVo {

    /** 袋编码 */
    private String bagCode;

    /** 袋状态码（回收在途 / 使用中） */
    private String status;

    /** 袋状态中文名 */
    private String statusName;

    /** 是否已标记归还（true=已标记投放本站的回收在途袋；false=未标记的使用中袋） */
    private Boolean marked;

    /** 绑定订单号（按 created_at 倒序的最新一笔，可能为空） */
    private String orderNo;

    /** 绑定订单状态码 */
    private String orderStatus;

    /** 绑定订单状态中文名 */
    private String orderStatusName;

    /** 订单归还站点ID（已标记时为本站） */
    private Long returnStationId;

    /** 标记归还时间（未标记时为空） */
    private LocalDateTime returnMarkAt;

    /** 寄件时间（判断是否曾寄出的辅助信息，可能为空） */
    private LocalDateTime shippedAt;
}
