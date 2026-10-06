package com.sems.module.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 系统指标计数实体，映射表 {@code sys_metric}
 * （字段逐列对应 deploy/sql/V1__init_schema.sql，设计口径见《系统设计说明书》7.6）。
 *
 * <p>承载运行期累计指标，当前使用方为「审计队列丢弃计数」{@code audit_drop_count}：
 * 审计线程池队列饱和且事件已降级写入应急文件时按天累加，
 * 经 {@code /stats/dashboard} 的 {@code auditDropCount} 与 {@code /alerts}「审计队列丢弃」对外展示。
 *
 * <p>写入使用 {@code metric_key + stat_date} 唯一键做原子累加（INSERT ... ON DUPLICATE KEY UPDATE），
 * 不采用「先查后写」，避免并发计数互相覆盖。
 */
@Data
@TableName("sys_metric")
public class SysMetric {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 指标键（如 audit_drop_count，列宽 64） */
    private String metricKey;

    /** 统计日期（按天累计） */
    private LocalDate statDate;

    /** 指标值（累加计数） */
    private Long metricValue;

    /** 最后更新时间（库端默认值，应用不赋值） */
    private LocalDateTime updatedAt;
}
