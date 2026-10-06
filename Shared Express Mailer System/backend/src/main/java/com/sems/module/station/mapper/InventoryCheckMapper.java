package com.sems.module.station.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.station.entity.InventoryCheck;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存盘点单 Mapper（对应表 {@code inventory_check}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>仅继承 MyBatis-Plus 通用 CRUD，不声明自定义方法：盘点单据只插不改删（状态作废），
 * 查询经 Service 层以预编译参数构造（见设计书 8.2 与 9.4）。
 */
@Mapper
public interface InventoryCheckMapper extends BaseMapper<InventoryCheck> {
}
