package com.sems.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 定时任务调度接线：把 {@code @Scheduled}（4 个定时任务 + WebSocket 心跳）接到
 * {@code jobExecutor} 的 4 线程池（见《系统设计说明书》7.5「调度使用独立线程池（4 线程）」）。
 *
 * <p><b>为什么必须显式接线</b>：{@link ThreadPoolTaskExecutor} 只是普通执行器、不是
 * {@link TaskScheduler}；若不声明 TaskScheduler Bean，Spring Boot 会回落到默认单线程调度器——
 * 4 个扫描任务与 WS 心跳（30s）串行争用同一根线程，任一慢扫描（单轮至多 500×20 条）都会把心跳
 * 无限期顺延，7.3「被禁用账号的 WS 连接在下次心跳（≤30s）断开」的安全保证随之落空。
 *
 * <p><b>任务如何真正跑在 jobExecutor 的 4 个线程上</b>：Spring 6.2 的
 * {@link ConcurrentTaskScheduler} 即便传入外部执行器，调度触发后的任务默认仍在计时线程上直接执行
 * （已用同版本 Spring 实测验证）；因此这里用 {@code TaskDecorator} 把每个触发改写为
 * 「提交给 jobExecutor 执行」，计时线程只负责触发并立即返回。任务因此实际运行在
 * {@code sems-job-*} 的 4 个线程上，与 Web 请求线程、审计落库线程分离。
 *
 * <p><b>计时载体</b>：单线程守护线程 {@code sems-sched-timer}，只做触发计时、极轻量；
 * 设为守护线程是为了不阻塞 JVM 退出——重活全部在上述 4 线程池中。
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    /** 定时任务执行器：复用 AsyncConfig 定义的 jobExecutor（Bean 名契约，见 {@link AsyncConfig}）。 */
    private final ThreadPoolTaskExecutor jobExecutor;

    public SchedulingConfig(@Qualifier("jobExecutor") ThreadPoolTaskExecutor jobExecutor) {
        this.jobExecutor = jobExecutor;
    }

    /**
     * 供 {@code @Scheduled} 使用的调度器：Bean 名取 Spring 约定名 {@code taskScheduler}
     * （ScheduledAnnotationBeanPostProcessor 按其解析默认调度器；Spring Boot 也会因已存在
     * TaskScheduler 而不再创建单线程默认调度器）。
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sems-sched-timer");
            thread.setDaemon(true);
            return thread;
        });
        ConcurrentTaskScheduler scheduler = new ConcurrentTaskScheduler(timer);
        // 触发即转交 jobExecutor：7.5 的 4 线程调度隔离由此落地。
        // 注：fixedDelay 的间隔从「任务被提交」起算；7.5 的扫描类任务单轮耗时远小于各自间隔
        // 且自带幂等（各 Job 方法自身吞异常），该口径差异不影响业务正确性。
        scheduler.setTaskDecorator(task -> () -> jobExecutor.execute(task));
        return scheduler;
    }

    /** 把调度器显式登记到注解调度注册表，不依赖 Bean 名解析（装配关系一目了然）。 */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(taskScheduler());
    }
}
