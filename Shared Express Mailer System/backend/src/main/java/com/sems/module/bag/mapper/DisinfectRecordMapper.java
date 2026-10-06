package com.sems.module.bag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.bag.entity.DisinfectRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消毒批次 Mapper，对应表 {@code disinfect_record}（见《系统设计说明书》8.2）。
 *
 * <p>批次头只插入不改；明细随批次同事务写入，见 {@link DisinfectDetailMapper}。必须标注 {@link Mapper} 以被启动类扫描注册。
 */
@Mapper
public interface DisinfectRecordMapper extends BaseMapper<DisinfectRecord> {
}
