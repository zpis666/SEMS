package com.sems.module.station.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.station.entity.AllocationOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站点间调拨单 Mapper（对应表 {@code allocation_order}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>仅继承 MyBatis-Plus 通用 CRUD，不声明自定义方法：乐观锁更新由实体上的 @Version
 * 配合全局 OptimisticLockerInnerInterceptor 生效，无需在此写 SQL（见设计书 8.2）。
 */
@Mapper
public interface AllocationOrderMapper extends BaseMapper<AllocationOrder> {
}
