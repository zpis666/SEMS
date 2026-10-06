package com.sems.module.station.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 在途遗失确认入参（见《系统设计说明书》5.3「POST /api/v1/allocations/{id}/lost-confirm」、
 * 3.2 #19 与 3.4.3：出库后 48h 未核收 → 平台管理员确认在途遗失）。
 *
 * <p>逐只触发 LOST_IN_TRANSIT（袋→已遗失）；单据置「异常」并写责任记录
 * （biz_type=ALLOCATION、type=在途遗失、responsible_station_id=源站）。
 */
@Data
public class AllocationLostDto {

    /** 确认遗失的袋码清单（每项以 bagCode 为键） */
    /**
     * 待确认遗失的袋码清单。<b>可空</b>：在途遗失确认是**单据级**裁决
     * （设计书 5.3「在途遗失确认（袋→已遗失，调拨单异常，写责任记录）」），
     * 平台管理员通常是对整张在途单裁决，不必（也不应）由前端枚举袋码——
     * 前端枚举期间若有袋被核收，就会提交一份过期的清单。
     * 因此缺省语义为「作用于该单当前全部已出库未核收的明细」，由 Service 现算。
     */
    @Valid
    private List<BagItem> bags;

    /** 处置结论（可空，落 liability_record.conclusion；缺省由服务端给默认结论） */
    @Size(max = 255, message = "处置结论不能超过 255 字")
    private String conclusion;

    /** 注记（可空，落 liability_record.note） */
    @Size(max = 255, message = "注记不能超过 255 字")
    private String note;

    /** 单袋项 */
    @Data
    public static class BagItem {

        /** 快递袋编码（SJB 定长码） */
        @NotBlank(message = "袋码不能为空")
        private String bagCode;

        /** 该袋备注（可空，落 allocation_detail.remark） */
        @Size(max = 255, message = "备注长度不能超过 255 字")
        private String remark;
    }
}
