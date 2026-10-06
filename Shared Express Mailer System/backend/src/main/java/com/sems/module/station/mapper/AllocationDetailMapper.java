package com.sems.module.station.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.station.entity.AllocationDetail;
import org.apache.ibatis.annotations.Mapper;

/**
 * 调拨单袋级明细 Mapper（对应表 {@code allocation_detail}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>仅继承 MyBatis-Plus 通用 CRUD，不声明自定义方法：袋级状态推进与按单查询
 * 由 Service 层用 LambdaWrapper 预编译构造（见设计书 9.4）。
 */
@Mapper
public interface AllocationDetailMapper extends BaseMapper<AllocationDetail> {
}
