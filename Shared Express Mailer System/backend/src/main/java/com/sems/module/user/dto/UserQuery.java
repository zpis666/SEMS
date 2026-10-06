package com.sems.module.user.dto;

import lombok.Data;

/**
 * 用户分页查询入参（见《系统设计说明书》5.1「分页查询用户（支持按手机号/角色/状态筛选）」与 6.1 查询参数约定）。
 *
 * <p>取值口径：{@code pageNum} 默认 1；{@code pageSize} 默认 10、上限 100（超出由 Service 收敛到上限）；
 * {@code phone} 为模糊匹配片段（前端传明文号码，出参才脱敏）；{@code role} 传角色码
 * （{@code USER / STATION_ADMIN / PLATFORM_ADMIN}），匹配“拥有该角色”的用户；{@code status} 传
 * {@code ENABLED / DISABLED}；{@code orderBy} 形如 {@code field,asc|desc}，字段走 Service 层白名单，
 * 拒绝任意列名（见 9.4 注入防护）。
 */
@Data
public class UserQuery {

    /** 页码，从 1 起；缺省 1 */
    private Integer pageNum = 1;

    /** 每页条数；缺省 10，最大 100 */
    private Integer pageSize = 10;

    /** 手机号片段，模糊匹配 */
    private String phone;

    /** 角色码筛选：匹配“拥有该角色”的用户（经 sys_user_role 关联查询） */
    private String role;

    /** 账号状态筛选：ENABLED / DISABLED */
    private String status;

    /** 排序表达式 {@code field,asc|desc}；字段白名单见 UserService（id/createdAt/lastLoginAt） */
    private String orderBy;
}
