package com.sems.module.stats.controller;

import com.sems.common.Constants;
import com.sems.common.Result;
import com.sems.module.stats.dto.DashboardVo;
import com.sems.module.stats.dto.OrderTrendVo;
import com.sems.module.stats.dto.StockDistributionVo;
import com.sems.module.stats.dto.TurnoverVo;
import com.sems.module.stats.service.StatsService;
import com.sems.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据统计控制器（M6 统计部分，四个端点逐条对应《系统设计说明书》5.6）：
 * 看板汇总、订单趋势、快递袋周转率、库存分布。
 *
 * <p><b>权限</b>：5.6 四个端点均为平台管理员；类上统一标
 * {@code @RequireRole("PLATFORM_ADMIN")}，方法级不再重复声明（等级制鉴权，见契约 4.4）。
 *
 * <p><b>只读</b>：全部为 GET 查询，不产生写操作，因此没有 clientRequestId 幂等头要求
 * （幂等只适用于写接口，见 9.6）。
 */
@RestController
@RequestMapping("/api/v1/stats")
@RequireRole(Constants.ROLE_PLATFORM_ADMIN)
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * 看板汇总（5.6）：在用袋数、在循环袋数、各状态分布、今日取/还、预警站点数、
     * 审计队列丢弃数 auditDropCount 与库存对账差异数 reconcileDiffCount。
     */
    @GetMapping("/dashboard")
    public Result<DashboardVo> dashboard() {
        return Result.ok(statsService.dashboard());
    }

    /**
     * 近 N 日取袋/归还订单趋势（5.6，折线图）：borrow 按建单时间、return 按回收确认时间分组，
     * 无数据日期补 0。
     *
     * @param days 统计天数（含今天），默认 7，取值 1-90
     */
    @GetMapping("/order-trend")
    public Result<List<OrderTrendVo>> orderTrend(
            @RequestParam(name = "days", required = false) Integer days) {
        return Result.ok(statsService.orderTrend(days));
    }

    /**
     * 快递袋周转率（5.6，柱状图）：按站点返回，周转率 = 周期内完成归还确认的订单数 ÷ 在循环袋数均值
     * （口径见 3.5；数据不足时返回 0）。
     *
     * @param days 统计周期（含今天），默认 30，取值 1-90
     */
    @GetMapping("/bag-turnover")
    public Result<List<TurnoverVo>> bagTurnover(
            @RequestParam(name = "days", required = false) Integer days) {
        return Result.ok(statsService.bagTurnover(days));
    }

    /** 库存分布（5.6，饼图）：按状态（Map，键=状态码）与按站点（在循环袋口径）返回。 */
    @GetMapping("/stock-distribution")
    public Result<StockDistributionVo> stockDistribution() {
        return Result.ok(statsService.stockDistribution());
    }
}
