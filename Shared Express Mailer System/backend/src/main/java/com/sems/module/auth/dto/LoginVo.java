package com.sems.module.auth.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录/续签出参（见《系统设计说明书》5.1 与基础层契约第 12 节的前端契约）：
 * {@code data.token / data.expiresAt / data.user{...}}。
 *
 * <p>续签（{@code POST /api/v1/auth/refresh}）复用本结构：换发新 token 后返回同样三件套，
 * 前端会话 Store 据此无感替换（见设计书 7.1「静默续签」）。
 */
@Data
public class LoginVo {

    /** 访问令牌（JWT，请求时以 {@code Authorization: Bearer} 头携带）。 */
    private String token;

    /** 令牌过期时间（服务端时区；前端据此调度静默续签，剩余 <30min 触发，见设计书 3.5）。 */
    private LocalDateTime expiresAt;

    /** 登录用户信息（手机号已掩码）。 */
    private LoginUserVo user;
}
