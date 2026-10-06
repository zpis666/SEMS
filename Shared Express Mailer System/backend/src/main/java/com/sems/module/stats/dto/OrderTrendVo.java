package com.sems.module.stats.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 订单趋势的单个数据点（{@code GET /api/v1/stats/order-trend} 的数组元素，
 * 见《系统设计说明书》5.6「近 N 日取袋/归还订单趋势（折线）」）。
 *
 * <p>口径（3.5：取袋按建单时间、归还按回收确认时间）：
 * {@code borrowCount} 按 {@code bag_order.created_at} 的自然日分组计数；
 * {@code returnCount} 按 {@code bag_order.confirmed_at} 的自然日分组计数。
 * 无数据的日期由服务层补 0，保证折线横轴连续。
 */
@Data
public class OrderTrendVo {

    /** 日期（自然日；Jackson 序列化为 yyyy-MM-dd） */
    private LocalDate date;

    /** 当日取袋（申领建单）订单数 */
    private Long borrowCount;

    /** 当日归还（回收确认完成）订单数 */
    private Long returnCount;
}
