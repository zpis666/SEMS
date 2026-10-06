package com.sems.module.bag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站点出入库流水实体，对应表 {@code stock_record}（表概要与设计出处见《系统设计说明书》8.2；
 * 字段级定义见《数据库设计说明书》V1.1 第 4 章，与 {@code deploy/sql/V1__init_schema.sql} 逐列一致）。
 *
 * <p>只插入的旁路流水：记录 IN 入库 / OUT 出库 / SCRAP_OUT 报废出库三类事件（见设计书 3.4），
 * 与 Redis 可用库存计数（{@code stock:available}，见设计书 7.2）互为佐证，供对账与追溯；
 * 代操作时 {@code operator_id} 记平台管理员（见设计书 3.2 代操作口径）。
 *
 * <p>类型字段用 {@code String} 而非枚举：与 DDL 的 {@code varchar} 码值一致（IN/OUT/SCRAP_OUT），业务层按常量转换。
 */
@Data
@TableName("stock_record")
public class StockRecord {

    /** 流水ID（自增主键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发生出入库的站点ID */
    private Long stationId;

    /** 快递袋编码，关联 {@code bag.bag_code} */
    private String bagCode;

    /** 类型：IN 入库 / OUT 出库 / SCRAP_OUT 报废出库 */
    private String type;

    /** 操作人用户ID（代操作记平台管理员） */
    private Long operatorId;

    /** 备注（差异单号、报废说明等） */
    private String remark;

    /** 出入库发生时间 */
    private LocalDateTime createdAt;
}
