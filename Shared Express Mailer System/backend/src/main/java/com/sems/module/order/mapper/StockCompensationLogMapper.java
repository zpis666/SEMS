package com.sems.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.order.entity.StockCompensationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存补偿日志 Mapper（对应表 {@code stock_compensation_log}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>仅继承 MyBatis-Plus 通用 CRUD，不声明自定义方法：补偿写入依唯一键
 * client_request_id 去重，插入冲突即视为已补偿（见设计书 7.2）。
 */
@Mapper
public interface StockCompensationLogMapper extends BaseMapper<StockCompensationLog> {
}
