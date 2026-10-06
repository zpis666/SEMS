package com.sems.module.stats.controller;

import com.sems.common.Constants;
import com.sems.common.PageResult;
import com.sems.common.Result;
import com.sems.module.stats.dto.AlertQuery;
import com.sems.module.stats.dto.AlertVo;
import com.sems.module.stats.service.AlertService;
import com.sems.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异常与风控告警控制器（{@code GET /api/v1/alerts}，见《系统设计说明书》5.6）。
 *
 * <p>端点归属说明：/audit-logs、/login-logs 归 module/audit 的审计查询控制器；
 * /alerts 归本控制器（/stats/** 归 StatsController）——避免两个模块抢同一端点，
 * 划分依据见并行开发契约模块版 8.7 的最终裁定。
 *
 * <p><b>权限</b>：告警清单仅平台管理员可见（5.6），类上标
 * {@code @RequireRole("PLATFORM_ADMIN")}；清单无「本站/本人」维度，无水平越权校验。
 *
 * <p><b>只读</b>：GET 查询，无幂等头要求（幂等只适用于写接口，见 9.6）。
 */
@RestController
@RequestMapping("/api/v1/alerts")
@RequireRole(Constants.ROLE_PLATFORM_ADMIN)
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 异常与风控告警清单（5.6）：七类告警（四类异常 + 两类系统指标 + 一类风控线索），
     * 按类型 / 时间区间筛选，按发生时间倒序分页返回；清单项含 actionUrl 供前端直接跳转处置。
     *
     * @param query 类型码、时间区间（闭区间，含边界）、分页参数（pageNum 默认 1、pageSize 默认 10 最大 100）
     */
    @GetMapping
    public Result<PageResult<AlertVo>> listAlerts(AlertQuery query) {
        return Result.ok(alertService.pageAlerts(query));
    }
}
