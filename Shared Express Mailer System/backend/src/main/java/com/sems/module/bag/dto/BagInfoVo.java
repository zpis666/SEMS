package com.sems.module.bag.dto;

import lombok.Data;

/**
 * 扫码袋信息出参（见《系统设计说明书》5.2、7.4「信息最小化（防枚举）」与契约模块版 8.3）。
 *
 * <p>最小必要集（任何登录用户可见）：{@code bagCode / spec / available / stationName}；
 * 与查询者有归属关系时（本人持该袋进行中订单、本站站点管理员、平台管理员）额外返回
 * {@code stationId / color / capacity / status / statusName / useCount / maxUseCount}。
 *
 * <p>实现上用"未赋值即不输出"表达最小化：Jackson 全局配置
 * {@code default-property-inclusion: non_null}，扩展字段留 null 即不会出现在响应里，
 * 因此无需为两种视图各建一个 VO，也避免两处字段漂移。
 */
@Data
public class BagInfoVo {

    /** 袋码（任何查询者都返回，扫码结果的主标识） */
    private String bagCode;

    /** 规格（最小必要集之一） */
    private String spec;

    /** 是否可领用：状态=空闲且未被未出库调拨单锁定（与申领前置校验同口径，见设计书 3.2 #2） */
    private boolean available;

    /** 所在站点名称（最小必要集之一） */
    private String stationName;

    // ===== 以下为归属人扩展字段：非归属查询者保持 null，序列化时自动省略 =====

    /** 所在站点 ID（扩展） */
    private Long stationId;

    /** 颜色（扩展） */
    private String color;

    /** 容量（扩展） */
    private String capacity;

    /** 状态码（扩展，8 态枚举码） */
    private String status;

    /** 状态中文名（扩展，与设计书 3.1 状态表一致） */
    private String statusName;

    /** 复用次数（扩展） */
    private Integer useCount;

    /** 复用上限（扩展） */
    private Integer maxUseCount;
}
