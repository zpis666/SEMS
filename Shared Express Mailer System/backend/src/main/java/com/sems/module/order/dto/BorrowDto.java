package com.sems.module.order.dto;

import com.sems.common.Constants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 申领取袋入参（见《系统设计说明书》5.2「共享快递袋服务模块」扫码申领；契约 9 第 1 步入参）。
 *
 * <p>前端扫码或手输袋码后提交；袋码格式先由 {@link Constants#BAG_CODE_REGEX} 在参数层拦截，
 * 存在性与状态校验在 Service 层执行（4106/4107/4102）。
 *
 * <p>为什么要 {@code @Size(max = 20)}：袋码列 {@code bag.varchar(20)}，
 * 先于正则拦截超长输入，避免把异常长度的字符串带进后续处理链。
 */
@Data
public class BorrowDto {

    /** 快递袋编码（SJB 定长码，如 SJB-L-2609-00001-4） */
    @NotBlank(message = "袋码不能为空")
    @Size(max = 20, message = "袋码长度不能超过 20 位")
    @Pattern(regexp = Constants.BAG_CODE_REGEX, message = "袋码格式不正确")
    private String bagCode;
}
