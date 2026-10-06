package com.sems.module.audit.dto;

import com.sems.common.util.MaskUtils;
import com.sems.module.audit.entity.LoginLog;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录日志列表项（{@code GET /api/v1/login-logs} 的 records 元素，见《系统设计说明书》5.6）。
 *
 * <p>隐私口径同 {@link AuditLogVo}（设计书 7.6/9.5）：列表只回掩码 IP，
 * 完整 IP 走独立端点 {@code GET /api/v1/login-logs/{id}/ip}，查看行为记审计。
 * {@code phoneMask} 在登录时即以掩码形态落库，本 VO 直接透传；
 * {@code failReason} 是账号安全排查的首要线索（密码错/不存在/锁定/禁用/验证码）。
 */
@Data
public class LoginLogVo {

    /** 记录 ID：前端「查看完整 IP」按钮据此调用 /login-logs/{id}/ip */
    private Long id;

    /** 登录时间（列表按此倒序） */
    private LocalDateTime createdAt;

    /** 用户 ID（0=账号不存在） */
    private Long userId;

    /** 手机号掩码（如 138****1234） */
    private String phoneMask;

    /** 登录结果：1成功/0失败 */
    private Integer result;

    /** 失败原因（成功记录为空串）；仅失败时有意义 */
    private String failReason;

    /** 掩码后的客户端 IP（如 192.168.*.*）；完整值只能经 /login-logs/{id}/ip 查看且会被记审计 */
    private String ipMasked;

    /** 客户端 User-Agent（用于识别异常终端，超长已按列宽截断） */
    private String ua;

    /**
     * 实体 → 列表 VO。IP 在此处统一掩码，使「列表一律掩码」的口径不可能在某个查询分支被遗漏。
     */
    public static LoginLogVo from(LoginLog entity) {
        LoginLogVo vo = new LoginLogVo();
        vo.setId(entity.getId());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUserId(entity.getUserId());
        vo.setPhoneMask(entity.getPhoneMask());
        vo.setResult(entity.getResult());
        vo.setFailReason(entity.getFailReason());
        vo.setIpMasked(MaskUtils.maskIp(entity.getIp()));
        vo.setUa(entity.getUa());
        return vo;
    }
}
