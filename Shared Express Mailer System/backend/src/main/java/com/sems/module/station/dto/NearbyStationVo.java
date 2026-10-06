package com.sems.module.station.dto;

import lombok.Data;

/**
 * 站点列表出参（见《系统设计说明书》5.2 GET /api/v1/stations/nearby：
 * 名称/位置/可用库存，过滤停用站，供取袋与归还引导）。
 *
 * <p>可用库存读取口径：Redis 计数优先（{@code StockService.getAvailable}），
 * 计数未就绪时回落 DB 统计的 IDLE 袋数（3.1 权威口径）。
 */
@Data
public class NearbyStationVo {

    /** 站点ID */
    private Long id;

    /** 站点名称 */
    private String name;

    /** 站点地址 */
    private String address;

    /** 开放时段展示文本 */
    private String openTime;

    /** 可用库存（该站点空闲袋数） */
    private Integer availableStock;
}
