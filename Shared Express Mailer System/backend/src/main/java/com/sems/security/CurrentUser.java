package com.sems.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controller 参数注解：标注在 {@link LoginUser} 参数上，由 WebMvcConfig 注册的参数解析器
 * 从 {@link AuthContext#require()} 取出当前登录用户注入（见《系统设计说明书》7.1、契约 4.6）。
 *
 * <p>用途：Controller 不必自己调 {@code AuthContext.require()}，签名即声明依赖，
 * 也便于接口文档（OpenAPI）识别「本接口需要登录」。
 *
 * <p>取值时未认证会抛 1001：正常请求已由 {@link AuthInterceptor} 认证通过，
 * 走到这里仍为空说明调用链异常（如异步线程丢失上下文），属于编程错误而非业务分支。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
