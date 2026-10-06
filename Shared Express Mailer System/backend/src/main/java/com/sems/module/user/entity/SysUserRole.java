package com.sems.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户与角色关联实体，对应表 {@code sys_user_role}（表概要与设计出处见《系统设计说明书》8.2；
 * 字段级定义见《数据库设计说明书》V1.1 第 4 章，与 {@code deploy/sql/V1__init_schema.sql} 逐列一致）。
 *
 * <p>{@code (user_id, role_id)} 唯一：防重复授予同一角色。登录时读一次角色形成 JWT 快照，
 * 运行期鉴权以快照为准（见设计书 7.1），改绑角色需靠提升 {@code sys_user.token_version} 使旧令牌失效。
 */
@Data
@TableName("sys_user_role")
public class SysUserRole {

    /** 关联ID（自增主键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 角色ID */
    private Long roleId;

    /** 授予时间 */
    private LocalDateTime createdAt;
}
