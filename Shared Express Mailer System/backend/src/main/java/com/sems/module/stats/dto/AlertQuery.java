package com.sems.module.stats.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 异常与风控告警清单查询入参（{@code GET /api/v1/alerts}，见《系统设计说明书》5.6：
 * 「按类型/时间筛选，可跳转处置」；分页口径见 6.1）。
 *
 * <p>筛选口径：{@code type} 为七类告警类型码之一（可选，空 = 全部）；
 * {@code beginTime/endTime} 为闭区间（含边界），作用于各告警的 {@code occurredAt}
 * （①②③④为事件时间、⑤⑥为指标最后更新时刻、⑦为检测时刻）。
 *
 * <p>时间参数：前端日期控件 value-format 为 {@code YYYY-MM-DD HH:mm:ss}（与全站线上格式一致，
 * 见 application.yml 的 Jackson date-format），故主格式取 {@code yyyy-MM-dd HH:mm:ss}，
 * 并兼容 ISO-8601 与纯日期写法；纯日期按当天 00:00:00 解释（与 /audit-logs 的查询入参口径一致）。
 *
 * <p>分页口径：pageNum 默认 1、pageSize 默认 10；越界值由服务层归一化
 * （pageNum &lt; 1 归 1；pageSize &gt; 100 截为 100），与设计书 6.1 及各查询接口一致。
 */
@Data
public class AlertQuery {

    /** 页码（默认 1） */
    private int pageNum = 1;

    /** 每页条数（默认 10，最大 100） */
    private int pageSize = 10;

    /** 告警类型码（七类之一；null/空 表示不按类型筛选） */
    private String type;

    /** 起始时间（含），格式 yyyy-MM-dd HH:mm:ss */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss",
            fallbackPatterns = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"})
    private LocalDateTime beginTime;

    /** 结束时间（含），格式 yyyy-MM-dd HH:mm:ss */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss",
            fallbackPatterns = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"})
    private LocalDateTime endTime;
}
