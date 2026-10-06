package com.sems.module.auth.dto;

import com.sems.common.Constants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 登录入参（见《系统设计说明书》5.1 登录、9.2「认证与会话安全」）。
 *
 * <p>图形验证码两个字段为<b>可选</b>：常规登录只校验手机号与密码；
 * 只有当账号曾被锁定（{@code login:locked-once:{phone}} 记忆存在）时，
 * 服务层才强制要求同时携带 captchaId 与 captchaCode 并一次性校验
 * （机制说明见 {@code AuthService} 类头），故本类对这两个字段不加 {@code @NotBlank}。
 */
@Data
public class LoginDto {

    /** 登录手机号：11 位大陆手机号（设计书 10.4）。 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = Constants.PHONE_REGEX, message = "手机号格式不正确")
    private String phone;

    /** 登录密码：只做非空校验——登录侧不复用注册的密文规则，避免口令策略随错误提示外泄。 */
    @NotBlank(message = "密码不能为空")
    private String password;

    /** 图形验证码 ID：账号处于「锁定期满后首次登录」时必须携带。 */
    private String captchaId;

    /** 图形验证码字符：同上，须与 captchaId 成对携带。 */
    private String captchaCode;
}
