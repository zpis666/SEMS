package com.sems.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.order.entity.LiabilityRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 责任记录 Mapper（对应表 {@code liability_record}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>仅继承 MyBatis-Plus 通用 CRUD，不声明自定义方法：按业务单号+责任类型查询
 * 由 Service 层以 LambdaWrapper 预编译构造（见设计书 9.4）。
 */
@Mapper
public interface LiabilityRecordMapper extends BaseMapper<LiabilityRecord> {
}
