package com.sems.module.station.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.station.entity.Station;
import org.apache.ibatis.annotations.Mapper;

/**
 * 共享站点 Mapper（对应表 {@code station}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>仅继承 MyBatis-Plus 通用 CRUD，不声明自定义方法：条件查询经 Service 层用
 * LambdaWrapper 以预编译参数构造，杜绝字符串拼接 SQL（见设计书 9.4 注入防护）。
 */
@Mapper
public interface StationMapper extends BaseMapper<Station> {
}
