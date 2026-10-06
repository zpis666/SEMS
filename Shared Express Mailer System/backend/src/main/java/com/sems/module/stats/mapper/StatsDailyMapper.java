package com.sems.module.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.stats.entity.StatsDaily;
import org.apache.ibatis.annotations.Mapper;

/**
 * 日统计汇总 Mapper（对应表 {@code stats_daily}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>仅继承 MyBatis-Plus 通用 CRUD，不声明自定义方法：按日期区间查询由 Service 层
 * 以 LambdaWrapper 预编译构造；同日重算按唯一键 (date, station_id) 覆盖更新。
 */
@Mapper
public interface StatsDailyMapper extends BaseMapper<StatsDaily> {
}
