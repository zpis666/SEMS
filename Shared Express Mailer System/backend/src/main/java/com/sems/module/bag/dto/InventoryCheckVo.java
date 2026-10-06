package com.sems.module.bag.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 盘点单出参（{@code POST /api/v1/inventory-checks}、
 * {@code GET /api/v1/inventory-checks}、{@code POST /api/v1/inventory-checks/{id}/void}，
 * 见《系统设计说明书》5.4 与 8.2 盘点单表）。
 *
 * <p>{@code expectJson / actualJson} 为「状态码 → 袋数」快照（对应表列 {@code expect_json /
 * actual_json}）；{@code diffBags} 为袋级差异清单（对应表列 {@code diff_bags_json}，
 * 元素含袋码与差异类型），{@code diffCount} 为清单条数。
 */
@Data
public class InventoryCheckVo {

    /** 盘点单主键 ID（作废接口的路径参数） */
    private Long id;

    /** 盘点单号（IC + 时间戳 + 随机段，全局唯一；状态修正经 ref_check_no 引用之） */
    private String checkNo;

    /** 盘点站点 ID */
    private Long stationId;

    /** 实盘执行时间（提交时点） */
    private LocalDateTime checkTime;

    /** 账面各状态袋数快照（状态码 → 袋数） */
    private Map<String, Integer> expectJson;

    /** 实盘各状态袋数快照（状态码 → 袋数） */
    private Map<String, Integer> actualJson;

    /** 袋级差异清单（候选袋码 + 差异类型：盘亏/盘盈） */
    private List<DiffBag> diffBags;

    /** 差异袋数（差异清单条数） */
    private Integer diffCount;

    /** 状态：PENDING_FIX 待修正 / CLOSED 已闭环 / VOID 作废 */
    private String status;

    /** 盘点人（提交账号）ID */
    private Long operatorId;

    /** 闭环时间（差异修正完毕，本期无闭环操作入口，保留展示） */
    private LocalDateTime closedAt;

    /** 作废时间 */
    private LocalDateTime voidedAt;

    /** 作废操作人 ID */
    private Long voidedBy;

    /** 作废原因（重盘说明） */
    private String voidReason;

    /** 备注 */
    private String remark;

    /** 提交时间 */
    private LocalDateTime createdAt;

    /** 袋级差异清单元素：袋码 + 差异类型（盘亏=账面有实盘无，盘盈=实盘多出） */
    @Data
    public static class DiffBag {

        /** 快递袋编码 */
        private String bagCode;

        /** 差异类型：盘亏 / 盘盈 */
        private String diffType;
    }
}
