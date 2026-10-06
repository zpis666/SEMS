package com.sems.module.bag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.bag.entity.StockRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 出入库流水 Mapper，对应表 {@code stock_record}（见《系统设计说明书》8.2）。
 *
 * <p>流水只插入；必须标注 {@link Mapper} 以被启动类扫描注册。
 */
@Mapper
public interface StockRecordMapper extends BaseMapper<StockRecord> {
}
