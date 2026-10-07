package com.sems.module.bag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 快递袋档案实体，对应表 {@code bag}（表概要与设计出处见《系统设计说明书》8.2 与 3.1；
 * 字段级定义见《数据库设计说明书》V1.1 第 4 章，与 {@code deploy/sql/V1__init_schema.sql} 逐列一致）。
 *
 * <p>全生命周期底账：{@code status} 为 8 态枚举码（初始 IDLE）；
 * {@code station_id} 随实物转移，仅 RECOVER_UNUSED/RECOVER_CONFIRM/RECOVER_DIRECT/ALLOCATE_IN/FOUND/STATUS_CORRECT
 * 六个事件可改写（见设计书 3.2 站点归属变更规则）；{@code use_count} 唯一递增点是回收确认。
 *
 * <p>{@code version} 由 MyBatis-Plus 乐观锁拦截器维护（{@link Version}），条件 UPDATE 是并发防重复领取的最后防线
 * （见设计书 3.3 与 8.2），因此禁止绕过实体直接用字符串拼 UPDATE。
 *
 * <p>状态字段用 {@code String} 而非枚举：与 DDL 的 {@code varchar} 码值一致，业务层用 {@code BagStatus} 转换
 * （{@code BagStatus.of(code)}），既保留库内可读性，又避免 ORM 枚举映射与码值两份口径。
 */
@Data
@TableName("bag")
public class Bag {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 袋编码：SJB-规格-年月-流水-校验（唯一，印在实物上） */
    private String bagCode;

    /** 规格（1 位码值嵌入袋码，如 L） */
    private String spec;

    /** 颜色（如 黄色） */
    private String color;

    /** 容量（如 33×25×5cm） */
    private String capacity;

    /** 当前存放站点（随实物转移；建袋写登记站） */
    private Long stationId;

    /** 袋状态（8 态枚举码，初始 IDLE） */
    private String status;

    /** 复用次数（唯一递增点：回收确认） */
    private Integer useCount;

    /** 复用上限（默认 50，超限拒绝上架，须转维修/报废） */
    private Integer maxUseCount;

    /** 乐观锁版本（条件 UPDATE 防重领，由乐观锁拦截器自动维护） */
    @Version
    private Integer version;

    /** 建档时间 */
    private LocalDateTime createdAt;

    /** 更新时间（库内 ON UPDATE 自动刷新，业务侧只读） */
    private LocalDateTime updatedAt;
}
