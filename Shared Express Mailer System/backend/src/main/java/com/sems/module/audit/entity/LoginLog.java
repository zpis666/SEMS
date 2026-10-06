package com.sems.module.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录日志实体，映射表 {@code login_log}
 * （字段逐列对应 deploy/sql/V1__init_schema.sql）。
 *
 * <p>登录成功与失败均写本表（见《系统设计说明书》7.6 与 9.2 登录防护）：
 * 失败原因是处置线索（密码错/不存在/锁定/禁用/验证码），手机号以掩码形式留存，
 * 避免日志本身成为敏感数据泄露面（11.1 数据分级）。
 *
 * <p>防篡改约定（7.6）：本表只插入；与 audit_log 一同纳入 ≥180 天保留与归档清理范围。
 */
@Data
@TableName("login_log")
public class LoginLog {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID（0=账号不存在） */
    private Long userId;

    /** 手机号掩码（如 138****1234，列宽 20） */
    private String phoneMask;

    /** 登录结果：1成功/0失败 */
    private Integer result;

    /** 失败原因（密码错/不存在/锁定/禁用/验证码；成功为空串，列宽 20） */
    private String failReason;

    /** 客户端IP（IPv4/IPv6；反代场景取 X-Forwarded-For 首个地址，列宽 45） */
    private String ip;

    /** 客户端 User-Agent（超长截断，列宽 255） */
    private String ua;

    /** 登录时间（库端默认值，应用不赋值） */
    private LocalDateTime createdAt;
}
