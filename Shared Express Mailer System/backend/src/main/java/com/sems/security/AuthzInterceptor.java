package com.sems.security;

import com.sems.common.BizException;
import com.sems.common.event.AuditEvent;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 角色鉴权拦截器：落实 {@link RequireRole} 的等级制校验
 * （见《系统设计说明书》4.2 权限模型、4.3 角色功能权限矩阵、9.3 访问控制）。
 *
 * <p>与 {@link AuthInterceptor} 的分工：认证（token 解析、两级吊销、账号状态）在前，
 * 本拦截器在后，只读 {@link AuthContext} 中已就绪的登录人做粗粒度鉴权；
 * 细粒度的数据归属（本人/本站）仍由 Service 层校验（见设计书 4.4）。
 *
 * <p>鉴权规则（契约 4.4）：
 * <ul>
 *   <li>方法级 {@code @RequireRole} 优先于类级；</li>
 *   <li>注解声明的角色数组取<b>最小</b>等级作为门槛，{@code LoginUser.roleLevel() >= 门槛} 即放行
 *       （因此 {@code @RequireRole("USER")} 等价于「任意登录用户」，与设计书 4.3 的继承规则一致）；</li>
 *   <li>未标注 {@code @RequireRole} 的方法视为「仅需登录」，同样要求登录上下文存在；</li>
 *   <li>不达线抛 {@link ErrorCode#FORBIDDEN}（2001），由全局异常处理器转为 403。</li>
 * </ul>
 */
@Component
public class AuthzInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthzInterceptor.class);

    /** USER 的最低等级（与 {@code LoginUser#roleLevel()} 口径一致，见契约 4.1/4.4）。 */
    private static final int LEVEL_USER = 1;
    /** STATION_ADMIN 的最低等级。 */
    private static final int LEVEL_STATION_ADMIN = 2;
    /** PLATFORM_ADMIN 的最低等级。 */
    private static final int LEVEL_PLATFORM_ADMIN = 3;

    /** 越权尝试的规范化审计动作（7.6 要求「可按动作筛出」，故不能沿用原接口动作）。 */
    public static final String ACTION_FORBIDDEN_ATTEMPT = "越权尝试";

    /** 审计事件发布器：发布方只依赖 common 包的 AuditEvent，不反向依赖 module/audit。 */
    private final ApplicationEventPublisher eventPublisher;

    public AuthzInterceptor(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 只对 Controller 方法做注解鉴权；静态资源等非 HandlerMethod 处理器不参与
        // （免登录路径已在 WebMvcConfig 注册拦截器时整体排除，不会进入本方法）
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 未标注 @RequireRole 的方法同样要求登录：require() 在无上下文时抛 1001。
        // 这里也顺带兜底了「拦截器装配顺序被改动、认证层未执行」的空窗
        LoginUser loginUser = AuthContext.require();

        RequireRole requireRole = resolveRequireRole(handlerMethod);
        if (requireRole == null) {
            return true;
        }

        int threshold = minRequiredLevel(requireRole.value());
        if (loginUser.roleLevel() < threshold) {
            String detail = String.format(
                    "要求角色=%s（门槛等级 %d），实际角色=%s（等级 %d），handler=%s",
                    String.join(",", requireRole.value()), threshold,
                    loginUser.getRole(), loginUser.roleLevel(), handlerMethod.getShortLogMessage());
            log.warn("越权拦截：uri={}, uid={}, {}", request.getRequestURI(), loginUser.getUserId(), detail);
            // 越权尝试属设计书 7.6 的必审事件，且拦截发生在 Controller 之前——切面根本看不到这次请求，
            // 因此必须由本层主动投递。投递走 common 包的 AuditEvent（发布方不依赖 module/audit，
            // 依赖方向见契约第 1 节），订阅端是 AuditLogService；无监听者时事件被静默丢弃，不影响拦截结果。
            publishAudit(request, loginUser, detail);
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return true;
    }

    /** 投递一条「越权尝试」审计事件；投递失败只记 ERROR，绝不改变拦截本身的结果。 */
    private void publishAudit(HttpServletRequest request, LoginUser loginUser, String detail) {
        try {
            AuditEvent event = new AuditEvent();
            event.setOperatorId(loginUser.getUserId());
            event.setOperatorRole(loginUser.getRole());
            event.setIp(resolveClientIp(request));
            event.setUa(truncate(request.getHeader("User-Agent"), 255));
            // module 写「安全」而非原业务模块：这条记录的性质是安全事件，
            // 原接口上下文已由 request_uri 与 paramsMasked 保留
            event.setModule("安全");
            event.setAction(ACTION_FORBIDDEN_ATTEMPT);
            event.setRequestUri(truncate(request.getRequestURI(), 255));
            event.setParamsMasked(truncate(detail, 4096));
            event.setResult(0);
            event.setErrorCode(ErrorCode.FORBIDDEN.getCode());
            event.setCostMs(0);
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.error("越权审计投递失败（不影响拦截结果）：uri={}", request.getRequestURI(), e);
        }
    }

    /**
     * 取客户端 IP：生产经 Nginx 反代，remoteAddr 只会是代理地址，故优先取 X-Forwarded-For 首个。
     * 与 {@code AuditAspect} 的口径一致；此处独立实现是为了不让 security 包依赖 aspect 包。
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "" : remote;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 取生效的 {@link RequireRole}：方法级优先、其次类级（契约 4.4）。
     *
     * <p>用 {@link AnnotatedElementUtils} 而非 {@code getAnnotation}，以兼容组合注解/元注解写法，
     * 与 Spring 自身的注解查找语义保持一致。
     */
    private RequireRole resolveRequireRole(HandlerMethod handlerMethod) {
        RequireRole methodLevel = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequireRole.class);
        if (methodLevel != null) {
            return methodLevel;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequireRole.class);
    }

    /** 角色数组取最小 roleLevel 作为门槛：声明多角色时按最宽松者放行（契约 4.4）。 */
    private static int minRequiredLevel(String[] roles) {
        if (roles == null || roles.length == 0) {
            // 空声明属编码错误：宁可 500 暴露问题，也绝不 fail-open 放行管理接口
            throw new BizException(ErrorCode.SYSTEM_ERROR, "@RequireRole 未声明任何角色");
        }
        int min = Integer.MAX_VALUE;
        for (String role : roles) {
            min = Math.min(min, roleLevelOf(role));
        }
        return min;
    }

    /**
     * 角色码转等级：与 {@code LoginUser#roleLevel()} 同口径（契约 4.1：USER=1、STATION_ADMIN=2、PLATFORM_ADMIN=3）。
     *
     * <p>这里是注解侧的最低门槛查表，无法复用 LoginUser 的实例方法，故在本类内维护；
     * 未登记的角色码属配置错误，抛 5000 显式暴露，不作为「未知即最高权限」或「未知即放行」处理。
     */
    private static int roleLevelOf(String role) {
        String code = role == null ? "" : role.trim();
        return switch (code) {
            case Constants.ROLE_USER -> LEVEL_USER;
            case Constants.ROLE_STATION_ADMIN -> LEVEL_STATION_ADMIN;
            case Constants.ROLE_PLATFORM_ADMIN -> LEVEL_PLATFORM_ADMIN;
            default -> throw new BizException(ErrorCode.SYSTEM_ERROR, "未登记的角色声明：" + role);
        };
    }
}
