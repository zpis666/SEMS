package com.sems.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 责任记录（对应表 {@code liability_record}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>承载遗失/在途遗失/超时三类责任认定：责任主体口径为——使用中遗失与回收在途超时
 * 归申领用户；调拨在途遗失归源站（{@link #responsibleStationId} 指向源站，由平台管理员裁决）。
 * {@link #responsibleUserId} 可空表示责任待定，处置结论记于 conclusion / decided_by / decided_at。
 *
 * <p>责任找回时以 {@link #note} 追加注记而不改写结论，保持认定过程留痕（见设计书 8.2）。
 */
@Data
@TableName("liability_record")
public class LiabilityRecord {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务类型：ORDER 订单 / ALLOCATION 调拨 */
    private String bizType;

    /** 业务单号（订单号或调拨单号） */
    private String bizNo;

    /** 责任类型（遗失/在途遗失/超时） */
    private String type;

    /** 责任用户（可空=责任待定） */
    private Long responsibleUserId;

    /** 责任站点（biz_type=ALLOCATION 时指向源站） */
    private Long responsibleStationId;

    /** 处置结论（管理员裁决内容） */
    private String conclusion;

    /** 裁决人（平台管理员ID） */
    private Long decidedBy;

    /** 裁决时间 */
    private LocalDateTime decidedAt;

    /** 责任状态：PENDING 待认定 / CONFIRMED 已认定 */
    private String status;

    /** 注记（找回等追加说明，不改写既有结论） */
    private String note;

    /** 创建时间（认定动作发生） */
    private LocalDateTime createdAt;

    /** 更新时间（注记追加刷新） */
    private LocalDateTime updatedAt;
}
