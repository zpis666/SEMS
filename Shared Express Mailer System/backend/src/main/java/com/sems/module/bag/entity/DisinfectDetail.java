package com.sems.module.bag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消毒批次袋码明细实体，对应表 {@code disinfect_detail}（表概要与设计出处见《系统设计说明书》8.2；
 * 字段级定义见《数据库设计说明书》V1.1 第 4 章，与 {@code deploy/sql/V1__init_schema.sql} 逐列一致）。
 *
 * <p>{@code (disinfect_id, bag_code)} 唯一：同一批次内同一只袋只允许登记一条，是消毒登记逐袋幂等的
 * 数据库兜底（Redis 层见设计书 7.2/9.6 的 clientRequestId+bagCode 幂等）；不合格时 {@code defect_desc} 必填。
 *
 * <p>结果字段用 {@code String} 而非枚举：与 DDL 的 {@code varchar} 中文码值一致（合格/不合格），业务层按常量转换。
 */
@Data
@TableName("disinfect_detail")
public class DisinfectDetail {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属消毒批次 ID */
    private Long disinfectId;

    /** 快递袋编码 */
    private String bagCode;

    /** 消毒结果：合格 / 不合格 */
    private String result;

    /** 不合格缺陷说明（合格为空） */
    private String defectDesc;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
