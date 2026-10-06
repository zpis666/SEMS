package com.sems.common;

import java.util.List;

/**
 * 分页数据载体（见《系统设计说明书》6.2）。
 *
 * <p>前端约定读取 {@code data.records / data.total / data.pages}。
 * {@code pages = ceil(total / size)}，由构造器统一计算，避免各处各算一套。
 *
 * @param <T> 记录类型
 */
public class PageResult<T> {

    private List<T> records;
    private long total;
    private long pages;

    public PageResult() {
    }

    public PageResult(List<T> records, long total, long size) {
        this.records = records;
        this.total = total;
        this.pages = size <= 0 ? 0 : (total + size - 1) / size;
    }

    public static <T> PageResult<T> of(List<T> records, long total, long size) {
        return new PageResult<>(records, total, size);
    }

    public List<T> getRecords() {
        return records;
    }

    public void setRecords(List<T> records) {
        this.records = records;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getPages() {
        return pages;
    }

    public void setPages(long pages) {
        this.pages = pages;
    }
}
