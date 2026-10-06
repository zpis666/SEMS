package com.sems.module.bag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.bag.entity.ScrapRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 报废记录 Mapper，对应表 {@code scrap_record}（见《系统设计说明书》8.2）。
 *
 * <p>报废凭证只插入；必须标注 {@link Mapper} 以被启动类扫描注册。
 */
@Mapper
public interface ScrapRecordMapper extends BaseMapper<ScrapRecord> {
}
