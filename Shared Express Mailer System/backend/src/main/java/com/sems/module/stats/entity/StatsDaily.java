package com.sems.module.stats.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日统计汇总（对应表 {@code stats_daily}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>看板指标的每日沉淀（可选优化表）：校园规模下各看板可基于索引实时聚合，
 * 本表用于历史趋势（如周转率曲线）与离线重算；唯一键 (date, station_id) 保证
 * 同站同日只一行，{@code station_id=0} 为全平台汇总行。
 *
 * <p>date 列为 MySQL 的 DATE 类型，按契约映射为 {@link LocalDate}；
 * stock_json 为 JSON 列，以字符串读写，业务层用 {@code com.sems.common.util.JsonUtils} 转换。
 */
@Data
@TableName("stats_daily")
public class StatsDaily {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 统计日期（date 列 → java.time.LocalDate，与 (station_id) 联合唯一） */
    private LocalDate date;

    /** 站点ID（0=全平台汇总） */
    private Long stationId;

    /** 当日取袋（申领）订单数 */
    private Integer ordersBorrow;

    /** 当日归还确认订单数 */
    private Integer ordersReturn;

    /** 当日周转率（归还数/在循环均数，口径见设计书 3.5） */
    private Double turnoverRate;

    /** 库存分布快照（各状态袋数，JSON）；MySQL JSON 列以字符串读写，业务层用 JsonUtils 转换 */
    private String stockJson;

    /** 汇总生成时间 */
    private LocalDateTime createdAt;

    /** 重算更新时间（自动刷新） */
    private LocalDateTime updatedAt;
}
