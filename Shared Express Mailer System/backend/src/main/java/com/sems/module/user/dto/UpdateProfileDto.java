package com.sems.module.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改个人信息入参（见《系统设计说明书》5.1 {@code PATCH /api/v1/users/me}）。
 *
 * <p>只开放「昵称」这类非敏感字段：手机号是登录凭证与唯一键、角色与状态属管理动作、
 * 密码另走 {@code POST /users/me/password}，均不允许本人经本接口修改。
 */
@Data
public class UpdateProfileDto {

    /** 昵称（2–16 个字符） */
    @NotBlank(message = "昵称不能为空")
    @Size(min = 2, max = 16, message = "昵称长度应为 2–16 个字符")
    private String nickname;
}
