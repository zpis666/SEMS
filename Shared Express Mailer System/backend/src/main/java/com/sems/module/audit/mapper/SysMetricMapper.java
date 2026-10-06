package com.sems.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.audit.entity.SysMetric;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 系统指标 Mapper（表 sys_metric）。
 *
 * <p>累加写入采用 {@code INSERT ... ON DUPLICATE KEY UPDATE} 单条原子语句：
 * 依赖唯一键 {@code (metric_key, stat_date)} 把并发累加交给数据库行锁完成，
 * 避免「先查后改」在并发下丢失计数（审计队列丢弃计数必须可信，见 7.6）。
 */
@Mapper
public interface SysMetricMapper extends BaseMapper<SysMetric> {

    /**
     * 按「指标键 + 日期」原子累加 1：不存在则插入 1，已存在则原值加 1。
     *
     * <p>全程预编译参数（{@code #{}}），不使用字符串拼接（见 9.4 注入防护）。
     *
     * @param metricKey 指标键，如 audit_drop_count
     * @param statDate  统计日期（按天累计）
     * @return 受影响行数
     */
    @Insert("INSERT INTO sys_metric (metric_key, stat_date, metric_value) "
            + "VALUES (#{metricKey}, #{statDate}, 1) "
            + "ON DUPLICATE KEY UPDATE metric_value = metric_value + 1")
    int increment(@Param("metricKey") String metricKey, @Param("statDate") LocalDate statDate);
}
