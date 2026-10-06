package com.sems.module.station.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建调拨单入参（见《系统设计说明书》5.3「POST /api/v1/allocations」与 3.4.3 调拨流程）。
 *
 * <p>语义：平台管理员安排源站向目标站调拨一批快递袋，逐只扫码登记袋码；建单即锁定明细
 * （{@code allocation_detail} 以 PENDING_DISPATCH 行锁定），被锁定的袋在申领/转维修/报废/状态修正时
 * 报 4110——唯一判定入口见 {@code AllocationService#isBagLocked(String)}。
 */
@Data
public class CreateAllocationDto {

    /** 源站点ID（调出方） */
    @NotNull(message = "源站点不能为空")
    private Long fromStationId;

    /** 目标站点ID（调入方；必须与源站不同） */
    @NotNull(message = "目标站点不能为空")
    private Long toStationId;

    /** 袋码清单（服务端先去重；逐只校验存在、空闲且未被其他未出库调拨单引用） */
    @NotEmpty(message = "袋码清单不能为空")
    private List<String> bagCodes;

    /** 备注（可空） */
    @Size(max = 255, message = "备注长度不能超过 255 字")
    private String remark;
}
