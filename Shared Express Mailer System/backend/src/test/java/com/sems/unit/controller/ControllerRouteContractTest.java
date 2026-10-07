package com.sems.unit.controller;

import com.sems.aspect.AuditLog;
import com.sems.common.HealthController;
import com.sems.module.audit.controller.AuditQueryController;
import com.sems.module.auth.controller.AuthController;
import com.sems.module.bag.controller.BagController;
import com.sems.module.bag.controller.DisinfectController;
import com.sems.module.bag.controller.InventoryCheckController;
import com.sems.module.order.controller.BorrowController;
import com.sems.module.order.controller.OrderController;
import com.sems.module.stats.controller.AlertController;
import com.sems.module.stats.controller.StatsController;
import com.sems.module.station.controller.AllocationController;
import com.sems.module.station.controller.StationController;
import com.sems.module.user.controller.UserController;
import com.sems.security.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** REST 端点契约：路径/HTTP 方法、垂直权限与高风险审计注解需与接口表保持一致。 */
class ControllerRouteContractTest {

    private record Route(String name, String method, String path, String role, boolean audited) {
    }

    @Test
    @DisplayName("所有公开 Controller 端点的路由与权限等级固定，写操作审计标注完整")
    void routesRolesAndAuditsMatchContract() {
        Map<Class<?>, Map<String, Route>> expected = expectedRoutes();
        for (Map.Entry<Class<?>, Map<String, Route>> controllerEntry : expected.entrySet()) {
            Class<?> controller = controllerEntry.getKey();
            RequestMapping baseMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            assertNotNull(baseMapping, controller.getSimpleName() + " 缺少资源根路由");
            String base = first(baseMapping.path(), baseMapping.value());

            Set<Method> publicMethods = java.util.Arrays.stream(controller.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
                    .collect(Collectors.toSet());
            assertEquals(controllerEntry.getValue().keySet(), publicMethods.stream().map(Method::getName)
                    .collect(Collectors.toSet()), controller.getSimpleName() + " 公开端点增删应同步接口契约");

            for (Method method : publicMethods) {
                Route route = controllerEntry.getValue().get(method.getName());
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                assertNotNull(mapping, controller.getSimpleName() + "." + method.getName() + " 缺少 HTTP 路由");
                assertEquals(route.method(), mapping.method()[0].name(), method.toString());
                assertEquals(join(base, first(mapping.path(), mapping.value())), route.path(), method.toString());

                RequireRole required = AnnotatedElementUtils.findMergedAnnotation(method, RequireRole.class);
                if (required == null) {
                    required = AnnotatedElementUtils.findMergedAnnotation(controller, RequireRole.class);
                }
                String actualRole = required == null ? "ANONYMOUS" : required.value()[0];
                assertEquals(route.role(), actualRole, controller.getSimpleName() + "." + method.getName());
                assertEquals(route.audited(), AnnotatedElementUtils.hasAnnotation(method, AuditLog.class),
                        controller.getSimpleName() + "." + method.getName() + " 审计注解口径");
            }
        }
    }

    private static Map<Class<?>, Map<String, Route>> expectedRoutes() {
        Map<Class<?>, Map<String, Route>> all = new LinkedHashMap<>();
        all.put(AuthController.class, routes(
                r("captcha", "GET", "/api/v1/auth/captcha", "ANONYMOUS", false),
                r("register", "POST", "/api/v1/auth/register", "ANONYMOUS", true),
                r("login", "POST", "/api/v1/auth/login", "ANONYMOUS", true),
                r("refresh", "POST", "/api/v1/auth/refresh", "USER", false),
                r("logout", "POST", "/api/v1/auth/logout", "USER", true)));
        all.put(UserController.class, routes(
                r("getMe", "GET", "/api/v1/users/me", "USER", false),
                r("updateMe", "PATCH", "/api/v1/users/me", "USER", true),
                r("changePassword", "POST", "/api/v1/users/me/password", "USER", true),
                r("createUser", "POST", "/api/v1/users", "PLATFORM_ADMIN", true),
                r("updateRoles", "PUT", "/api/v1/users/{id}/roles", "PLATFORM_ADMIN", true),
                r("updateStation", "PUT", "/api/v1/users/{id}/station", "PLATFORM_ADMIN", true),
                r("pageUsers", "GET", "/api/v1/users", "PLATFORM_ADMIN", false),
                r("updateStatus", "PATCH", "/api/v1/users/{id}/status", "PLATFORM_ADMIN", true),
                r("resetPassword", "POST", "/api/v1/users/{id}/password-reset", "PLATFORM_ADMIN", true)));
        all.put(BagController.class, routes(
                r("create", "POST", "/api/v1/bags", "PLATFORM_ADMIN", true),
                r("batchGenerate", "POST", "/api/v1/bags/batch-generate", "PLATFORM_ADMIN", true),
                r("page", "GET", "/api/v1/bags", "STATION_ADMIN", false),
                r("info", "GET", "/api/v1/bags/{bagCode}/info", "USER", false),
                r("qrcode", "GET", "/api/v1/bags/{bagCode}/qrcode", "STATION_ADMIN", false),
                r("trace", "GET", "/api/v1/bags/{bagCode}/trace", "STATION_ADMIN", false),
                r("activate", "POST", "/api/v1/bags/{bagCode}/activate", "STATION_ADMIN", true),
                r("stockIn", "POST", "/api/v1/bags/stock-in", "STATION_ADMIN", true),
                r("unusedReturn", "POST", "/api/v1/bags/{bagCode}/unused-return", "STATION_ADMIN", true),
                r("stockOut", "POST", "/api/v1/bags/stock-out", "STATION_ADMIN", true),
                r("repair", "POST", "/api/v1/bags/{bagCode}/repair", "STATION_ADMIN", true),
                r("repairComplete", "POST", "/api/v1/bags/{bagCode}/repair-complete", "STATION_ADMIN", true),
                r("scrap", "POST", "/api/v1/bags/{bagCode}/scrap", "STATION_ADMIN", true),
                r("lostRecovery", "POST", "/api/v1/bags/{bagCode}/lost-recovery", "PLATFORM_ADMIN", true),
                r("statusCorrect", "POST", "/api/v1/bags/{bagCode}/status-correct", "PLATFORM_ADMIN", true)));
        all.put(InventoryCheckController.class, routes(
                r("page", "GET", "/api/v1/inventory-checks", "STATION_ADMIN", false),
                r("submit", "POST", "/api/v1/inventory-checks", "STATION_ADMIN", true),
                r("voidCheck", "POST", "/api/v1/inventory-checks/{id}/void", "STATION_ADMIN", true)));
        all.put(DisinfectController.class, routes(
                r("page", "GET", "/api/v1/disinfections", "STATION_ADMIN", false),
                r("create", "POST", "/api/v1/disinfections", "STATION_ADMIN", true)));
        all.put(AuditQueryController.class, routes(
                r("listAuditLogs", "GET", "/api/v1/audit-logs", "PLATFORM_ADMIN", false),
                r("listLoginLogs", "GET", "/api/v1/login-logs", "PLATFORM_ADMIN", false),
                r("viewAuditLogIp", "GET", "/api/v1/audit-logs/{id}/ip", "PLATFORM_ADMIN", false),
                r("viewLoginLogIp", "GET", "/api/v1/login-logs/{id}/ip", "PLATFORM_ADMIN", false)));
        all.put(StatsController.class, routes(
                r("dashboard", "GET", "/api/v1/stats/dashboard", "PLATFORM_ADMIN", false),
                r("orderTrend", "GET", "/api/v1/stats/order-trend", "PLATFORM_ADMIN", false),
                r("bagTurnover", "GET", "/api/v1/stats/bag-turnover", "PLATFORM_ADMIN", false),
                r("stockDistribution", "GET", "/api/v1/stats/stock-distribution", "PLATFORM_ADMIN", false)));
        all.put(AlertController.class, routes(
                r("listAlerts", "GET", "/api/v1/alerts", "PLATFORM_ADMIN", false)));
        all.put(AllocationController.class, routes(
                r("page", "GET", "/api/v1/allocations", "STATION_ADMIN", false),
                r("detail", "GET", "/api/v1/allocations/{id}", "STATION_ADMIN", false),
                r("create", "POST", "/api/v1/allocations", "PLATFORM_ADMIN", true),
                r("cancelOrTerminate", "DELETE", "/api/v1/allocations/{id}", "PLATFORM_ADMIN", true),
                r("dispatch", "POST", "/api/v1/allocations/{id}/dispatch", "STATION_ADMIN", true),
                r("receive", "POST", "/api/v1/allocations/{id}/receive", "STATION_ADMIN", true),
                r("lostConfirm", "POST", "/api/v1/allocations/{id}/lost-confirm", "PLATFORM_ADMIN", true),
                r("removeDetail", "POST", "/api/v1/allocations/{id}/remove-detail", "PLATFORM_ADMIN", true)));
        all.put(BorrowController.class, routes(
                r("borrow", "POST", "/api/v1/borrows", "USER", true),
                r("shipping", "POST", "/api/v1/borrows/{orderNo}/shipping", "USER", true),
                r("withdraw", "DELETE", "/api/v1/borrows/{orderNo}", "USER", true)));
        all.put(OrderController.class, routes(
                r("mine", "GET", "/api/v1/orders/mine", "USER", false),
                r("list", "GET", "/api/v1/orders", "PLATFORM_ADMIN", false),
                r("timeout", "GET", "/api/v1/orders/timeout", "PLATFORM_ADMIN", false),
                r("detail", "GET", "/api/v1/orders/{orderNo}", "USER", true),
                r("returnMark", "POST", "/api/v1/orders/{orderNo}/return-mark", "USER", true),
                r("lostConfirm", "POST", "/api/v1/orders/{orderNo}/lost-confirm", "PLATFORM_ADMIN", true)));
        all.put(StationController.class, routes(
                r("page", "GET", "/api/v1/stations", "PLATFORM_ADMIN", false),
                r("create", "POST", "/api/v1/stations", "PLATFORM_ADMIN", true),
                r("update", "PUT", "/api/v1/stations/{id}", "PLATFORM_ADMIN", true),
                r("updateStatus", "PATCH", "/api/v1/stations/{id}/status", "PLATFORM_ADMIN", true),
                r("stock", "GET", "/api/v1/stations/{id}/stock", "STATION_ADMIN", false),
                r("stockWarning", "GET", "/api/v1/stations/stock-warning", "PLATFORM_ADMIN", false),
                r("recycleBin", "GET", "/api/v1/stations/{id}/recycle-bin", "STATION_ADMIN", false),
                r("recycleConfirm", "POST", "/api/v1/stations/{id}/recycle-confirm", "STATION_ADMIN", true),
                r("nearby", "GET", "/api/v1/stations/nearby", "USER", false)));
        all.put(HealthController.class, routes(
                r("health", "GET", "/api/health", "ANONYMOUS", false)));
        return all;
    }

    private static Map<String, Route> routes(Route... routes) {
        Map<String, Route> result = new LinkedHashMap<>();
        for (Route route : routes) {
            result.put(route.name(), route);
        }
        return result;
    }

    private static Route r(String name, String method, String path, String role, boolean audited) {
        return new Route(name, method, path, role, audited);
    }

    private static String first(String[] path, String[] value) {
        if (path.length > 0 && !path[0].isEmpty()) {
            return path[0];
        }
        return value.length == 0 ? "" : value[0];
    }

    private static String join(String base, String local) {
        if (base.isEmpty()) {
            return local;
        }
        if (local.isEmpty()) {
            return base;
        }
        return base.endsWith("/") ? base + local.substring(1) : base + local;
    }
}
