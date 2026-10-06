package com.sems.module.order.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 遗失确认入参（见《系统设计说明书》3.2 #18「确认遗失」与 8.2 {@code liability_record} 字段口径）。
 *
 * <p>责任认定口径（设计书 3.2 表后说明）：使用中遗失/回收在途超时 → 责任主体为申领用户；
 * 平台管理员在确认时填写处置结论。{@code responsibleUserId} 可空 = 责任待定；
 * 结论、裁决人（当前管理员）、裁决时间由服务端写入责任记录，不取前端传值。
 */
@Data
public class LostConfirmDto {

    /** 处置结论（管理员裁决内容，≤255 字符，与 liability_record.conclusion 列宽一致） */
    @Size(max = 255, message = "处置结论长度不能超过 255 位")
    private String conclusion;

    /** 责任用户ID（可空 = 责任待定；不填时不向库中写责任用户） */
    private Long responsibleUserId;

    /** 注记（补充说明，写 liability_record.note） */
    @Size(max = 255, message = "注记长度不能超过 255 位")
    private String note;
}
