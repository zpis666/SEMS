package com.sems.module.audit.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 操作审计日志分页查询入参（{@code GET /api/v1/audit-logs}，见《系统设计说明书》5.6）。
 *
 * <p>筛选口径与设计书 5.6 逐条对应：按操作人 {@code operatorId}、模块 {@code module}、
 * 动作 {@code action}、时间区间 {@code beginTime/endTime} 精确筛选，本实现额外支持
 * 结果 {@code result}（1成功/0失败）——回收确认按直投/跨站写不同 action，
 * 「按动作筛出审计重点核查项」依赖 action 的精确匹配，见 5.6 与 7.6。
 * 全部条件均可选，多个条件之间为「与」关系；结果固定按时间倒序（最近发生的审计优先展示）。
 *
 * <p>时间参数：前端日期控件 value-format 为 {@code YYYY-MM-DD HH:mm:ss}（与全站线上格式一致，
 * 见 application.yml 的 Jackson date-format），故主格式取 {@code yyyy-MM-dd HH:mm:ss}，
 * 并兼容 ISO-8601（{@code yyyy-MM-dd'T'HH:mm:ss}）与纯日期 {@code yyyy-MM-dd} 两种写法；
 * 区间为闭区间（含边界），纯日期写法按当天 00:00:00 解释，前端需按需补足 23:59:59。
 *
 * <p>分页口径：pageNum 默认 1、pageSize 默认 10；越界值由 Service 归一化
 * （pageNum &lt; 1 归 1；pageSize &gt; 100 截为 100），与设计书 6.1 的分页约定
 * 及基础层 AuditLogService 的处理口径保持一致。
 */
@Data
public class AuditLogQuery {

    /** 页码（默认 1） */
    private int pageNum = 1;

    /** 每页条数（默认 10，最大 100） */
    private int pageSize = 10;

    /** 操作人 ID（精确匹配；匿名/未知操作人的记录以 0 存储） */
    private Long operatorId;

    /** 业务模块（精确匹配，如：快递袋管理、审计查询） */
    private String module;

    /** 业务动作（精确匹配，如：报废、状态修正、回收确认-直投） */
    private String action;

    /** 结果：1成功/0失败；null 表示不按结果筛选 */
    private Integer result;

    /** 起始时间（含），格式 yyyy-MM-dd HH:mm:ss */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss",
            fallbackPatterns = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"})
    private LocalDateTime beginTime;

    /** 结束时间（含），格式 yyyy-MM-dd HH:mm:ss */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss",
            fallbackPatterns = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"})
    private LocalDateTime endTime;
}
