package com.sems.module.stats.dto;

import lombok.Data;

import java.util.Map;

/**
 * 平台看板汇总出参（{@code GET /api/v1/stats/dashboard}，见《系统设计说明书》5.6；
 * 统计口径唯一出处为 3.5「关键时限与计数口径表」）。
 *
 * <p>字段口径逐条：
 * <ul>
 *   <li>{@link #inUseCount} 在用袋数：状态 ∈ {已分配, 使用中, 待消毒}（3.5）；</li>
 *   <li>{@link #inCirculationCount} 在循环袋数：非报废、非遗失（3.5，含各站点与在途）；</li>
 *   <li>{@link #todayBorrow}/{@link #todayReturn} 今日取/还：读 {@code stats:today:0} 汇总键，
 *       缺失时按各站点 {@code stats:today:{stationId}} 求和（5.6/8.3）；</li>
 *   <li>{@link #warningStationCount} 预警站点数：启用站点中可用库存低于 {@code warn_threshold} 的数量；</li>
 *   <li>{@link #statusDistribution} 各状态分布：键 = {@code BagStatus} 状态码，值 = 袋数（8 态补 0）；</li>
 *   <li>{@link #auditDropCount} 审计队列丢弃数：当日累计（7.6）；</li>
 *   <li>{@link #reconcileDiffCount} 库存对账差异数：当日累计（7.2 R4）。</li>
 * </ul>
 */
@Data
public class DashboardVo {

    /** 在用袋数（3.5：已分配/使用中/待消毒/可复用） */
    private Long inUseCount;

    /** 在循环袋数（3.5：非报废、非遗失） */
    private Long inCirculationCount;

    /**
     * 在循环袋数（{@link #inCirculationCount} 的兼容别名，两字段恒取同值）。
     *
     * <p>为什么保留两个字段：前端看板按「语义候选名」兼容读取该 KPI
     * （Dashboard.vue 的 pick：inCycleCount / inCycleBagCount / inCycle），
     * 均与本接口的正式命名 inCirculationCount 不同；不提供别名时该卡片会静默显示 0。
     * 口径以 3.5 的 inCirculationCount 为准，别名字段仅作联调兼容，后续前端对齐后可移除。
     */
    private Long inCycleCount;

    /** 今日取袋（申领）单数（读 stats:today 计数） */
    private Long todayBorrow;

    /** 今日归还（回收确认）单数（读 stats:today 计数） */
    private Long todayReturn;

    /** 预警站点数：启用站点中当前可用库存低于预警阈值的数量（阈值为 0 表示不预警） */
    private Integer warningStationCount;

    /** 各状态袋数分布：键 = BagStatus 状态码，值 = 袋数（8 态全集，无袋的状态为 0） */
    private Map<String, Long> statusDistribution;

    /** 审计队列丢弃数（当日累计，见 7.6；读 AuditLogService.getAuditDropCount） */
    private Long auditDropCount;

    /** 库存对账差异数（当日累计，见 7.2 R4；读 sys_metric.stock_reconcile_diff_count） */
    private Long reconcileDiffCount;
}
