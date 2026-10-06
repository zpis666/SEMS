package com.sems.module.bag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.bag.entity.BagTransferLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 流转日志 Mapper，对应表 {@code bag_transfer_log}（见《系统设计说明书》8.2 与 3.3）。
 *
 * <p>日志只插入不更新不删除；必须标注 {@link Mapper} 以被启动类扫描注册。
 */
@Mapper
public interface BagTransferLogMapper extends BaseMapper<BagTransferLog> {
}
