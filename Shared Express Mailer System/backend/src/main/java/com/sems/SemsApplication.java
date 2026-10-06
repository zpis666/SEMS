package com.sems;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 共享快递袋系统（Shared Express Mailer System，SEMS）后端启动类。
 *
 * <p>工程结构与分层见《系统设计说明书》2.9；配置键清单见 11.2。
 *
 * <p>关于 Mapper 扫描：设计书 2.9 写作 {@code @MapperScan("com.sems.module.*.mapper")}，
 * 但 MyBatis 的 {@code @MapperScan} 不支持通配符路径。这里改为扫描 {@code com.sems.module}
 * 下所有标注了 {@link Mapper} 的接口，语义等价且只会注册 Mapper（不会误纳 Service 接口）。
 * 新增 Mapper 时请务必带上 {@code @Mapper} 注解。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@MapperScan(basePackages = "com.sems.module", annotationClass = Mapper.class)
public class SemsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SemsApplication.class, args);
    }
}
