package com.sems.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.user.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色 Mapper，对应表 {@code sys_role}（见《系统设计说明书》8.2）。
 *
 * <p>角色为三行预置数据，运行期只读不写；必须标注 {@link Mapper} 以被启动类扫描注册（见 {@code SemsApplication} 注释）。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {
}
