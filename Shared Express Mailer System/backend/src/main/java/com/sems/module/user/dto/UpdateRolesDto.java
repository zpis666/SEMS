package com.sems.module.user.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 调整角色入参（见《系统设计说明书》5.1 {@code PUT /api/v1/users/{id}/roles}，FR-M1-13）。
 *
 * <p>语义为「全量替换」：请求中的 {@code roles} 即目标账号调整后的全部角色，
 * Service 在同一事务内先删 {@code sys_user_role} 旧关联再插入新关联。
 * 最终角色含 {@code STATION_ADMIN} 时必须绑定站点——优先取本请求的 {@code stationId}，
 * 未携带则沿用账号现有绑定；最终角色不含 {@code STATION_ADMIN} 时站点绑定一律置空
 * （DB 口径「仅站点管理员非空」，见 4.2）。
 */
@Data
public class UpdateRolesDto {

    /** 目标角色码列表（全量替换，至少一个） */
    @NotEmpty(message = "至少选择一个角色")
    private List<String> roles;

    /** 站点绑定：授予 STATION_ADMIN 且账号尚未绑定站点时必填；也可用于同请求换绑 */
    private Long stationId;
}
