package com.sems.module.station.dto;

import com.sems.common.BatchResult;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 扫码回收确认出参（见《系统设计说明书》5.3 与 6.2：批量接口在 data 中返回逐袋结果明细）。
 *
 * <p>统一批量信封 {@link BatchResult} 的落地形态之一：{@code total / success / failed / items}
 * 四个键由父类提供（items 为逐袋明细，元素字段名固定为 bagCode/success/code/message），
 * 对外 JSON 键名与语义与改造前保持一致；本类只追加回收确认特有的三个审计上下文计数
 * （设计书 7.6：回收确认按上下文区分「回收确认-直投 / 回收确认-跨站 / 回收确认-未标记直投」
 * 三档 action，供审计查询按动作筛出重点核查项）：
 * <ul>
 *   <li>{@code proxy}：平台管理员代操作确认成功的袋数（代操作一律计入跨站档）；</li>
 *   <li>{@code crossStation}：袋原站点 ≠ 确认站点（跨站归还/回收）的袋数；</li>
 *   <li>{@code directWithoutMark}：袋为使用中且绑定订单非「待回收确认」（未标记直投，走 #7）的袋数。</li>
 * </ul>
 */
@Data
// 父类 BatchResult 为 common 包手写风格，未覆写 equals/hashCode；callSuper=true 会生成
// "不同实例恒不相等"的错误语义，故显式声明 callSuper=false（只比较本类追加的上下文计数）。
@EqualsAndHashCode(callSuper = false)
public class RecycleConfirmVo extends BatchResult<RecycleConfirmItemDto> {

    /** 平台管理员代操作计数（审计上下文） */
    private int proxy;

    /** 跨站确认计数（审计上下文） */
    private int crossStation;

    /** 未标记直投计数（审计上下文） */
    private int directWithoutMark;

    /**
     * 便捷静态工厂：逐袋成败计数统一交给 {@link BatchResult#of(List, java.util.function.Predicate)}
     * 按元素 success 标志汇总，业务侧不再手写另一套计数；三个上下文计数由调用方随后赋值。
     */
    public static RecycleConfirmVo of(List<RecycleConfirmItemDto> items) {
        BatchResult<RecycleConfirmItemDto> counted = BatchResult.of(items, RecycleConfirmItemDto::isSuccess);
        RecycleConfirmVo vo = new RecycleConfirmVo();
        vo.setTotal(counted.getTotal());
        vo.setSuccess(counted.getSuccess());
        vo.setFailed(counted.getFailed());
        vo.setItems(counted.getItems());
        return vo;
    }
}
