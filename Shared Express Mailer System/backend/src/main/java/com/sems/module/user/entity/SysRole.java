package com.sems.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色实体，对应表 {@code sys_role}（表概要与设计出处见《系统设计说明书》8.2；
 * 字段级定义见《数据库设计说明书》V1.1 第 4 章，与 {@code deploy/sql/V1__init_schema.sql} 逐列一致）。
 *
 * <p>三行预置（USER/STATION_ADMIN/PLATFORM_ADMIN），权限点不落库、由 {@code @RequireRole} 注解承载（见设计书 4.2），
 * 故本表只承载角色码、名称与说明；{@code role_code} 与 {@code Constants.ROLE_*} 常量逐字一致。
 *
 * <p>码值字段用 {@code String} 而非枚举：与 DDL 的 {@code varchar} 码值一致，业务层按常量转换。
 */
@Data
@TableName("sys_role")
public class SysRole {

    /** 角色ID（自增主键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色码：USER / STATION_ADMIN / PLATFORM_ADMIN（唯一） */
    private String roleCode;

    /** 角色名称（普通用户/站点管理员/平台管理员） */
    private String roleName;

    /** 角色说明 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（库内 ON UPDATE 自动刷新） */
    private LocalDateTime updatedAt;
}
