package com.sems.module.auth.dto;

import lombok.Data;

/**
 * 登录用户信息出参（字段与前端契约逐字对齐：登录响应
 * {@code data.user{ id, phone(mask), nickname, role, stationId, mustChangePassword }}，
 * 见《系统设计说明书》5.1 与基础层契约第 12 节）。
 *
 * <p>手机号一律经 {@code MaskUtils.maskPhone} 脱敏后返回（设计书 9.5 数据安全与脱敏）。
 */
@Data
public class LoginUserVo {

    /** 用户 ID。 */
    private Long id;

    /** 手机号（已掩码，如 138****1234）。 */
    private String phone;

    /** 昵称。 */
    private String nickname;

    /** 角色码：USER / STATION_ADMIN / PLATFORM_ADMIN（见设计书 4.1）。 */
    private String role;

    /** 绑定站点 ID：仅站点管理员非空（设计书 4.1/4.2）。 */
    private Long stationId;

    /** 是否须强制修改初始密码（true 时前端跳改密页；拦截器同步限行，见设计书 4.2）。 */
    private boolean mustChangePassword;
}
