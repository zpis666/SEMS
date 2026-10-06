package com.sems.module.station.dto;

import com.sems.common.Constants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 回收确认逐袋条目：既是请求入参（袋码 + 「曾寄出」标注），也是逐袋结果明细出参
 * （见《系统设计说明书》5.3 POST /api/v1/stations/{id}/recycle-confirm 与 3.2 表后「#4 与 #5 的取用规则」）。
 *
 * <p>出参字段名固定为 {@code bagCode / success / code / message}（前端批量结果对话框按此渲染，不得改名）；
 * 另附 {@code event / toStatus} 便于核对本袋实际走的路由（#4 未使用回收 / #5 与 #8 回收确认 / #7 直投）。
 */
@Data
public class RecycleConfirmItemDto {

    /** 袋编码（扫码或手输；格式见 7.4，长度与校验位由正则约束） */
    @NotBlank(message = "袋码不能为空")
    @Pattern(regexp = Constants.BAG_CODE_REGEX, message = "袋码格式不正确")
    private String bagCode;

    /**
     * 「曾寄出」标注：站点扫码时勾选，表示用户迟寄未登记（订单已取消但实物确曾寄出）。
     * 命中时按 #5 待消毒路径处理并计 use_count（3.2 #4 表后说明）。
     */
    private Boolean shippedBefore;

    /** 逐袋结果：是否成功（0 与 1 之外的错误码见 code） */
    private boolean success;

    /** 逐袋结果：错误码（0=成功） */
    private int code;

    /** 逐袋结果：提示文案（成功说明路由，失败说明具体原因） */
    private String message;

    /** 本袋实际触发的流转事件码（RECOVER_UNUSED / RECOVER_CONFIRM / RECOVER_DIRECT） */
    private String event;

    /** 流转后的袋状态码（失败时为空） */
    private String toStatus;
}
