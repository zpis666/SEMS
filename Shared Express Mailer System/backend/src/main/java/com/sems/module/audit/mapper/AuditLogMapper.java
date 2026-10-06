package com.sems.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.audit.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作审计日志 Mapper（表 audit_log）。
 *
 * <p>防篡改约定（《系统设计说明书》7.6）：应用层对审计表只做 INSERT 与 SELECT，
 * 业务代码不得调用本 Mapper 的 update/delete 方法——数据库账号对 audit_log 亦仅授予 INSERT、SELECT。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
}
