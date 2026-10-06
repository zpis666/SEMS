package com.sems.module.bag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 维修记录实体，对应表 {@code repair_record}（表概要与设计出处见《系统设计说明书》8.2；
 * 字段级定义见《数据库设计说明书》V1.1 第 4 章，与 {@code deploy/sql/V1__init_schema.sql} 逐列一致）。
 *
 * <p>登记/完成两阶段共用一行：转维修时写 {@code start_time} 与破损描述（{@code reporter_id} 记登记人）；
 * 修复入库或判定报废时回填 {@code end_time}/{@code result}/{@code operator_id}（见设计书 3.4.2）。
 * #21 状态修正转入维修时，{@code description} 取修正原因（见设计书 3.2）。
 *
 * <p>结果字段用 {@code String} 而非枚举：与 DDL 的 {@code varchar} 中文码值一致（修复/无法修复），业务层按常量转换。
 */
@Data
@TableName("repair_record")
public class RepairRecord {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 快递袋编码 */
    private String bagCode;

    /** 报修人（登记破损的管理员；#21 状态修正转入维修时=修正操作人） */
    private Long reporterId;

    /** 破损描述（报修时登记；#21 转入维修时取修正原因） */
    private String description;

    /** 维修开始时间（转维修时点） */
    private LocalDateTime startTime;

    /** 维修完成时间（未完成为空） */
    private LocalDateTime endTime;

    /** 维修结果：修复 / 无法修复（未完成为空） */
    private String result;

    /** 维修完成操作人（未完成为空） */
    private Long operatorId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（库内 ON UPDATE 自动刷新） */
    private LocalDateTime updatedAt;
}
