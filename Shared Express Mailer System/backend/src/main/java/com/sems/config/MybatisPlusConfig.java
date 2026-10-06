package com.sems.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>两个拦截器对应设计中的两条硬约束：
 * <ul>
 *   <li><b>乐观锁</b>：{@code bag.version} / {@code bag_order.version} /
 *       {@code allocation_order.version} 承载（见设计书 3.3 与 8.2），
 *       并发条件更新 {@code WHERE version=?} 是防重复领取的最后防线；</li>
 *   <li><b>分页</b>：统一分页查询，避免各接口自行拼 LIMIT。</li>
 * </ul>
 *
 * <p>注意：拦截器顺序为「分页在后、乐观锁在前」——MyBatis-Plus 要求分页拦截器放在最后。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
