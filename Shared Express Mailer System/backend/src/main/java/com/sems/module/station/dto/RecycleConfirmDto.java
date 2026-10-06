package com.sems.module.station.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 扫码回收确认入参（见《系统设计说明书》5.3 POST /api/v1/stations/{id}/recycle-confirm：
 * 对回收箱内袋逐只执行，按绑定订单状态自动路由 #4/#5/#7/#8；单批 ≤500 只、逐袋返回结果明细）。
 *
 * <p>批量上限由 Service 层读 {@code sems.batch.max-size}（默认 500）校验，
 * 超限统一抛 3001（6.2 批量接口的部分失败与上限口径）。
 */
@Data
public class RecycleConfirmDto {

    /** 回收箱内待确认的袋清单（逐袋独立执行、逐袋返回明细，单只失败不影响其余） */
    @Valid
    @NotEmpty(message = "回收确认清单不能为空")
    private List<RecycleConfirmItemDto> bags;
}
