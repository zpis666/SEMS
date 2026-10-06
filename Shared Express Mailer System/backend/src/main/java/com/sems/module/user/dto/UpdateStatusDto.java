package com.sems.module.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 启用/禁用账号入参（见《系统设计说明书》5.1 {@code PATCH /api/v1/users/{id}/status}，FR-M1-16）。
 *
 * <p>禁用触发 {@code token_version} 自增（5.1 与 7.1）：被禁用账号的存量令牌在下一次请求
 * 即被拒绝，「封号即失效」由此兑现；启用不涉及既有令牌，不触发吊销。
 */
@Data
public class UpdateStatusDto {

    /** 目标状态：ENABLED 启用 / DISABLED 禁用（值域同 Constants.STATUS_*） */
    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "^(ENABLED|DISABLED)$", message = "状态只能为 ENABLED 或 DISABLED")
    private String status;
}
