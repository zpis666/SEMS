package com.sems.module.bag.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 袋档案详情出参（台账列表、激活/维修/报废等写操作结果统一复用）。
 *
 * <p>字段与 {@code bag} 表逐列对应（多一个联查得到的 {@code stationName} 展示字段），
 * 见《系统设计说明书》5.4 与 8.2；不含 {@code bag_code} 之外的任何敏感数据。
 */
@Data
public class BagDetailVo {

    /** 主键 ID */
    private Long id;

    /** 袋编码（SJB-规格-年月-流水-校验） */
    private String bagCode;

    /** 规格（1 位码值） */
    private String spec;

    /** 颜色 */
    private String color;

    /** 容量 */
    private String capacity;

    /** 当前存放站点 ID（随实物转移，见设计书 3.2 站点归属规则） */
    private Long stationId;

    /** 当前存放站点名称（联查展示） */
    private String stationName;

    /** 状态码（11 态枚举码） */
    private String status;

    /** 状态中文名（与设计书 3.1 一致） */
    private String statusName;

    /** 复用次数（唯一递增点：回收确认） */
    private Integer useCount;

    /** 复用上限（超限拒绝上架入库，报 4108） */
    private Integer maxUseCount;

    /** 乐观锁版本 */
    private Integer version;

    /** 建档时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
