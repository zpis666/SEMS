package com.sems.security;

import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;

/**
 * 请求级登录态上下文（见《系统设计说明书》7.1「拦截链」）。
 *
 * <p>认证在 {@link AuthInterceptor} 中一次完成，但身份信息在 Controller/Service/状态机中
 * 散落使用，故用 ThreadLocal 承载，避免把 {@link LoginUser} 逐层透传。
 *
 * <p><b>清理纪律</b>：Tomcat 线程是复用的，请求结束若不清除，下一个请求（同一线程）会读到
 * 上一个请求的身份——这是最典型的越权事故。因此 {@link AuthInterceptor#afterCompletion}
 * 必须调用 {@link #clear()}，且 {@code clear()} 用 {@link ThreadLocal#remove()} 而非
 * {@code set(null)}（remove 才能真正断开强引用、避免线程池场景下的内存滞留）。
 *
 * <p>取用侧约定：业务代码拿不到身份时应当 fail-closed（抛 1001），
 * 故 {@link #require()} / {@link #userId()} 等方法在未认证时直接抛异常。
 */
public final class AuthContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    /** 工具类禁止实例化。 */
    private AuthContext() {
    }

    /** 认证通过后写入当前线程身份（仅拦截器调用）。 */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** 当前登录用户；未认证（或已清理）返回 {@code null}，供容错分支使用。 */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /**
     * 取当前登录用户；缺失即抛 1001（fail-closed）。
     *
     * <p>正常情况下请求能进入业务代码就说明拦截器已放行；此处兜底覆盖
     * 「异步线程未继承上下文」「@CurrentUser 用在未认证路径」等异常情形。
     */
    public static LoginUser require() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        return user;
    }

    /** 当前用户 ID。 */
    public static Long userId() {
        return require().getUserId();
    }

    /** 当前用户角色码。 */
    public static String role() {
        return require().getRole();
    }

    /** 当前用户绑定站点 ID；非站点角色为 {@code null}，调用方须自行判空（「本站」校验见契约 10）。 */
    public static Long stationId() {
        return require().getStationId();
    }

    /**
     * 是否平台管理员。
     *
     * <p>作为布尔判断使用，未认证时返回 {@code false} 而不是抛异常：
     * 调用方（如平台看板）只需「不是平台管理员就别看」，无需关心是未登录还是角色不符；
     * 需要区分 1001/2001 的场景请改用 {@link #require()}。
     */
    public static boolean isPlatformAdmin() {
        LoginUser user = HOLDER.get();
        return user != null && Constants.ROLE_PLATFORM_ADMIN.equals(user.getRole());
    }

    /** 请求结束清理当前线程身份（拦截器 afterCompletion 调用，见类头「清理纪律」）。 */
    public static void clear() {
        HOLDER.remove();
    }
}
