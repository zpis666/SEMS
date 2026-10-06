package com.sems.module.station.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 共享站点（对应表 {@code station}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>袋的收发与回收服务点，也是库存计数的归属主体（Redis {@code stock:available:{stationId}}）。
 * 站点停用由 {@code status} 表达，本系统不使用逻辑删除；停用前须满足「可用库存为 0 且非终态
 * 实物为 0 或已指定接收站」，接收站记于 {@link #handoverStationId}（见设计书 8.2）。
 *
 * <p>字段与 {@code deploy/sql/V1__init_schema.sql} 的 station 表逐列对应，不额外声明非表字段。
 */
@Data
@TableName("station")
public class Station {

    /** 站点ID，自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 站点名称，全平台唯一（uk_station_name） */
    private String name;

    /** 站点地址（展示与就近引导） */
    private String address;

    /** 开放时段展示文本，不做硬校验 */
    private String openTime;

    /** 库存预警阈值（低于则预警；0=不预警） */
    private Integer warnThreshold;

    /** 状态：ENABLED 启用 / DISABLED 停用（值域同 Constants.STATUS_ENABLED / STATUS_DISABLED） */
    private String status;

    /** 停用时指定的接收站ID（可空；停用前置校验「已指定接收站」时读取） */
    private Long handoverStationId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（随修改自动刷新） */
    private LocalDateTime updatedAt;
}
