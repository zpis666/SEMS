package com.sems.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码编码器配置（见《系统设计说明书》9.2「密码策略」与 5.1「密码 BCrypt 加密存储（cost≥10）」）。
 *
 * <p>为什么单独建本配置类：本工程只引入 {@code spring-security-crypto} 依赖
 * （不含 spring-boot-starter-security，不会带来默认过滤器链与默认登录页），
 * 而该依赖不会自动注册 {@link BCryptPasswordEncoder}，故需显式声明为 Bean，
 * 供认证模块（注册加密、登录比对）注入使用。
 *
 * <p>强度取 10（BCrypt 的 log rounds）：设计书 9.2 要求 cost ≥ 10；
 * cost 每 +1 计算耗时翻倍，10 是安全与吞吐的折中。存量摘要自带其 cost 参数，
 * 后续若上调本值，旧摘要仍可正常验证（验证时按摘要内的 cost 计算）。
 */
@Configuration
public class PasswordEncoderConfig {

    /** BCrypt 强度（log rounds），设计书 9.2 规定 cost ≥ 10。 */
    private static final int BCRYPT_COST = 10;

    /**
     * 全站唯一的密码编码器：注册/创建账号时 {@code encode}，
     * 登录/改密时 {@code matches}；禁止业务代码自行 new（保证参数口径唯一）。
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }
}
