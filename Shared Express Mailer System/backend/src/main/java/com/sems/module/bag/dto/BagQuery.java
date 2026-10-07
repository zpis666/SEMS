package com.sems.module.bag.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 袋台账分页查询入参（见《系统设计说明书》5.4「GET /api/v1/bags」与 6.1 查询参数约定）。
 *
 * <p>分页默认 pageNum=1、pageSize=10，上限 100 由 Service 层收敛；
 * 排序字段走白名单（Service 层 switch 映射），禁止把前端传来的字段名直接拼进 SQL（设计书 9.4）。
 * 「本站站点管理员限本站袋」由 Service 层强制过滤（设计书 4.4），前端传入的 stationId
 * 只能进一步收窄范围，不能越过本站边界。
 */
@Data
public class BagQuery {

    /** 页码（默认 1） */
    private Integer pageNum = 1;

    /** 每页条数（默认 10，最大 100） */
    private Integer pageSize = 10;

    /** 状态筛选（8 态枚举码，如 IDLE；非法码报 3001） */
    private String status;

    /** 站点筛选（站点管理员仅允许本站，越界报 2002） */
    private Long stationId;

    /** 袋码关键字（包含匹配） */
    private String bagCode;

    /** 规格码精确筛选 */
    private String spec;

    /** 排序表达式 {@code field,asc|desc}，field 白名单：createdAt / updatedAt / useCount / bagCode */
    private String orderBy;

    /** 建档时间起（含） */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    /** 建档时间止（含） */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
