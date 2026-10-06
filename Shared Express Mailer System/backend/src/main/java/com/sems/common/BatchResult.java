package com.sems.common;

import java.util.List;
import java.util.function.Predicate;

/**
 * 通用批量结果信封（见《系统设计说明书》6.2「批量接口的部分失败」：逐袋执行的批量接口
 * ——回收确认、上架入库、出库、消毒登记——在 {@code data} 中返回逐袋结果明细，单只失败不影响其余）。
 *
 * <p><b>为什么统一成信封</b>：这四个逐袋批量接口原先各返回一种形态——回收确认返回带 items 的对象、
 * 出入库返回裸数组、消毒登记把逐袋明细放在 details 键，前端只能逐个猜测形态做兼容，
 * 已出现「按 items 取值只对回收确认生效、另两处取到空」的缺陷。现统一为本信封：
 * <b>逐袋明细键名固定为 {@code items}</b>，逐袋元素字段名固定为
 * {@code bagCode / success / code / message}；前端批量结果组件与页面按此消费，不得改名。
 *
 * <p>各批量接口可在本信封之上追加批次级字段（如回收确认的 proxy/crossStation/directWithoutMark、
 * 消毒登记批次的 id/batchNo/result 等）——继承本类即可；<b>继承后不得再声明同名字段</b>，
 * 否则继承来的 getter 与子类字段会被 Jackson 序列化成重复 JSON 键。
 *
 * @param <T> 逐袋明细元素类型（元素字段须含 bagCode/success/code/message）
 */
public class BatchResult<T> {

    /** 提交处理的总数 */
    private int total;

    /** 成功数 */
    private int success;

    /** 失败数 */
    private int failed;

    /** 逐袋明细（键名固定为 items） */
    private List<T> items;

    public BatchResult() {
    }

    public BatchResult(int total, int success, int failed, List<T> items) {
        this.total = total;
        this.success = success;
        this.failed = failed;
        this.items = items;
    }

    /**
     * 按逐袋明细与判成功谓词组装信封：
     * {@code total = items.size()}、{@code success = 谓词为真的条数}、{@code failed = total - success}。
     *
     * @param items     逐袋明细（null 或空表示无提交袋，返回空信封）
     * @param isSuccess 判成功谓词（不可为 null：取元素自身的 success 标志，或按错误码约定判定）
     * @param <T>       逐袋明细元素类型
     */
    public static <T> BatchResult<T> of(List<T> items, Predicate<T> isSuccess) {
        if (items == null || items.isEmpty()) {
            return empty();
        }
        int successCount = 0;
        for (T item : items) {
            if (isSuccess.test(item)) {
                successCount++;
            }
        }
        return new BatchResult<>(items.size(), successCount, items.size() - successCount, items);
    }

    /** 空信封（无逐袋明细，total/success/failed 均为 0）。 */
    public static <T> BatchResult<T> empty() {
        return new BatchResult<>(0, 0, 0, List.of());
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getSuccess() {
        return success;
    }

    public void setSuccess(int success) {
        this.success = success;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(int failed) {
        this.failed = failed;
    }

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }
}
