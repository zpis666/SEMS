package com.sems.module.bag.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 消毒批次分页查询入参（{@code GET /api/v1/disinfections}，见《系统设计说明书》5.4）。
 *
 * <p>分页口径遵循契约 10：{@code pageNum} 默认 1、{@code pageSize} 默认 10（最大 100）、
 * 排序字段走白名单；本站站点管理员强制限本站，数据权限在 Service 层落实（见 4.4）。
 *
 * <p>时间筛选按 {@code created_at}（登记时间）过滤，与建表脚本的
 * {@code idx_dr_station_time(station_id, created_at)} 索引口径对齐。
 */
@Data
public class DisinfectQuery {

    /** 页码（默认 1） */
    private Integer pageNum = 1;

    /** 每页条数（默认 10，最大 100） */
    private Integer pageSize = 10;

    /** 站点筛选：平台管理员可任选；站点管理员只能等于本人站点（传他站报 2002） */
    private Long stationId;

    /** 批次结果筛选：合格 / 部分不合格 / 不合格（其余取值报 3001） */
    private String result;

    /** 登记时间起（含），格式 yyyy-MM-dd HH:mm:ss */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    /** 登记时间止（含），格式 yyyy-MM-dd HH:mm:ss */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /** 排序字段白名单：startTime / endTime / createdAt（缺省按 createdAt 倒序） */
    private String orderBy;
}
