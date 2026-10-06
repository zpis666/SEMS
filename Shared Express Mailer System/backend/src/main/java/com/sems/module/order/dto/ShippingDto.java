package com.sems.module.order.dto;

import com.sems.common.Constants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交寄件信息入参（见《系统设计说明书》5.2「提交寄件信息」与 3.2 #3 前置校验；契约 9 转移 #3）。
 *
 * <p>收件人手机号<b>只在内存中经过</b>：落库前立即转成掩码（展示）与 HMAC-SHA256 摘要（留存证据），
 * 明文不落库、不入日志、不回显（见设计书 8.2/9.5）。
 *
 * <p>字段长度上限与 DDL 对齐（{@code express_company varchar(30)}、{@code tracking_no varchar(20)}），
 * 超长在参数层拦截（3001），避免数据库层报错暴露内部结构。
 */
@Data
public class ShippingDto {

    /** 快递公司名称（必填，≤30 字符） */
    @NotBlank(message = "快递公司不能为空")
    @Size(max = 30, message = "快递公司名称长度不能超过 30 位")
    private String expressCompany;

    /** 运单号（必填，8–20 位字母数字，见设计书 10.4；公司+单号联合唯一） */
    @NotBlank(message = "运单号不能为空")
    @Size(max = 20, message = "运单号长度不能超过 20 位")
    @Pattern(regexp = Constants.TRACKING_NO_REGEX, message = "运单号格式不正确")
    private String trackingNo;

    /** 收件人手机号（必填，中国大陆 11 位；落库仅存掩码与哈希，不存明文） */
    @NotBlank(message = "收件人手机号不能为空")
    @Pattern(regexp = Constants.PHONE_REGEX, message = "收件人手机号格式不正确")
    private String recipientPhone;
}
