package com.sems.module.bag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 维修登记入参（见《系统设计说明书》5.4「POST /bags/{bagCode}/repair」、3.2 #12）。
 *
 * <p>破损描述必填（需求 FR-M4-12：缺失报 3001）；描述同时写入
 * {@code repair_record.description}（流转动作内落库，见设计书 3.2 #12 伴随动作）。
 */
@Data
public class RepairDto {

    /** 破损描述（必填） */
    @NotBlank(message = "破损描述不能为空")
    @Size(max = 255, message = "破损描述长度不能超过 255")
    private String description;
}
