package com.sems.module.bag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.bag.entity.DisinfectDetail;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消毒批次明细 Mapper，对应表 {@code disinfect_detail}（见《系统设计说明书》8.2）。
 *
 * <p>{@code (disinfect_id, bag_code)} 唯一约束是逐袋幂等的数据库兜底；必须标注 {@link Mapper} 以被启动类扫描注册。
 */
@Mapper
public interface DisinfectDetailMapper extends BaseMapper<DisinfectDetail> {
}
