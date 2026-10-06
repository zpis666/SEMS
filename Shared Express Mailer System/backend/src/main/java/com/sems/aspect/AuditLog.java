package com.sems.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解（见《系统设计说明书》7.6）。
 *
 * <p>标注在 Controller 方法上，由 {@link AuditAspect} 环绕采集操作人、角色、IP、UA、
 * 模块/动作、脱敏入参、结果与耗时，并交给审计服务异步落库。
 *
 * <p>强制要求：登录失败、越权拦截（2001/2002）、状态机拒绝（4102）、报废、状态修正、
 * 回收确认等关键事件必须入审计；回收确认按上下文标注不同 action
 * （回收确认 直投 / 回收确认 跨站 / 回收确认 未标记直投），便于按动作筛出重点核查项。
 *
 * <p>命名提示：本注解与实体 {@code com.sems.module.audit.entity.AuditLogEntity} 同名不同包，
 * 两者不要互相 import，避免解析歧义。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 业务模块（如：快递袋管理） */
    String module();

    /** 业务动作（如：消毒登记） */
    String action();

    /** 是否记录脱敏入参，默认记录；查询类接口可置 false 以减小审计体积 */
    boolean recordParams() default true;
}
