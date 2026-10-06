package com.sems.module.bag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.bag.entity.RepairRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 维修记录 Mapper，对应表 {@code repair_record}（见《系统设计说明书》8.2）。
 *
 * <p>登记/完成两阶段对同一行更新；必须标注 {@link Mapper} 以被启动类扫描注册。
 */
@Mapper
public interface RepairRecordMapper extends BaseMapper<RepairRecord> {
}
