package com.sems.module.audit.controller;

import com.sems.common.Constants;
import com.sems.common.PageResult;
import com.sems.common.Result;
import com.sems.module.audit.dto.AuditLogQuery;
import com.sems.module.audit.dto.AuditLogVo;
import com.sems.module.audit.dto.LoginLogQuery;
import com.sems.module.audit.dto.LoginLogVo;
import com.sems.module.audit.service.AuditQueryService;
import com.sems.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计查询控制器（M6 审计部分，接口清单见《系统设计说明书》5.6）：
 * 操作审计日志分页查询、登录日志分页查询，以及两者的「查看完整 IP」受控端点。
 *
 * <p><b>权限</b>：审计查询仅平台管理员可见（5.6 / 7.6），类上统一标
 * {@code @RequireRole("PLATFORM_ADMIN")}，方法级不再重复声明。
 *
 * <p><b>为什么 IP 有独立端点（设计书 7.6 口径）</b>：登录与操作 IP 仅平台管理员在审计查询中可见，
 * 列表默认部分掩码（如 {@code 192.168.*.*}），详情可看完整值，且<b>查看行为本身记审计</b>。
 * 因此列表接口（/audit-logs、/login-logs）只回掩码值；完整值另设
 * {@code GET /audit-logs/{id}/ip} 与 {@code GET /login-logs/{id}/ip} 两个端点按记录 ID 单独查看——
 * 独立成端点是为了避免列表接口在分页查询里顺带把整页完整 IP 批量返回给调用方，
 * 让「批量泄露」变成「逐条受控查看 + 逐条留痕」（落库动作由 AuditQueryService 完成）。
 *
 * <p><b>只读</b>：审计表按 7.6 防篡改三层设计只插入、只查询，
 * 本控制器不提供任何修改/删除接口。
 */
@RestController
@RequestMapping("/api/v1")
@RequireRole(Constants.ROLE_PLATFORM_ADMIN)
public class AuditQueryController {

    private final AuditQueryService auditQueryService;

    public AuditQueryController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    /**
     * 操作日志分页查询（5.6）：按操作人/模块/动作/结果/时间区间筛选，时间倒序。
     * IP 列返回掩码值（{@link AuditLogVo#getIpMasked()}），完整值需另调 {@code /audit-logs/{id}/ip}。
     */
    @GetMapping("/audit-logs")
    public Result<PageResult<AuditLogVo>> listAuditLogs(AuditLogQuery query) {
        return Result.ok(auditQueryService.pageAuditLogs(query));
    }

    /**
     * 登录日志分页查询（5.6）：按结果/手机号掩码/时间区间筛选，时间倒序。
     * IP 列返回掩码值（{@link LoginLogVo#getIpMasked()}），完整值需另调 {@code /login-logs/{id}/ip}。
     */
    @GetMapping("/login-logs")
    public Result<PageResult<LoginLogVo>> listLoginLogs(LoginLogQuery query) {
        return Result.ok(auditQueryService.pageLoginLogs(query));
    }

    /**
     * 查看单条审计记录的完整 IP（7.6 详情可看完整值）。
     * 本次查看行为会由服务层写入一条审计（module=「审计查询」、action=「查看完整IP」）。
     *
     * @return data 为完整 IP 字符串（记录 IP 为空时返回空串）
     */
    @GetMapping("/audit-logs/{id}/ip")
    public Result<String> viewAuditLogIp(@PathVariable Long id, HttpServletRequest request) {
        return Result.ok(auditQueryService.viewAuditLogIp(id, request));
    }

    /**
     * 查看单条登录日志的完整 IP（7.6 详情可看完整值）。
     * 本次查看行为会由服务层写入一条审计（module=「审计查询」、action=「查看完整IP」）。
     *
     * @return data 为完整 IP 字符串（记录 IP 为空时返回空串）
     */
    @GetMapping("/login-logs/{id}/ip")
    public Result<String> viewLoginLogIp(@PathVariable Long id, HttpServletRequest request) {
        return Result.ok(auditQueryService.viewLoginLogIp(id, request));
    }
}
