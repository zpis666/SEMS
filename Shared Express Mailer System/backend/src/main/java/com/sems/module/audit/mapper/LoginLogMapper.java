package com.sems.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.audit.entity.LoginLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录日志 Mapper（表 login_log）。
 *
 * <p>登录成功/失败均只插入；与 audit_log 同样受「应用层只插不改」的防篡改约束（《系统设计说明书》7.6）。
 */
@Mapper
public interface LoginLogMapper extends BaseMapper<LoginLog> {
}
