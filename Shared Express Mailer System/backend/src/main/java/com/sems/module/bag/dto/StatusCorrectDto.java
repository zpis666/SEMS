package com.sems.module.bag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 状态修正入参（见《系统设计说明书》5.4「POST /bags/{bagCode}/status-correct」、3.2 #21）。
 *
 * <p>前置校验（设计书 3.2 #21）：盘点差异单号与修正原因必填；目标态白名单
 * = {空闲 IDLE, 维修中 REPAIRING, 已遗失 LOST, 报废 SCRAPPED}；袋未被未出库调拨单引用
 * （命中报 4110）；跨站修正须指定目标站点。库存增减、订单闭环与业务记录写入
 * 由 #21 的伴随动作按公式完成（审计重点核查项）。
 */
@Data
public class StatusCorrectDto {

    /** 目标状态码：IDLE / REPAIRING / LOST / SCRAPPED（白名单在 Service 层二次校验） */
    @NotBlank(message = "目标状态不能为空")
    @Size(max = 20, message = "目标状态码长度不合法")
    private String targetStatus;

    /** 盘点差异单号（必填，写入流转日志 ref_check_no） */
    @NotBlank(message = "盘点差异单号不能为空")
    @Size(max = 32, message = "盘点差异单号长度不能超过 32")
    private String refCheckNo;

    /** 修正原因（必填，写入流转日志备注；转入维修时作为破损描述） */
    @NotBlank(message = "修正原因不能为空")
    @Size(max = 255, message = "修正原因长度不能超过 255")
    private String remark;

    /**
     * 目标站点 ID（可选）：仅在跨站修正时指定，表示袋归属变更为该站点。
     * 不传即视为同站修正（保持袋当前 station_id 不变），符合 #21「仅同站且同态不动」的口径。
     */
    private Long targetStationId;
}
