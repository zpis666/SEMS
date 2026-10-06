package com.sems.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 存活探针（见《系统设计说明书》6.3）。
 *
 * <p>与 {@code /auth/register}、{@code /auth/login}、{@code /auth/captcha} 一样，
 * 本端点是**不要求 JWT** 的少数接口之一，供负载均衡/部署脚本探活。
 *
 * <p><b>口径要求</b>：只返回存活状态，<b>不泄露任何内部信息</b>——
 * 不返回数据库/Redis 连接状态、版本号、主机名、构建信息，避免为攻击者提供指纹（见 9.1 信息泄露对策）。
 * 需要看依赖健康度请走内网监控，不要加到本端点。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Result<Map<String, String>> health() {
        return Result.ok(Map.of("status", "UP"));
    }
}
