package com.sems.module.station.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站点信息出参（见《系统设计说明书》5.3 站点管理接口与 8.2 站点表）。
 *
 * <p>字段与 {@code station} 表列一一对应；{@code statusName} 为中文展示名（启用/停用），
 * 供管理端列表直接渲染，避免前端再维护一份码值映射。
 */
@Data
public class StationVo {

    /** 站点ID */
    private Long id;

    /** 站点名称（全平台唯一） */
    private String name;

    /** 站点地址 */
    private String address;

    /** 开放时段展示文本 */
    private String openTime;

    /** 库存预警阈值（0=不预警） */
    private Integer warnThreshold;

    /** 状态码：ENABLED / DISABLED */
    private String status;

    /** 状态中文名：启用 / 停用 */
    private String statusName;

    /** 停用时指定的接收站ID（可空） */
    private Long handoverStationId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
