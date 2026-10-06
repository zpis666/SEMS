package com.sems.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户账号 Mapper，对应表 {@code sys_user}（见《系统设计说明书》8.2）。
 *
 * <p>本期只做单表 CRUD，一律用 {@link BaseMapper} 与 Wrapper 的预编译参数，禁止字符串拼接 SQL（见设计书 9.4）。
 * 必须标注 {@link Mapper}：启动类按「{@code com.sems.module} 包 + @Mapper 注解」扫描注册（见 {@code SemsApplication} 注释）。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 令牌版本自增 1，实现用户级「全量吊销」（见《系统设计说明书》7.1「两级主动失效」第 2 条）。
     *
     * <p>调用时机：角色变更、站点绑定变更、禁用、重置密码、本人改密后；调用方随后必须
     * 删除 Redis 读缓存 {@code auth:userver:{uid}}（仅 60s 读加速，权威在 DB）。
     *
     * <p>用「自增」而非「读值后写回」：并发下两次吊销不会互相覆盖，旧令牌的版本必然落后于库内值；
     * 语句为固定 SQL，仅 {@code #{id}} 一个预编译参数，符合 9.4 注入防护要求。
     *
     * @param id 目标用户 ID
     * @return 影响行数（0 表示该用户不存在）
     */
    @Update("UPDATE sys_user SET token_version = token_version + 1 WHERE id = #{id}")
    int incrementTokenVersion(@Param("id") Long id);
}
