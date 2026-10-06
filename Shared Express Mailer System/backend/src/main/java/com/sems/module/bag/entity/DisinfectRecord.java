package com.sems.module.bag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消毒批次记录实体，对应表 {@code disinfect_record}（表概要与设计出处见《系统设计说明书》8.2；
 * 字段级定义见《数据库设计说明书》V1.1 第 4 章，与 {@code deploy/sql/V1__init_schema.sql} 逐列一致）。
 *
 * <p>一次扫码登记形成一个批次：{@code batch_no} 全局唯一（幂等兜底），批量袋数受
 * {@code sems.batch.disinfect-max} 上限约束（见设计书 5.4，阈值一律读配置、不写死）；
 * {@code bag_count}/{@code pass_count}/{@code fail_count} 由 {@link DisinfectDetail} 明细汇总回填。
 *
 * <p>结果字段用 {@code String} 而非枚举：与 DDL 的 {@code varchar} 中文码值一致
 * （合格/部分不合格/不合格；明细为 合格/不合格），业务层按常量转换。
 */
@Data
@TableName("disinfect_record")
public class DisinfectRecord {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消毒站点 ID */
    private Long stationId;

    /** 消毒批次号（全局唯一） */
    private String batchNo;

    /** 消毒方式：紫外线/臭氧/高温/喷洒 */
    private String method;

    /** 消毒开始时间 */
    private LocalDateTime startTime;

    /** 消毒结束时间 */
    private LocalDateTime endTime;

    /** 消毒登记操作人 ID */
    private Long operatorId;

    /** 批次汇总：合格/部分不合格/不合格 */
    private String result;

    /** 本批消毒袋数 */
    private Integer bagCount;

    /** 合格袋数 */
    private Integer passCount;

    /** 不合格袋数 */
    private Integer failCount;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
