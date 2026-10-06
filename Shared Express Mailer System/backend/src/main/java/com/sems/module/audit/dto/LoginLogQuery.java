package com.sems.module.audit.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 登录日志分页查询入参（{@code GET /api/v1/login-logs}，见《系统设计说明书》5.6）。
 *
 * <p>筛选口径：结果 {@code result}（1成功/0失败）、手机号掩码 {@code phoneMask}、
 * 时间区间 {@code beginTime/endTime}，全部可选，多个条件为「与」关系；
 * 固定按登录时间倒序（失败排查最先关注最近的尝试）。
 *
 * <p><b>手机号筛选的两点约定</b>：
 * <ol>
 *   <li>{@code login_log} 只留手机号掩码（设计书 7.6/9.5），因此筛选值会先归一到掩码形态再精确匹配——
 *       管理员输入完整手机号 {@code 13800001234} 时后端自动掩码为 {@code 138****1234} 再查询，
 *       避免"输入完整号永远查不到"的假空结果；直接输入掩码值则原样匹配；</li>
 *   <li>{@code phone} 是前端页面（LoginLog.vue）已在使用的参数名，作为 {@code phoneMask} 的
 *       兼容别名保留；两者同时出现时以 {@code phoneMask} 为准。新代码请用正式名 {@code phoneMask}。</li>
 * </ol>
 *
 * <p>时间参数：主格式 {@code yyyy-MM-dd HH:mm:ss}（前端日期控件 value-format），
 * 兼容 ISO-8601 与纯日期写法；区间为闭区间，见 {@link AuditLogQuery} 同一说明。
 * 分页口径同 {@link AuditLogQuery}（默认 1/10，越界由 Service 归一化，pageSize 上限 100）。
 */
@Data
public class LoginLogQuery {

    /** 页码（默认 1） */
    private int pageNum = 1;

    /** 每页条数（默认 10，最大 100） */
    private int pageSize = 10;

    /** 登录结果：1成功/0失败；null 表示不按结果筛选 */
    private Integer result;

    /** 手机号（掩码精确匹配；输入完整手机号时由 Service 自动掩码后匹配） */
    private String phoneMask;

    /** 手机号筛选的兼容别名（前端既有参数名 phone）；与 phoneMask 同时出现时以 phoneMask 为准 */
    private String phone;

    /** 起始时间（含），格式 yyyy-MM-dd HH:mm:ss */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss",
            fallbackPatterns = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"})
    private LocalDateTime beginTime;

    /** 结束时间（含），格式 yyyy-MM-dd HH:mm:ss */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss",
            fallbackPatterns = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"})
    private LocalDateTime endTime;
}
