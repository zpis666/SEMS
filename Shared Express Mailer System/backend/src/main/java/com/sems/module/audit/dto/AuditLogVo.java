package com.sems.module.audit.dto;

import com.sems.common.util.MaskUtils;
import com.sems.module.audit.entity.AuditLogEntity;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计日志列表项（{@code GET /api/v1/audit-logs} 的 records 元素，见《系统设计说明书》5.6）。
 *
 * <p><b>隐私口径（设计书 7.6 与 9.5）</b>：本 VO 只携带掩码后的 IP（{@code 192.168.*.*}），
 * <b>不携带完整 IP</b>——列表是分页批量返回，若顺带回完整值等于把登录/操作 IP 全量暴露给任何
 * 拿到列表权限的调用方。完整 IP 必须走独立端点 {@code GET /api/v1/audit-logs/{id}/ip} 单独查看，
 * 且该查看行为本身会被记入审计（见 AuditQueryService 的模块注释）。
 *
 * <p>{@code paramsMasked} 是审计切面在落库前已脱敏的入参快照（password/phone 等字段替换为 ***），
 * 本 VO 只做透传，不得在查询侧二次加工。
 * {@code operatorRole} 是操作发生时的角色快照（非当前角色），用于事后追责时还原权限上下文。
 */
@Data
public class AuditLogVo {

    /** 记录 ID：前端「查看完整 IP」按钮据此调用 /audit-logs/{id}/ip */
    private Long id;

    /** 记录写入时间（审计列表按此倒序） */
    private LocalDateTime createdAt;

    /** 操作人 ID（0=匿名或未知） */
    private Long operatorId;

    /** 操作人角色快照（空=匿名） */
    private String operatorRole;

    /** 业务模块（如：快递袋管理） */
    private String module;

    /** 业务动作（如：报废、状态修正、回收确认-直投） */
    private String action;

    /** 请求 URI（如 /api/v1/bags） */
    private String requestUri;

    /** 结果：1成功/0失败 */
    private Integer result;

    /** 错误码（0=成功，见设计书 6.4 错误码表） */
    private Integer errorCode;

    /** 处理耗时（毫秒） */
    private Integer costMs;

    /** 掩码后的客户端 IP（如 192.168.*.*）；完整值只能经 /audit-logs/{id}/ip 查看且会被记审计 */
    private String ipMasked;

    /** 脱敏后的请求入参快照（JSON 文本） */
    private String paramsMasked;

    /**
     * 实体 → 列表 VO。IP 在此处统一掩码，使「列表一律掩码」的口径不可能在某个查询分支被遗漏。
     */
    public static AuditLogVo from(AuditLogEntity entity) {
        AuditLogVo vo = new AuditLogVo();
        vo.setId(entity.getId());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperatorRole(entity.getOperatorRole());
        vo.setModule(entity.getModule());
        vo.setAction(entity.getAction());
        vo.setRequestUri(entity.getRequestUri());
        vo.setResult(entity.getResult());
        vo.setErrorCode(entity.getErrorCode());
        vo.setCostMs(entity.getCostMs());
        // 掩码而非省略：管理员仍需判断来源网段（设计书 7.6「列表默认部分掩码」）
        vo.setIpMasked(MaskUtils.maskIp(entity.getIp()));
        vo.setParamsMasked(entity.getParamsMasked());
        return vo;
    }
}
