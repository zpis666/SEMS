package com.sems.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置（见《系统设计说明书》7.5 定时任务、7.6 审计异步落库）。
 *
 * <p>两个池子职责分离、互不挤占，且都用有界队列（设计书 9.1 拒绝服务对策：异步链路不得无界堆积）：
 * <ul>
 *   <li>{@code auditExecutor}：审计日志异步落库（7.6）。队列容量放大到 2000 以吸纳审计洪峰；
 *       饱和策略选 {@link ThreadPoolExecutor.CallerRunsPolicy}，队列打满时由调用线程亲自执行，
 *       宁可让当次请求变慢也不静默丢弃审计记录（关键事件的降级改写由提交方按上下文处理）；</li>
 *   <li>{@code jobExecutor}：定时任务专用（7.5 要求独立线程池、不占用 Web 请求线程），固定 4 线程；
 *       饱和策略选 {@link ThreadPoolExecutor.AbortPolicy}，任务来不及消化时明确拒绝并由调度侧记 WARN，
 *       避免无限排队把故障推迟到更晚才暴露。</li>
 * </ul>
 *
 * <p>两个 Bean 均按名称注入使用（{@code @Qualifier("auditExecutor")} / {@code @Qualifier("jobExecutor")}），
 * 提交方自行处理各自的饱和语义。
 *
 * <p>注意：不在此处手工调用 {@code initialize()}，容器会把 {@link ThreadPoolTaskExecutor}
 * 按 {@code InitializingBean} 生命周期初始化；手工调用会造成重复初始化。
 */
@Configuration
public class AsyncConfig {

    /** 审计落库线程池 Bean 名（契约冻结，提交方按名注入）。 */
    private static final String AUDIT_EXECUTOR = "auditExecutor";
    /** 定时任务线程池 Bean 名（契约冻结，提交方按名注入）。 */
    private static final String JOB_EXECUTOR = "jobExecutor";

    /**
     * 审计日志异步落库线程池（见设计书 7.6）。
     *
     * <p>关机时等待队列消化（最多 30 秒）：审计数据要求可追溯，不能随进程退出静默丢失。
     */
    @Bean(AUDIT_EXECUTOR)
    public ThreadPoolTaskExecutor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("sems-audit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    /**
     * 定时任务线程池（见设计书 7.5：调度独立线程池 4 线程）。
     *
     * <p>核心与最大均为 4，保持线程数稳定；任务均为幂等的扫描类作业，关机不等待在途任务。
     *
     * <p><b>接线</b>：{@code @Scheduled} 的任务执行由 {@link SchedulingConfig} 转交本池——
     * 4 个定时任务与 WS 心跳实际运行在 {@code sems-job-*} 这 4 个线程上（7.5 的调度隔离即本 Bean）。
     */
    @Bean(JOB_EXECUTOR)
    public ThreadPoolTaskExecutor jobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("sems-job-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
