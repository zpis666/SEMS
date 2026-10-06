package com.sems.module.bag.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 库存盘点单提交入参（{@code POST /api/v1/inventory-checks}，见《系统设计说明书》5.4、
 * 3.4.4「库存盘点流程」与需求 FR-M4-17）。
 *
 * <p>流程：系统先按 DB 实时统计本站各状态账面数（DB 为权威口径），与录入的实盘数逐状态比对，
 * 自动计算差异与袋级差异清单，生成状态为「待修正」的盘点单；盘点单不可删除，仅可作废重盘。
 *
 * <p>{@code actualJson} 为「状态码 → 实盘数」映射（键如 IDLE/IN_USE/...，值为非负整数），
 * 键合法性、数值非负与缺省状态补 0 的归一化在 Service 完成；前端提交的 {@code expectJson}
 * 只作展示参考，服务端不采信（账面数一律实时统计）。
 */
@Data
public class CreateInventoryCheckDto {

    /** 盘点站点 ID：站点管理员缺省取本人绑定站点（传他站报 2002）；平台管理员必填 */
    private Long stationId;

    /** 实盘各状态袋数（键 = 袋状态码，值 = 非负整数） */
    @NotEmpty(message = "实盘各状态袋数不能为空")
    private Map<String, Integer> actualJson;

    /** 备注（选填，≤255 字符） */
    @Size(max = 255, message = "备注长度不能超过 255 字符")
    private String remark;
}
