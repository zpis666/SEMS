package com.sems.module.auth.controller;

import com.sems.aspect.AuditLog;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.common.Result;
import com.sems.common.util.MaskUtils;
import com.sems.module.auth.dto.CaptchaVo;
import com.sems.module.auth.dto.LoginDto;
import com.sems.module.auth.dto.LoginVo;
import com.sems.module.auth.dto.RegisterDto;
import com.sems.module.auth.service.AuthService;
import com.sems.module.auth.service.CaptchaService;
import com.sems.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（M1 用户管理模块的认证部分）。
 *
 * <p>端点、路径与权限逐条对齐《系统设计说明书》5.1：
 * <pre>
 * GET  /api/v1/auth/captcha   匿名      获取图形验证码（一次性，TTL 3 分钟）
 * POST /api/v1/auth/register  匿名      注册（需图形验证码）
 * POST /api/v1/auth/login     匿名      登录，返回 token 与角色；账号锁定返回 1002
 * POST /api/v1/auth/refresh   登录用户  静默续签（旧 jti 入黑名单，签发新 token）
 * POST /api/v1/auth/logout    登录用户  退出（当前 jti 入黑名单）
 * </pre>
 *
 * <p><b>幂等口径</b>（设计书 6.1/9.6）：captcha/register/login 是匿名写接口，
 * 不启用接口级幂等——注册由「一次性验证码 + 手机号唯一键」防护、登录由失败锁定防护；
 * refresh 与 logout 属登录后写接口，按 6.1「登录后全部写接口启用」必须携带 UUID 格式的
 * {@code clientRequestId} 请求头（缺失/非法 → 3001），但不做结果回放——两者的语义是
 * 「让旧凭据失效 / 换发新凭据」，重复执行各自安全（再拉黑一次 jti / 再轮换一次），
 * 没有需要幂等消除的重复副作用（对比：建单类接口重复执行会重复扣减库存）。
 *
 * <p><b>免认证白名单</b>：captcha/register/login 在 AuthInterceptor 白名单中；
 * refresh/logout 需携带 {@code Authorization: Bearer}，且属于强制改密期间的四个放行路径之二
 * （契约 4.4）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** User-Agent 请求头名。 */
    private static final String HEADER_USER_AGENT = "User-Agent";

    /** 反向代理来源地址请求头（Nginx 反代场景，见设计书 11.1）。 */
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    /** login_log.ip 列宽 45：按「列宽 − 1」调用 MaskUtils.truncate（省略号另计）。 */
    private static final int IP_COLUMN_MAX = 44;

    /** login_log.ua 列宽 255：同上。 */
    private static final int UA_COLUMN_MAX = 254;

    private final AuthService authService;
    private final CaptchaService captchaService;

    public AuthController(AuthService authService, CaptchaService captchaService) {
        this.authService = authService;
        this.captchaService = captchaService;
    }

    /**
     * 获取图形验证码（匿名）。
     *
     * @return data = {@code { captchaId, imageBase64 }}，imageBase64 不含 data: 前缀
     */
    @GetMapping("/captcha")
    public Result<CaptchaVo> captcha() {
        return Result.ok(captchaService.generate());
    }

    /**
     * 注册（匿名，需图形验证码）：成功后使用新账号登录，本接口不自动签发令牌。
     *
     * <p>不启用接口级幂等：详见类头「幂等口径」。
     *
     * @param dto 注册入参（手机号/密码/昵称/验证码，JSR-380 校验）
     */
    @PostMapping("/register")
    @AuditLog(module = "用户管理", action = "注册")
    public Result<Void> register(@Valid @RequestBody RegisterDto dto) {
        authService.register(dto);
        return Result.ok();
    }

    /**
     * 登录（匿名）：返回 token、expiresAt 与用户信息；账号锁定返回 1002，
     * 需要图形验证码时返回 3001 且 message 含「验证码」（前端据此展开验证码输入行）。
     *
     * <p>不启用接口级幂等：详见类头「幂等口径」。
     *
     * @param dto     登录入参（验证码字段可选，由服务层按锁定状态判定是否必填）
     * @param request 用于采集 login_log 所需客户端 IP 与 UA
     */
    @PostMapping("/login")
    @AuditLog(module = "用户管理", action = "登录")
    public Result<LoginVo> login(@Valid @RequestBody LoginDto dto, HttpServletRequest request) {
        String clientIp = MaskUtils.truncate(resolveClientIp(request), IP_COLUMN_MAX);
        String userAgent = MaskUtils.truncate(request.getHeader(HEADER_USER_AGENT), UA_COLUMN_MAX);
        return Result.ok(authService.login(dto, clientIp, userAgent));
    }

    /**
     * 静默续签（登录用户）：旧 jti 入黑名单，换发新 token，返回与登录相同的结构。
     *
     * <p>幂等头：按 6.1/9.6 登录后写接口必须携带合法 {@code clientRequestId}（缺失/非法 → 3001）；
     * 不做结果回放的理由见类头「幂等口径」。
     */
    @PostMapping("/refresh")
    @RequireRole(Constants.ROLE_USER)
    public Result<LoginVo> refresh(HttpServletRequest request,
                                   @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                   String clientRequestId) {
        requireClientRequestId(clientRequestId);
        return Result.ok(authService.refresh(resolveBearerToken(request)));
    }

    /**
     * 退出登录（登录用户）：当前 jti 写入 Redis 黑名单（TTL = 令牌剩余有效期）。
     *
     * <p>幂等头：按 6.1/9.6 登录后写接口必须携带合法 {@code clientRequestId}（缺失/非法 → 3001）；
     * 不做结果回放的理由见类头「幂等口径」。
     */
    @PostMapping("/logout")
    @RequireRole(Constants.ROLE_USER)
    @AuditLog(module = "用户管理", action = "登出")
    public Result<Void> logout(HttpServletRequest request,
                               @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                               String clientRequestId) {
        requireClientRequestId(clientRequestId);
        authService.logout(resolveBearerToken(request));
        return Result.ok();
    }

    /**
     * 幂等请求头校验（设计书 6.1/9.6：登录后全部写接口启用；缺失或非 UUID 格式 → 3001）。
     * 文案与其余写接口（如 UserController）保持同一口径，便于接口测试统一断言。
     */
    private static String requireClientRequestId(String clientRequestId) {
        if (clientRequestId == null || !clientRequestId.matches(Constants.UUID_REGEX)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少或非法的 clientRequestId 请求头");
        }
        return clientRequestId;
    }

    /**
     * 提取 Bearer 令牌：能进入 refresh/logout 说明 AuthInterceptor 已校验通过，
     * 此处的兜底只为防御拦截器装配被改动等异常路径——缺失即 1001（fail-closed）。
     */
    private static String resolveBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(Constants.AUTH_HEADER);
        if (authorization == null || !authorization.startsWith(Constants.BEARER_PREFIX)) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        String token = authorization.substring(Constants.BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        return token;
    }

    /**
     * 解析客户端 IP：反代场景下 remoteAddr 是代理地址，真实来源取
     * {@code X-Forwarded-For} 首个地址（与 login_log.ip 注释口径一致）；
     * 直连或头缺失时回退 {@code remoteAddr}。
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(HEADER_X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",", 2)[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "" : remote;
    }
}
