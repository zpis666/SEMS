package com.sems.security;

import com.sems.common.Constants;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录态主体：JWT 载荷在服务端的对象表示（见《系统设计说明书》7.1）。
 *
 * <p>一次请求内，认证结果由 {@link AuthContext}（ThreadLocal）承载，业务代码通过
 * {@code AuthContext.require()} 取用，避免身份信息在 Controller/Service 间逐层透传；
 * 请求结束由 {@link AuthInterceptor#afterCompletion} 清理。
 *
 * <p>字段与令牌声明一一对应：{@code sub=userId}、{@code phone}、{@code nickname}、
 * {@code role}、{@code stationId}（站点管理员专属，见设计书 4.2）、{@code tokenVersion}、
 * {@code jti} 与 {@code exp}（落为 {@link #expiresAt}）。
 *
 * <p>{@link #mustChangePassword} 是唯一例外：它<b>不写入令牌</b>。理由是「重置密码/预置账号」
 * 可在令牌有效期内改变该标记，若固化进 2 小时的令牌，管理员重置后用户仍能拿旧令牌绕过强制改密；
 * 因此由 {@link AuthInterceptor} 每次请求从账号记录（或 60s 读缓存）回填。
 *
 * <p>本类仅承载数据，不承担权限判断；角色是否满足要求由 {@link RequireRole} 与
 * 鉴权拦截器按 {@link #roleLevel()} 的等级制判定。
 */
@Data
public class LoginUser {

    /** 用户 ID（JWT subject） */
    private Long userId;

    /** 登录手机号（注册与登录名；对外展示须脱敏） */
    private String phone;

    /** 昵称 */
    private String nickname;

    /** 角色码：{@link Constants#ROLE_USER} / {@link Constants#ROLE_STATION_ADMIN} / {@link Constants#ROLE_PLATFORM_ADMIN} */
    private String role;

    /** 绑定站点 ID：仅站点管理员非空；令牌签发后即固定，调岗会因 token_version 自增而作废 */
    private Long stationId;

    /** 令牌唯一标识：登出/续签时写入 {@code auth:deny:{jti}} 黑名单的键 */
    private String jti;

    /** 令牌版本：与 {@code sys_user.token_version} 比对，实现「禁用/改密/重置/调岗即全量吊销」 */
    private Long tokenVersion;

    /** 令牌过期时间（解析自 exp，服务端时区的本地时间） */
    private LocalDateTime expiresAt;

    /** 是否须强制修改初始密码（拦截器回填，见类头说明） */
    private boolean mustChangePassword;

    /**
     * 角色等级（契约 4.4「等级制鉴权」的基准）：{@code USER=1 < STATION_ADMIN=2 < PLATFORM_ADMIN=3}。
     *
     * <p>放行规则为「实际等级 ≥ 注解要求的最小等级」，因此 {@code @RequireRole("USER")}
     * 三角色皆可过、{@code @RequireRole("STATION_ADMIN")} 站点/平台管理员可过、
     * {@code @RequireRole("PLATFORM_ADMIN")} 仅平台管理员可过。
     *
     * @return 未知或空角色返回 0（低于任何要求，一律拒绝，fail-closed）
     */
    public int roleLevel() {
        if (role == null) {
            return 0;
        }
        return switch (role) {
            case Constants.ROLE_USER -> 1;
            case Constants.ROLE_STATION_ADMIN -> 2;
            case Constants.ROLE_PLATFORM_ADMIN -> 3;
            default -> 0;
        };
    }
}
