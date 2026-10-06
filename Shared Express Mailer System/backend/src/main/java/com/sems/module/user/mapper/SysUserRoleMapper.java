package com.sems.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.user.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户角色关联 Mapper，对应表 {@code sys_user_role}（见《系统设计说明书》8.2）。
 *
 * <p>注册用：按 {@code user_id} 查关联行组出用户角色；必须标注 {@link Mapper} 以被启动类扫描注册。
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {
}
