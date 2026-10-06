package com.sems.module.auth.dto;

import com.sems.common.Constants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册入参（见《系统设计说明书》5.1「注册（手机号 + 密码 + 图形验证码 + 昵称）」，
 * 字段级校验口径对齐 10.4「表单校验设计」的后端权威规则）。
 *
 * <p>校验分两层：格式类校验由本类 JSR-380 注解完成（失败经全局处理器返回 3001 字段明细）；
 * 验证码正确性（一次性消费）与手机号唯一性在 AuthService 内完成。
 *
 * <p>为什么不接收角色/站点：自助注册只能开通普通用户（USER）角色；
 * 站点管理员/平台管理员一律由平台管理员在用户管理中创建（设计书 4.2「账号与角色开通路径」）。
 */
@Data
public class RegisterDto {

    /** 登录手机号：11 位大陆手机号（设计书 10.4），唯一性由 sys_user 唯一键与 Service 校验兜底。 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = Constants.PHONE_REGEX, message = "手机号格式不正确")
    private String phone;

    /** 登录密码：至少 8 位且含字母与（数字或符号）两类字符（设计书 9.2），落库前 BCrypt 加密。 */
    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = Constants.PASSWORD_REGEX, message = "密码至少 8 位，且需包含字母与数字（或符号）两类字符")
    private String password;

    /** 昵称：非空；长度上限对齐库表 {@code sys_user.nickname varchar(32)}。 */
    @NotBlank(message = "昵称不能为空")
    @Size(max = 32, message = "昵称长度不能超过 32 个字符")
    private String nickname;

    /** 图形验证码 ID（取自 {@code GET /api/v1/auth/captcha}，一次性，TTL 3 分钟，见设计书 3.5）。 */
    @NotBlank(message = "请输入图形验证码")
    private String captchaId;

    /** 图形验证码字符：服务端一次性消费校验（无论对错都作废，见 CaptchaService）。 */
    @NotBlank(message = "请输入图形验证码")
    private String captchaCode;
}
