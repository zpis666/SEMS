package com.sems.module.bag.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 激活上架入参（见《系统设计说明书》5.4「POST /bags/{bagCode}/activate」、3.2 #1）。
 *
 * <p>激活本身不需要业务字段：归属校验按袋的登记站点与操作人站点比对完成（设计书 3.2 #1
 * 「按袋的登记站点校验归属」）。保留可选的备注字段，用于流转日志留痕（如"贴标完成"）。
 */
@Data
public class ActivateDto {

    /** 备注（可选，写入流转日志） */
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;
}
