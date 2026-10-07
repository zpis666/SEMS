package com.sems.module.bag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 快递袋报废记录实体，对应表 {@code scrap_record}（表概要与设计出处见《系统设计说明书》8.2；
 * 字段级定义见《数据库设计说明书》V1.1 第 4 章，与 {@code deploy/sql/V1__init_schema.sql} 逐列一致）。
 *
 * <p>报废动作的独立凭证（只插入）：报废是终态（仅 REPAIRING/IDLE/TO_DISINFECT 可进入，
 * 见设计书 3.2），{@code station_id} 记袋当时所在站；报废与状态修正同属审计必查事件（见设计书 7.6）。
 */
@Data
@TableName("scrap_record")
public class ScrapRecord {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 快递袋编码 */
    private String bagCode;

    /** 报废站点 ID（袋当时所在站） */
    private Long stationId;

    /** 报废原因（严重破损/超限/无法修复等） */
    private String reason;

    /** 报废时间（登记时点） */
    private LocalDateTime scrapTime;

    /** 报废登记操作人 ID */
    private Long operatorId;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
