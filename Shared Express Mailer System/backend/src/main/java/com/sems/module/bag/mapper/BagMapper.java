package com.sems.module.bag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.bag.entity.Bag;
import org.apache.ibatis.annotations.Mapper;

/**
 * 快递袋档案 Mapper，对应表 {@code bag}（见《系统设计说明书》8.2）。
 *
 * <p>乐观锁更新（条件 UPDATE {@code WHERE version=?}）由乐观锁拦截器基于实体 {@code @Version} 字段完成，
 * 禁止在此手写字符串拼接 SQL（见设计书 9.4）。必须标注 {@link Mapper} 以被启动类扫描注册。
 */
@Mapper
public interface BagMapper extends BaseMapper<Bag> {
}
