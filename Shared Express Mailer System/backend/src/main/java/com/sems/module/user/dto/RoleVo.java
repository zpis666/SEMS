package com.sems.module.user.dto;

import lombok.Data;

/**
 * 角色出参（见《系统设计说明书》5.1 用户查询出参需含角色列表、4.1 角色定义）。
 *
 * <p>对应 {@code sys_role} 的三行预置数据：{@code roleCode} 是接口与前端使用的稳定码
 * （USER / STATION_ADMIN / PLATFORM_ADMIN），{@code roleName} 为中文展示名。
 */
@Data
public class RoleVo {

    /** 角色 ID（sys_role 主键） */
    private Long id;

    /** 角色码：USER / STATION_ADMIN / PLATFORM_ADMIN */
    private String roleCode;

    /** 角色名称（普通用户/站点管理员/平台管理员） */
    private String roleName;

    /** 角色说明 */
    private String description;
}
