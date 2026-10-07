package com.sems.config;

import com.sems.security.AuthContext;
import com.sems.security.AuthInterceptor;
import com.sems.security.AuthzInterceptor;
import com.sems.security.CurrentUser;
import com.sems.security.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Spring MVC 全局配置：拦截器链、当前登录人参数解析、字符编码与开发期 CORS。
 *
 * <p>设计出处：《系统设计说明书》7.1「拦截链：解析 token → HandlerInterceptor 做 @RequireRole 鉴权
 * → Service 层数据权限校验」；9.4「生产同域部署（Nginx 托管前端 + 反代 API），CORS 默认关闭」。
 *
 * <p>两个安全拦截器共用同一份白名单：认证（{@link AuthInterceptor}）在前、鉴权（{@link AuthzInterceptor}）在后，
 * 用 order 显式区分先后 —— 鉴权拦截器依赖认证拦截器写入的 {@link AuthContext}。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebMvcConfig.class);

    /** 允许放开 CORS 的 profile（契约 4.7：默认关闭，仅 dev 放开）。 */
    private static final String DEV_PROFILE = "dev";

    /** 开发期跨域白名单：Vite 前端地址与后端自身地址（前端契约见设计书第 12 节）。 */
    private static final String[] DEV_ALLOWED_ORIGINS = {
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:8090"
    };

    /**
     * 免登录白名单（契约 4.5）：对认证、鉴权两个拦截器同时生效。
     *
     * <p>只放最小集合，避免通配符误放行业务接口；{@code /error} 是容器错误转发，
     * 若不放行会在异常处理路径上再次触发鉴权、掩盖原始错误。
     */
    private static final String[] EXCLUDE_PATHS = {
            "/api/health",
            "/api/v1/auth/captcha",
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            // WebSocket 握手不携带 Authorization 头，鉴权由 SemsWsHandler 的首帧 auth 完成（契约 7.2）
            "/ws/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error",
            "/favicon.ico"
    };

    private final AuthInterceptor authInterceptor;
    private final AuthzInterceptor authzInterceptor;
    private final Environment environment;

    public WebMvcConfig(AuthInterceptor authInterceptor, AuthzInterceptor authzInterceptor, Environment environment) {
        this.authInterceptor = authInterceptor;
        this.authzInterceptor = authzInterceptor;
        this.environment = environment;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // order：认证 1 → 鉴权 2。注册顺序之外的显式排序可防止后续新增拦截器时插队，
        // 保证 AuthzInterceptor 看到的 AuthContext 一定已由认证层建立
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(EXCLUDE_PATHS)
                .order(1);
        registry.addInterceptor(authzInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(EXCLUDE_PATHS)
                .order(2);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        // 标注 @CurrentUser 的参数注入当前登录人（契约 4.6）
        resolvers.add(new CurrentUserResolver());
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 字符串响应统一 UTF-8：server.servlet.encoding 管不到拦截器/异常处理器里直接写 String 的分支，
        // 这里显式指定，避免中文提示在无 charset 的响应上乱码。
        // 用 extend（而非 configure）是为了保留 Spring Boot 注册的默认转换器，尤其是 JSON 转换器
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof StringHttpMessageConverter stringConverter) {
                stringConverter.setDefaultCharset(StandardCharsets.UTF_8);
                return;
            }
        }
        converters.add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 生产为同域部署（前端静态产物与 API 同一 Nginx），无需 CORS；只在本地开发放开
        if (!environment.acceptsProfiles(Profiles.of(DEV_PROFILE))) {
            return;
        }
        registry.addMapping("/**")
                .allowedOrigins(DEV_ALLOWED_ORIGINS)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                // dev 环境按需放开请求头：Authorization（JWT）与 clientRequestId（幂等头，契约 3.2）都在其中
                .allowedHeaders("*")
                // 二维码 ZIP 下载需要前端读取文件名（契约：batch-generate 为二进制流响应）
                .exposedHeaders(HttpHeaders.CONTENT_DISPOSITION)
                // 会话走 Authorization 头而非 Cookie，不需要凭据模式
                .maxAge(3600);
        log.info("检测到 dev profile：已为 {} 开放 CORS（生产环境保持关闭）", String.join("、", DEV_ALLOWED_ORIGINS));
    }

    /**
     * {@code @CurrentUser LoginUser user} 参数解析器（契约 4.6）。
     *
     * <p>解析动作只有一句委托（取 {@link AuthContext}），没有独立状态与依赖，
     * 因此作为配置类的私有嵌套类实现，与注册处放在同一文件维护。
     */
    private static final class CurrentUserResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(CurrentUser.class)
                    && LoginUser.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            // require() 在上文缺失时抛 1001：能进入 Controller 的路径本就应该已经通过认证拦截器
            return AuthContext.require();
        }
    }
}
