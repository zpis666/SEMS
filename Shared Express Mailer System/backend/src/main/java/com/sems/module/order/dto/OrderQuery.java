package com.sems.module.order.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 订单分页查询入参（见《系统设计说明书》5.2「本人订单分页查询（状态筛选）」、
 * 5.5「全量订单查询（状态/站点/时间筛选）」与 6.1 分页约定）。
 *
 * <p><b>为什么没有 userId 字段</b>：本人订单接口强制 {@code user_id = 当前登录 uid}
 * （见设计书 4.4 与契约模块 6）——接收前端传入的 userId 会直接构成水平越权入口，
 * 故本 DTO 刻意不提供该字段，管理端筛选范围也按 5.5 仅限状态/站点/时间。
 *
 * <p>排序字段只接受白名单（见契约 0「排序字段必须走白名单」），白名单在 Service 层以
 * switch 映射为实体列，绝不把请求参数字符串拼进 SQL。
 */
@Data
public class OrderQuery {

    /** 页码（默认 1） */
    private Integer pageNum;

    /** 每页条数（默认 10，最大 100） */
    private Integer pageSize;

    /** 订单状态筛选（OrderStatus 码值，可空） */
    private String status;

    /** 取袋站点筛选（管理端用，可空） */
    private Long stationId;

    /** 袋码筛选（管理端用，可空） */
    private String bagCode;

    /** 建单时间起（含），格式 yyyy-MM-dd HH:mm:ss */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    /** 建单时间止（含），格式 yyyy-MM-dd HH:mm:ss */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /** 排序（白名单字段 + ,asc|,desc，如 createdAt,desc；缺省 createdAt,desc） */
    private String orderBy;

    /** 页码归一：空或越界时取 1。 */
    public int pageNumOrDefault() {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    /** 每页条数归一：默认 10，封顶 100（见设计书 6.1）。 */
    public int pageSizeOrDefault() {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, 100);
    }
}
