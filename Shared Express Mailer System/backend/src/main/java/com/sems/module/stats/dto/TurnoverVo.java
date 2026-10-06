package com.sems.module.stats.dto;

import lombok.Data;

/**
 * 站点快递袋周转率的单个数据点（{@code GET /api/v1/stats/bag-turnover} 的数组元素，
 * 见《系统设计说明书》5.6「快递袋周转率（按周期/按站点，柱状）」）。
 *
 * <p>周转率口径（3.5）：统计周期内完成归还确认的订单数 ÷ 在循环袋数均值。
 * 分母为 0（该站点无在循环袋）属数据不足，服务层返回 0 而不是抛异常，
 * 保证柱状图在仅有部分站点有数据时仍可整图渲染。
 */
@Data
public class TurnoverVo {

    /** 站点ID */
    private Long stationId;

    /** 站点名称 */
    private String stationName;

    /** 周期周转率（保留 4 位小数；数据不足时为 0） */
    private Double turnoverRate;
}
