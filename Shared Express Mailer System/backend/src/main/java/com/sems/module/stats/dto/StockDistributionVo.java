package com.sems.module.stats.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 库存分布出参（{@code GET /api/v1/stats/stock-distribution}，供饼图使用，
 * 见《系统设计说明书》5.6「库存分布（按站点/按状态，饼图）」）。
 *
 * <p>两个维度：
 * <ul>
 *   <li>{@link #statusDistribution} 按状态：键 = {@code BagStatus} 状态码、值 = 袋数（11 态补 0），
 *       与看板 {@code DashboardVo.statusDistribution} 同构，前端饼图直接按状态码映射中文名；</li>
 *   <li>{@link #byStation} 按站点：每站点当前存放的在循环袋数（非报废、非遗失，3.5）——
 *       报废已退出循环、遗失不在站点，均不计入站点分布。</li>
 * </ul>
 */
@Data
public class StockDistributionVo {

    /** 按状态分布：键 = 状态码，值 = 袋数（11 态全集，无袋的状态为 0） */
    private Map<String, Long> statusDistribution;

    /** 按站点分布（在循环袋口径，含站点名；无袋的站点保留 count=0，便于前端表格/饼图自行过滤） */
    private List<StationSlice> byStation;

    /** 按站点分布的单个数据片（嵌套 DTO，仅服务本 VO 序列化） */
    @Data
    public static class StationSlice {

        /** 站点ID */
        private Long stationId;

        /** 站点名称 */
        private String stationName;

        /** 该站点当前存放的在循环袋数 */
        private Long count;
    }
}
