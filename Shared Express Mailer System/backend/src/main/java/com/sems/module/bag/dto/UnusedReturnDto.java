package com.sems.module.bag.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 未使用回收入参（见《系统设计说明书》5.4「POST /bags/{bagCode}/unused-return」、3.2 #4）。
 *
 * <p>「曾寄出」补救标注不属于本接口：该标注按 #5 路径转待消毒并计复用次数，
 * 由站点管理员的回收确认接口（POST /stations/{id}/recycle-confirm）承载（见 3.2 表后说明）。
 */
@Data
public class UnusedReturnDto {

    /** 备注（可选，写入流转日志） */
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;
}
