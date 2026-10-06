package com.sems.module.bag.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消毒批次登记入参（{@code POST /api/v1/disinfections}，见《系统设计说明书》5.4 与 3.2 #9/#10）。
 *
 * <p>字段口径：站点（站点管理员可省、默认本站；平台管理员代操作时必填，见 4.4）、消毒方式、
 * 起止时间、备注与袋码明细；明细逐袋给「合格/不合格」判定，不合格必须附缺陷说明。
 * 逐袋结果为「不合格」而缺缺陷说明时，只判该袋失败（返回逐袋明细），不影响同批其余袋——
 * 因此明细项刻意不挂 JSR-380 级联约束，交由 Service 逐袋校验（契约模块版 5 的批量范式）。
 *
 * <p>{@code bags} 兼容前端已使用的字段名 {@code details}/{@code items}（{@link JsonAlias} 别名），
 * 避免联调期因字段名差异整单被 3001 拒绝。
 *
 * <p>时间格式与前端日期选择器（{@code YYYY-MM-DD HH:mm:ss}）一致；全局 Jackson 的
 * {@code spring.jackson.date-format} 只作用于 {@code java.util.Date}，JSR-310 需显式
 * {@link JsonFormat} 指定，否则 ISO 字符串与「空格分隔」格式对不上会解析失败。
 */
@Data
public class CreateDisinfectDto {

    /** 消毒站点 ID：站点管理员缺省取本人绑定站点（传他站报 2002）；平台管理员必填 */
    private Long stationId;

    /** 消毒方式：紫外线 / 臭氧 / 高温 / 喷洒（与消毒记录表 method 列的码值口径一致） */
    @NotBlank(message = "消毒方式不能为空")
    private String method;

    /** 消毒开始时间（格式 yyyy-MM-dd HH:mm:ss） */
    @NotNull(message = "消毒开始时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    /** 消毒结束时间（不得早于开始时间，时序校验在 Service） */
    @NotNull(message = "消毒结束时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /** 备注（选填，≤255 字符，与库列 varchar(255) 一致） */
    @Size(max = 255, message = "备注长度不能超过 255 字符")
    private String remark;

    /** 袋码明细（1 ~ sems.batch.disinfect-max 只；上限检查在 Service，超限抛 3001 并写明上限） */
    @NotEmpty(message = "消毒袋码明细不能为空")
    @JsonAlias({"details", "items"})
    private List<BagItem> bags;

    /** 逐袋登记项：合格触发转移 #9（待消毒→可复用）；不合格触发 #10（待消毒→维修中） */
    @Data
    public static class BagItem {

        /** 快递袋编码：格式、存在性、归属与状态合法性全部由 Service 逐袋校验并逐袋返回结果 */
        private String bagCode;

        /** 逐袋消毒结果：合格 / 不合格 */
        private String result;

        /** 不合格缺陷说明（不合格必填；合格时忽略） */
        private String defectDesc;
    }
}
