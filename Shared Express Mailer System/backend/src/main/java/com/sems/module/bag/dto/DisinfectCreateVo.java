package com.sems.module.bag.dto;

import com.sems.common.BatchResult;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 消毒批次登记出参（{@code POST /api/v1/disinfections}，见《系统设计说明书》5.4 与 6.2：
 * 批量接口在 data 中返回逐袋结果明细）。
 *
 * <p>统一批量信封 {@link BatchResult} 的落地形态之一：{@code total / success / failed / items}
 * 由父类提供（items 为逐袋处理结果，元素字段名固定为 bagCode/success/code/message）；
 * 本类追加消毒批次头字段（id/batchNo/result/stationId/startTime/endTime/method），
 * 供前端登记成功后回显批次信息与逐袋结果弹窗。
 *
 * <p><b>与 {@link DisinfectVo} 的分工</b>：查询接口（{@code GET /api/v1/disinfections}）的
 * 分页与批次留档仍用 DisinfectVo（其明细键名为 details，保持不变）；仅登记接口改用本类，
 * 逐袋明细键名统一为 items——此前登记接口把明细放在 details，前端按 items 取值会取到空。
 */
@Data
// 父类 BatchResult 为 common 包手写风格，未覆写 equals/hashCode；callSuper=true 会生成
// "不同实例恒不相等"的错误语义，故显式声明 callSuper=false（只比较本类追加的批次头字段）。
@EqualsAndHashCode(callSuper = false)
public class DisinfectCreateVo extends BatchResult<DisinfectDetailVo> {

    /** 批次主键 ID */
    private Long id;

    /** 消毒批次号（DB + 时间戳 + 随机段，全局唯一） */
    private String batchNo;

    /** 批次汇总：合格 / 部分不合格 / 不合格 */
    private String result;

    /** 消毒站点 ID */
    private Long stationId;

    /** 消毒开始时间 */
    private LocalDateTime startTime;

    /** 消毒结束时间 */
    private LocalDateTime endTime;

    /** 消毒方式：紫外线 / 臭氧 / 高温 / 喷洒 */
    private String method;
}
