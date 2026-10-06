package com.sems.module.station.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 调拨单分页查询入参（见《系统设计说明书》5.3「GET /api/v1/allocations」与 6.1 查询参数约定）。
 *
 * <p>字段口径：分页 {@code pageNum/pageSize}（默认 1/10、上限 100）；筛选 {@code status/fromStationId/toStationId}；
 * 时间区间按创建时间（{@code beginTime/endTime}）；排序 {@code orderBy=字段,asc|desc}，
 * 字段白名单在 {@code AllocationService} 内校验，非法字段回落默认排序（禁止把用户输入拼进 SQL）。
 *
 * <p>数据范围不在入参里开放：站点管理员强制过滤「源站或目标站=本站」由 Service 层追加（见 4.4 第⑤条），
 * 请求参数无法绕过。
 */
@Data
public class AllocationQuery {

    /** 页码（默认 1） */
    private Integer pageNum = 1;

    /** 每页条数（默认 10，最大 100） */
    private Integer pageSize = 10;

    /** 单据状态筛选：PENDING_DISPATCH/IN_TRANSIT/COMPLETED/ABNORMAL/CANCELLED/TERMINATED */
    private String status;

    /** 源站点筛选 */
    private Long fromStationId;

    /** 目标站点筛选 */
    private Long toStationId;

    /** 创建时间起（含） */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    /** 创建时间止（含） */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /** 排序：字段,asc|desc；白名单 createdAt/dispatchAt/completedAt/abnormalAt/status，默认 createdAt,desc */
    private String orderBy;
}
