package com.sems.module.user.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户管理列表/详情出参（见《系统设计说明书》5.1 {@code GET /api/v1/users} 与前端用户管理页）。
 *
 * <p>在 {@link UserInfoVo} 之外补充管理视角字段：账号状态、角色明细（多对多，含角色名）与
 * 站点名称；该接口仅限平台管理员访问，因此返回完整登录手机号用于账号管理（9.5 的管理端例外），
 * 密码绝不出参。
 */
@Data
public class UserDetailVo {

    /** 用户 ID */
    private Long id;

    /** 完整登录手机号（仅平台管理员用户管理接口返回） */
    private String phone;

    /** 昵称 */
    private String nickname;

    /** 账号状态：ENABLED / DISABLED */
    private String status;

    /** 绑定站点 ID：仅站点管理员非空 */
    private Long stationId;

    /** 绑定站点名称（站点已删除等异常情形可能为空） */
    private String stationName;

    /** 角色明细列表（全量授予结果，前端按 roleCode 渲染标签） */
    private List<RoleVo> roles;

    /** 是否须强制改密 */
    private boolean mustChangePassword;

    /** 最后登录时间（从未登录为空） */
    private LocalDateTime lastLoginAt;

    /** 创建时间（注册/建号时间） */
    private LocalDateTime createdAt;
}
