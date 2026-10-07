package com.sems.config;

import com.sems.push.SemsWsHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点配置：注册 {@code /ws}（见《系统设计说明书》7.3）。
 *
 * <p>跨域策略：开发环境前端跑在 Vite 开发服务器（localhost 或 127.0.0.1 的 5173 端口，见 12 前端契约），
 * 与后端不同端口，属跨域，需显式放行；生产环境前端由 Nginx 同源反向代理，
 * 不给任何跨域来源，保持默认的同源校验即可。
 * 判断用 {@link Environment} 的激活 profile（仅 dev 放开本机前端地址），
 * 而不是写死开关，避免把开发便利带到生产。
 *
 * <p>注意：{@code /ws} 已在认证拦截器白名单内（首帧由 WebSocket 层鉴权，见 4.5 与 7.3），
 * 本配置只负责端点注册与来源约束。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** 前端开发地址（Vite 默认端口，与 sems.qrcode.domain 的 dev 值一致） */
    private static final String[] DEV_FRONTEND_ORIGINS = {
            "http://localhost:5173",
            "http://127.0.0.1:5173"
    };

    private final SemsWsHandler semsWsHandler;
    private final Environment environment;

    public WebSocketConfig(SemsWsHandler semsWsHandler, Environment environment) {
        this.semsWsHandler = semsWsHandler;
        this.environment = environment;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(semsWsHandler, "/ws")
                .setAllowedOrigins(allowedOrigins());
    }

    /** dev 放开本机前端开发地址；非 dev 返回空集合，仅允许同源连接。 */
    private String[] allowedOrigins() {
        return environment.acceptsProfiles(Profiles.of("dev"))
                ? DEV_FRONTEND_ORIGINS
                : new String[0];
    }
}
