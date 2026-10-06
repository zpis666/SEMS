package com.sems.module.station.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存盘点单（对应表 {@code inventory_check}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>盘点单据不可删除：状态只在 PENDING_FIX 待修正 / CLOSED 已闭环 / VOID 作废 之间流转并留痕。
 * 差异修正按设计书 3.2 的 #21（STATUS_CORRECT）口径执行，盘点单号经
 * {@code bag_transfer_log.ref_check_no} 与流转日志关联，形成「盘点 → 修正 → 追溯」闭环。
 *
 * <p>三个 JSON 列一律以 String 承载：MySQL 的 JSON 类型经 JDBC 读写即字符串，
 * 业务层用 {@code com.sems.common.util.JsonUtils} 做对象转换（见实现契约 3.7）。
 */
@Data
@TableName("inventory_check")
public class InventoryCheck {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 盘点单号（全局唯一，BizNoUtils.checkNo() 生成；bag_transfer_log.ref_check_no 引用之） */
    private String checkNo;

    /** 盘点站点ID */
    private Long stationId;

    /** 实盘执行时间（业务时间，非记录写入时间） */
    private LocalDateTime checkTime;

    /** 账面各状态袋数快照（JSON）；MySQL JSON 列以字符串读写，业务层用 JsonUtils 转换 */
    private String expectJson;

    /** 实盘各状态袋数快照（JSON）；MySQL JSON 列以字符串读写，业务层用 JsonUtils 转换 */
    private String actualJson;

    /** 袋级差异清单 [{bagCode,diffType}]（JSON）；MySQL JSON 列以字符串读写，业务层用 JsonUtils 转换 */
    private String diffBagsJson;

    /** 差异袋数（差异清单条数） */
    private Integer diffCount;

    /** 状态：PENDING_FIX待修正 / CLOSED已闭环 / VOID作废 */
    private String status;

    /** 盘点人（提交账号）ID */
    private Long operatorId;

    /** 闭环时间（差异修正完毕） */
    private LocalDateTime closedAt;

    /** 作废时间 */
    private LocalDateTime voidedAt;

    /** 作废操作人ID */
    private Long voidedBy;

    /** 作废原因（重盘说明） */
    private String voidReason;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（自动刷新） */
    private LocalDateTime updatedAt;
}
