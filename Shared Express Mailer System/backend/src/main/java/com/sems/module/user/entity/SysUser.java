package com.sems.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户账号实体，对应表 {@code sys_user}（表概要与设计出处见《系统设计说明书》8.2；
 * 字段级定义见《数据库设计说明书》V1.1 第 4 章，与 {@code deploy/sql/V1__init_schema.sql} 逐列一致）。
 *
 * <p>三类角色（普通用户/站点管理员/平台管理员）共用本表：角色不落本表，经 {@code sys_role} 与
 * {@code sys_user_role} 关联表达（见设计书 4.1）；{@code station_id} 仅站点管理员非空。
 * {@code password} 只存 BCrypt 摘要；{@code phone} 存明文（登录业务必需）但出参一律脱敏（见设计书 9.5）。
 * {@code token_version} 自增即全量吊销该账号已签发的全部令牌（见设计书 7.1 认证设计）。
 *
 * <p>状态字段用 {@code String} 而非枚举：与 DDL 的 {@code varchar} 码值一致（ENABLED/DISABLED），
 * 业务层用 {@code Constants.STATUS_ENABLED} 等常量比对，避免 ORM 枚举映射与库内码值两份口径。
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 用户ID（自增主键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录手机号（唯一；出参脱敏为 138****1234） */
    private String phone;

    /** 密码 BCrypt 摘要（cost≥10，禁止存明文） */
    private String password;

    /** 昵称（注册必填） */
    private String nickname;

    /** 账号状态：ENABLED 启用 / DISABLED 禁用 */
    private String status;

    /** 绑定站点ID，仅站点管理员非空 */
    private Long stationId;

    /** 令牌版本，自增即全量吊销该用户所有令牌 */
    private Integer tokenVersion;

    /** 是否须强制改密：1 是（预置/重置账号首登必须改密）/ 0 否 */
    private Integer mustChangePassword;

    /** 最后登录时间 */
    private LocalDateTime lastLoginAt;

    /** 最后登录 IP（兼容 IPv6） */
    private String lastLoginIp;

    /** 创建时间（注册时间） */
    private LocalDateTime createdAt;

    /** 更新时间（库内 ON UPDATE 自动刷新，业务侧只读） */
    private LocalDateTime updatedAt;
}
