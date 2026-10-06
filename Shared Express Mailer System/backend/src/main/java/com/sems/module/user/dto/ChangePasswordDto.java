package com.sems.module.user.dto;

import com.sems.common.Constants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 修改密码入参（见《系统设计说明书》5.1 {@code POST /api/v1/users/me/password}）。
 *
 * <p>校验旧密码后写入新密码的 BCrypt 摘要，并清除 {@code must_change_password}；
 * 成功后 {@code token_version} 自增、全部旧令牌失效（7.1），须重新登录。
 * 新密码强度遵循 {@link Constants#PASSWORD_REGEX}（9.2）。
 */
@Data
public class ChangePasswordDto {

    /** 当前密码（用于身份二次确认，校验失败抛 3001） */
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    /** 新密码（≥8 位且含两类字符） */
    @NotBlank(message = "新密码不能为空")
    @Pattern(regexp = Constants.PASSWORD_REGEX, message = "新密码至少 8 位且包含字母与数字/符号两类字符")
    private String newPassword;
}
