package com.sems.module.bag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 遗失找回入参（见《系统设计说明书》5.4「POST /bags/{bagCode}/lost-recovery」、3.2 #20）。
 *
 * <p>找回说明与找回站点均必填（设计书 3.2 #20 前置校验「登记找回说明与找回站点」）：
 * 找回站点写入 {@code bag.station_id}（站点归属变更规则见 3.2 表后说明），
 * 袋转入待消毒后重新走消毒—上架流程回流循环；不重复计复用次数。
 */
@Data
public class LostRecoveryDto {

    /** 找回说明（必填） */
    @NotBlank(message = "找回说明不能为空")
    @Size(max = 255, message = "找回说明长度不能超过 255")
    private String remark;

    /** 找回站点 ID（必填：登记找回站点，并作为袋的新归属站点） */
    @NotNull(message = "找回站点不能为空")
    private Long stationId;
}
