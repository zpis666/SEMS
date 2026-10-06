package com.sems.module.bag.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 上架入库入参（批量）（见《系统设计说明书》5.4「POST /bags/stock-in」、3.2 #11）。
 *
 * <p>批量范式（契约模块版第 5 节）：入参为逐袋项列表、出参为逐袋结果明细
 * （{@code bagCode/success/code/message}），单只失败不影响其余；单批上限
 * {@code sems.batch.max-size}（默认 500），由 Service 层读配置校验。
 */
@Data
public class StockInDto {

    /** 逐袋项（每项以袋码为键）；数量上限读 {@code sems.batch.max-size}，在 Service 层校验并给出上限值 */
    @Valid
    @NotEmpty(message = "袋码清单不能为空")
    private List<Item> bags;

    /** 单袋项 */
    @Data
    public static class Item {

        /** 快递袋编码（SJB 定长码，格式先由正则拦截，存在性由 Service 校验） */
        @NotBlank(message = "袋码不能为空")
        @Pattern(regexp = "^SJB-[A-Z]-\\d{4}-\\d{5}-[0-9A-Z]$", message = "袋码格式不正确")
        private String bagCode;

        /** 备注（可选，写入流转日志） */
        @Size(max = 255, message = "备注长度不能超过 255")
        private String remark;
    }
}
