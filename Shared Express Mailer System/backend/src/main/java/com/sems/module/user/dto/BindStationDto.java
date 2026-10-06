package com.sems.module.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 调整站点绑定入参（见《系统设计说明书》5.1 {@code PUT /api/v1/users/{id}/station}，FR-M1-13）。
 *
 * <p>站点管理员的 {@code stationId} 由此写入并随 JWT 携带（4.2）；换绑后 Service 会自增
 * {@code token_version} 使旧令牌立即失效（7.1）。目标账号须已具备 {@code STATION_ADMIN} 角色
 * （一岗一站，角色未授予时绑定无意义）。
 */
@Data
public class BindStationDto {

    /** 目标站点 ID；仅站点管理员可绑定，站点须存在 */
    @NotNull(message = "站点ID不能为空")
    private Long stationId;
}
