package com.sems.module.bag.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 袋流转日志回溯出参（见《系统设计说明书》5.4「GET /bags/{bagCode}/trace」与 3.2 表头）。
 *
 * <p>一行对应一次成功流转：前态/后态/事件/操作人/操作站点/代操作标记/差异单号/备注/时间，
 * 操作人昵称与站点名称由 Service 层批量联查补全（展示用，避免前端逐个再查）。
 * 全生命周期追溯与审计重点核查项（报废、状态修正）都以本数据为唯一来源。
 */
@Data
public class BagTraceVo {

    /** 日志主键（写入有序） */
    private Long id;

    /** 流转前状态码 */
    private String fromStatus;

    /** 流转前状态中文名 */
    private String fromStatusName;

    /** 流转后状态码 */
    private String toStatus;

    /** 流转后状态中文名 */
    private String toStatusName;

    /** 触发事件码（BagEvent） */
    private String event;

    /** 触发事件中文名 */
    private String eventName;

    /** 操作人用户 ID */
    private Long operatorId;

    /** 操作人昵称（联查展示；账号已删除时为 null） */
    private String operatorName;

    /** 操作站点 ID（事件发生地） */
    private Long stationId;

    /** 操作站点名称（联查展示） */
    private String stationName;

    /** 是否平台管理员代操作（1=代操作） */
    private Integer proxyFlag;

    /** 盘点差异单号（仅 #21 状态修正必填） */
    private String refCheckNo;

    /** 备注（修正原因/找回说明等） */
    private String remark;

    /** 流转时间 */
    private LocalDateTime createdAt;
}
