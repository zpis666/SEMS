package com.sems.module.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计日志实体，映射表 {@code audit_log}
 * （字段逐列对应 deploy/sql/V1__init_schema.sql，设计口径见《系统设计说明书》7.6）。
 *
 * <p>类名带 Entity 后缀是为了与审计注解 {@code com.sems.aspect.AuditLog} 区分：
 * 两者同名不同包，请勿互相 import，审计切面只使用本实体、注解只描述动作语义。
 *
 * <p>防篡改约定（7.6）：本表只插入、不修改、不删除，应用层不提供 update/delete 入口，
 * 归档清理由独立运维账号执行。
 *
 * <p>{@code createdAt} 不赋值：列默认 {@code CURRENT_TIMESTAMP(3)}，由库端填充；
 * 应急文件回放入库时才会显式带回原始时间。
 */
@Data
@TableName("audit_log")
public class AuditLogEntity {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人ID（0=匿名或未知） */
    private Long operatorId;

    /** 操作人角色快照（空=匿名） */
    private String operatorRole;

    /** 客户端IP（IPv4/IPv6；反代场景取 X-Forwarded-For 首个地址，列宽 45） */
    private String ip;

    /** 客户端 User-Agent（超长截断，列宽 255） */
    private String ua;

    /** 业务模块（如：快递袋管理） */
    private String module;

    /** 业务动作（如：消毒登记；回收确认按上下文区分 直投/跨站/未标记直投） */
    private String action;

    /** 请求URI（如 /api/v1/bags） */
    private String requestUri;

    /** 脱敏后入参JSON（password/phone/recipientPhone 等敏感字段替换为 ***，超长截断 4096） */
    private String paramsMasked;

    /** 结果：1成功/0失败 */
    private Integer result;

    /** 错误码（0=成功，见《系统设计说明书》6.4 错误码表） */
    private Integer errorCode;

    /** 处理耗时（毫秒） */
    private Integer costMs;

    /** 记录写入时间（库端默认值，应用不赋值） */
    private LocalDateTime createdAt;
}
