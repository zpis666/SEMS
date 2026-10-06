package com.sems.module.user.dto;

import com.sems.common.Constants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建账号入参（见《系统设计说明书》5.1 {@code POST /api/v1/users}，FR-M1-12）。
 *
 * <p>规则：手机号须满足 {@link Constants#PHONE_REGEX} 且全站唯一（唯一索引兜底）；
 * 初始密码须满足 {@link Constants#PASSWORD_REGEX}（≥8 位、含两类字符，9.2）；
 * 角色多选写入 {@code sys_user_role}，选中 {@code STATION_ADMIN} 时 {@code stationId} 必填
 * 且站点须存在（4.2「站点管理员的 stationId …… 唯一来源」）。
 */
@Data
public class CreateUserDto {

    /** 登录手机号（11 位，唯一） */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = Constants.PHONE_REGEX, message = "手机号格式不正确")
    private String phone;

    /** 初始密码（BCrypt 加密存储，明文不落库） */
    @NotBlank(message = "初始密码不能为空")
    @Pattern(regexp = Constants.PASSWORD_REGEX, message = "密码至少 8 位且包含字母与数字/符号两类字符")
    private String password;

    /** 昵称（2–16 个字符，与前端表单口径一致） */
    @NotBlank(message = "昵称不能为空")
    @Size(min = 2, max = 16, message = "昵称长度应为 2–16 个字符")
    private String nickname;

    /** 角色码列表（可多选）；含 STATION_ADMIN 时必须给 stationId */
    @NotEmpty(message = "至少选择一个角色")
    private List<String> roles;

    /** 绑定站点 ID：仅授予 STATION_ADMIN 时必填；其它角色忽略（DB 口径「仅站点管理员非空」） */
    private Long stationId;
}
