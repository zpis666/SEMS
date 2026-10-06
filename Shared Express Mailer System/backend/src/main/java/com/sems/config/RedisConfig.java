package com.sems.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置。
 *
 * <p>键空间规划见《系统设计说明书》8.3 与《数据库设计说明书》第 6 章：
 * 库存计数、预扣凭据、各类限流与幂等键均以字符串键 + 数字/字符串值存在，
 * 因此键一律用 {@link StringRedisSerializer}（保证 Redis 里看到的键可读、可用 Lua 拼接），
 * 值用 JSON 序列化以支持对象缓存。
 *
 * <p>注意：7.2 的库存原子扣减走 {@code StringRedisTemplate} 或原生
 * {@code RedisConnection.eval}（Lua 脚本），不走本模板；本模板用于一般对象缓存。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
