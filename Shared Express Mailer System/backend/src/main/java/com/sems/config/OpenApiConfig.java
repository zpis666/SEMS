package com.sems.config;

import com.sems.common.SemsVersion;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI（接口文档）配置，落实《系统设计说明书》6.5「后端集成 springdoc-openapi 自动生成接口文档」。
 *
 * <p>只定义文档元信息与 Bearer(JWT) 安全方案；文档的开放/关闭由
 * {@code application-dev.yml / application-prod.yml} 的 springdoc 开关控制（生产关闭暴露）。
 * 任务书要求的「接口变更同步维护接口文档」由本自动文档 + Apifox 导入流程承接。
 */
@Configuration
public class OpenApiConfig {

    /** 安全方案名：请求头 {@code Authorization: Bearer <token>}（头名与前缀见契约 3.2）。 */
    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI semsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("共享快递袋系统（SEMS）接口文档")
                        .version(SemsVersion.VERSION)
                        .description("Shared Express Mailer System：共享快递袋「取—用—还—洗—调」循环管理后端接口。"
                                + "统一响应体为 { code, message, data }，错误码与 HTTP 状态映射见《系统设计说明书》6.4/6.6。"
                                + "匿名接口（/api/health 与 /api/v1/auth 下的 captcha、register、login）无需 JWT。"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, new SecurityScheme()
                                .name(BEARER_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 data.token；在 Swagger UI 点 Authorize 填写时无需带 Bearer 前缀。")))
                // 全局默认要求携带 JWT，使各接口在文档上直观体现登录要求；
                // 匿名接口服务端仍按白名单放行，此声明不影响实际调用
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }
}
