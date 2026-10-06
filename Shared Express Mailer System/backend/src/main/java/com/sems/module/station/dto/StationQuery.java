package com.sems.module.station.dto;

import lombok.Data;

/**
 * 站点分页查询入参（见《系统设计说明书》5.3 GET /api/v1/stations 与 6.1「RESTful 约定」）。
 *
 * <p>分页口径：{@code pageNum} 默认 1、{@code pageSize} 默认 10 且最大 100（6.1）；
 * {@code orderBy} 仅接受白名单字段（形如 {@code field,asc|desc}），非法字段由 Service 层拒绝，
 * 排序一律以类型安全的列引用拼 SQL，杜绝字符串拼接注入（9.4）。
 * 时间区间作用于站点创建时间 {@code created_at}，支持 {@code yyyy-MM-dd} 或 {@code yyyy-MM-dd HH:mm:ss}。
 */
@Data
public class StationQuery {

    /** 页码（从 1 起，默认 1） */
    private Integer pageNum = 1;

    /** 每页条数（默认 10，最大 100） */
    private Integer pageSize = 10;

    /** 排序：白名单 {id,name,status,warnThreshold,createdAt,updatedAt} + asc|desc，缺省按 id 升序 */
    private String orderBy;

    /** 创建时间起（含） */
    private String beginTime;

    /** 创建时间止（含；仅给日期时含当天 23:59:59） */
    private String endTime;

    /** 关键字：按站点名称或地址模糊匹配 */
    private String keyword;

    /** 状态筛选：ENABLED / DISABLED */
    private String status;
}
