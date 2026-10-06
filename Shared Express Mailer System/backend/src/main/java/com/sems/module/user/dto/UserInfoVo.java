package com.sems.module.user.dto;

import lombok.Data;

/**
 * 本人信息出参（见《系统设计说明书》5.1 {@code GET /users/me}、10 前端契约的登录返回口径）。
 *
 * <p>字段与登录响应中的 {@code data.user} 完全一致（契约模块版 8.1）：
 * 前端登录后取 {@code id/phone(掩码)/nickname/role/stationId/mustChangePassword}，
 * 刷新页面时用本 VO 从 {@code GET /users/me} 回填会话。手机号一律经
 * {@code MaskUtils.maskPhone} 脱敏（9.5）；密码摘要绝不出参。
 */
@Data
public class UserInfoVo {

    /** 用户 ID */
    private Long id;

    /** 手机号（掩码展示，如 138****1234） */
    private String phone;

    /** 昵称 */
    private String nickname;

    /** 角色码：多角色时取最高等级角色（与 JWT 单角色快照口径一致，见 4.3） */
    private String role;

    /** 绑定站点 ID：仅站点管理员非空 */
    private Long stationId;

    /** 是否须强制改密（预置/被重置账号为 true，改密后转 false；4.2） */
    private boolean mustChangePassword;
}
