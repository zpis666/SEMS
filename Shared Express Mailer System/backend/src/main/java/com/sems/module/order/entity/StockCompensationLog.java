package com.sems.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存补偿日志（对应表 {@code stock_compensation_log}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>Redis 预扣成功（Lua 原子扣减）但申领事务落库失败时，R0 同步补偿回补库存并写本表；
 * {@link #clientRequestId} 唯一约束是防重复补偿的持久化兜底——补偿动作可重放，重复执行
 * 会被唯一键挡下（见设计书 7.2 补偿流程）。
 */
@Data
@TableName("stock_compensation_log")
public class StockCompensationLog {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申领请求幂等ID（uk 唯一，防重复补偿） */
    private String clientRequestId;

    /** 申领订单号（事务失败时该单未落库，故只存号不设外键） */
    private String orderNo;

    /** 库存回补的站点ID */
    private Long stationId;

    /** 补偿原因（如申领落库失败） */
    private String reason;

    /** 补偿发生时间（业务时刻，非记录写入时间） */
    private LocalDateTime compensatedAt;

    /** 记录写入时间 */
    private LocalDateTime createdAt;
}
