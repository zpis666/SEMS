package com.sems.module.bag.dto;

import lombok.Data;

/**
 * 消毒批次袋码明细出参（{@code GET /api/v1/disinfections} 的批次明细；
 * {@code POST /api/v1/disinfections} 的逐袋处理结果，见《系统设计说明书》5.4 与 3.2 #9/#10）。
 *
 * <p><b>逐袋处理结果</b>（批量接口统一口径，契约模块版 5）字段固定为
 * {@code bagCode / success / code / message}，前端批量结果弹窗按此渲染；
 * 其中 {@code result / defectDesc} 为该袋的消毒判定与缺陷说明（前端失败重试时按袋码保留明细）。
 *
 * <p><b>批次查询明细</b>只填 {@code bagCode / result / defectDesc}，
 * {@code success / code / message} 保持为空（Jackson 已配置 non_null 不输出），两者共用本 VO。
 */
@Data
public class DisinfectDetailVo {

    /** 快递袋编码 */
    private String bagCode;

    /** 消毒结果：合格 / 不合格 */
    private String result;

    /** 不合格缺陷说明（合格为空） */
    private String defectDesc;

    /** 该袋是否处理成功（仅 POST 返回逐袋处理结果时填充） */
    private Boolean success;

    /** 失败错误码（成功为 null，仅 POST 返回逐袋处理结果时填充） */
    private Integer code;

    /** 失败原因/处理说明（仅 POST 返回逐袋处理结果时填充） */
    private String message;
}
