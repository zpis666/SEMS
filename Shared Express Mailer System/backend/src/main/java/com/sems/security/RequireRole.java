package com.sems.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口角色要求（见《系统设计说明书》7.1 拦截链、9.3 访问控制）。
 *
 * <p>由鉴权拦截器（AuthzInterceptor）读取：类上的注解为默认值，方法上的注解覆盖类级。
 *
 * <p><b>等级制规则</b>（契约 4.4）：
 * {@code 实际角色等级 >= 注解要求的最小等级} 即放行，
 * 等级基准见 {@link LoginUser#roleLevel()}（USER=1、STATION_ADMIN=2、PLATFORM_ADMIN=3）。
 * 于是 {@code @RequireRole("USER")} 三种角色都过、{@code @RequireRole("STATION_ADMIN")}
 * 站点与平台管理员可过、{@code @RequireRole("PLATFORM_ADMIN")} 仅平台管理员可过。
 * 不满足抛 {@code BizException(FORBIDDEN)}（2001）。
 *
 * <p><b>边界</b>：本注解只解决垂直越权（角色够不够）。「本站/本人」等水平越权
 * 由 Service 层比对数据归属、不符抛 {@code NO_DATA_PERMISSION}（2002）——
 * 例如站点管理员能否操作某个站点对象，注解答不了，见契约 10。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许访问的角色码；按「最小等级」语义解释（见类头规则），而非字面枚举全部角色。 */
    String[] value();
}
