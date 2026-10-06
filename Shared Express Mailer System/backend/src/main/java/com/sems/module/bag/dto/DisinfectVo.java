package com.sems.module.bag.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消毒批次出参（{@code POST /api/v1/disinfections} 与 {@code GET /api/v1/disinfections}，
 * 见《系统设计说明书》5.4 与 8.2 的消毒批次记录表）。
 *
 * <p>批次汇总（{@code bagCount/passCount/failCount/result}）由批次内实际写入的袋码明细回填
 * （口径见消毒记录实体注释）：全合格写「合格」、全不合格写「不合格」、混合写「部分不合格」。
 * {@code details} 为逐袋结果：登记接口返回逐袋处理结果（含失败袋与失败原因，元素字段固定为
 * {@code bagCode/success/code/message}），查询接口返回批次留档明细（袋码/结果/缺陷说明）。
 */
@Data
public class DisinfectVo {

    /** 批次主键 ID */
    private Long id;

    /** 消毒批次号（DB + 时间戳 + 随机段，全局唯一） */
    private String batchNo;

    /** 消毒站点 ID */
    private Long stationId;

    /** 消毒方式：紫外线 / 臭氧 / 高温 / 喷洒 */
    private String method;

    /** 消毒开始时间 */
    private LocalDateTime startTime;

    /** 消毒结束时间 */
    private LocalDateTime endTime;

    /** 登记操作人 ID */
    private Long operatorId;

    /** 批次汇总：合格 / 部分不合格 / 不合格 */
    private String result;

    /** 本批实际完成消毒的袋数（明细汇总） */
    private Integer bagCount;

    /** 合格袋数 */
    private Integer passCount;

    /** 不合格袋数 */
    private Integer failCount;

    /** 备注 */
    private String remark;

    /** 登记时间 */
    private LocalDateTime createdAt;

    /** 袋码明细：登记接口为逐袋处理结果，查询接口为批次留档明细 */
    private List<DisinfectDetailVo> details;
}
