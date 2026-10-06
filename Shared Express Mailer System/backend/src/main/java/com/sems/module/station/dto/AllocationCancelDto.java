package com.sems.module.station.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 取消 / 终止调拨单入参（见《系统设计说明书》5.3「DELETE /api/v1/allocations/{id}」与 3.4.3 异常处理）。
 *
 * <p>DELETE 端点按单据状态分派：待出库（PENDING_DISPATCH）走「取消」——明细解锁回空闲；
 * 异常（ABNORMAL）走「终止」——未出库明细解锁，其余保留差异记录。原因写入
 * {@code allocation_order.cancel_reason}；缺省时由服务端补默认文案。
 *
 * <p>请求体可缺省（DELETE 不带 body 属常见调用方式），此时按默认原因处理。
 */
@Data
public class AllocationCancelDto {

    /** 取消 / 终止原因（可空，缺省由服务端补默认文案；列宽 255） */
    @Size(max = 255, message = "取消/终止原因不能超过 255 字")
    private String reason;
}
