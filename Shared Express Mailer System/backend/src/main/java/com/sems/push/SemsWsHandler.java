package com.sems.push;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.common.util.JsonUtils;
import com.sems.common.util.MaskUtils;
import com.sems.module.audit.entity.AuditLogEntity;
import com.sems.module.audit.service.AuditLogService;
import com.sems.security.JwtService;
import com.sems.security.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebSocket 端点处理器（{@code /ws}，见《系统设计说明书》7.3）。
 *
 * <p>协议流程：
 * <ol>
 *   <li><b>首帧鉴权</b>：连接后 10 秒内发送 {@code {"type":"auth","token":"<JWT>"}}；
 *       服务端校验 JWT（签名/有效期）并即时复查吊销状态（jti 黑名单 + token_version + 账号状态），
 *       通过回 {@code auth_ok}，失败回 {@code auth_fail} 并断开；</li>
 *   <li><b>订阅</b>：鉴权后发送 {@code {"type":"subscribe","topic":"..."}}，主题必须落在
 *       {@link WsSessionManager#canSubscribe} 给出的白名单内，越权订阅拒绝并写审计（7.6）；</li>
 *   <li><b>心跳</b>：服务端每 30 秒发 ping；客户端可回 pong（不强制），亦可主动发 ping 换 pong。</li>
 * </ol>
 *
 * <p>本处理器不承载业务逻辑：校验通过后的推送由 {@link PushService} 经会话管理器广播。
 */
@Component
public class SemsWsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SemsWsHandler.class);

    /** 越权订阅审计的模块名（审计查询可按模块筛选，见 5.6） */
    private static final String AUDIT_MODULE = "实时推送";

    /** 越权订阅审计动作；含「越权」关键字，队列饱和时按关键事件同步落库（7.6） */
    private static final String AUDIT_ACTION_SUBSCRIBE_DENIED = "越权订阅";

    /** 审计 URI：WebSocket 握手路径 */
    private static final String AUDIT_URI = "/ws";

    /** 与 audit_log 列宽一致的截断上限 */
    private static final int ROLE_MAX = 20;
    private static final int IP_MAX = 45;
    private static final int UA_MAX = 255;
    private static final int PARAMS_MAX = 4096;

    private final WsSessionManager sessionManager;
    private final JwtService jwtService;
    private final AuditLogService auditLogService;

    public SemsWsHandler(WsSessionManager sessionManager, JwtService jwtService, AuditLogService auditLogService) {
        this.sessionManager = sessionManager;
        this.jwtService = jwtService;
        this.auditLogService = auditLogService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 只登记，不信任连接：10 秒内未通过首帧鉴权由会话管理器断开
        sessionManager.register(session);
        log.debug("WebSocket 连接建立，等待首帧鉴权：sessionId={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientFrame frame;
        try {
            frame = JsonUtils.parse(message.getPayload(), ClientFrame.class);
        } catch (RuntimeException e) {
            // 首帧前尚无任何可信身份，格式错误直接断开；已鉴权会话仅回错误帧
            if (!sessionManager.isAuthenticated(session)) {
                sessionManager.closeQuietly(session, "消息格式不合法");
            } else {
                send(session, WsMessage.of(null, WsMessage.TYPE_ERROR, "消息格式不合法"));
            }
            return;
        }

        String type = frame == null ? null : frame.getType();

        if (!sessionManager.isAuthenticated(session)) {
            if (!WsMessage.TYPE_AUTH.equals(type)) {
                log.warn("WebSocket 首帧非鉴权帧，断开：sessionId={}, type={}", session.getId(), type);
                sessionManager.closeQuietly(session, "首帧必须为鉴权帧");
                return;
            }
            handleAuth(session, frame);
            return;
        }

        switch (type == null ? "" : type) {
            case WsMessage.TYPE_SUBSCRIBE -> handleSubscribe(session, frame.getTopic());
            case WsMessage.TYPE_UNSUBSCRIBE -> {
                sessionManager.unsubscribe(session, frame.getTopic());
                send(session, WsMessage.of(frame.getTopic(), WsMessage.TYPE_UNSUBSCRIBED, null));
            }
            case WsMessage.TYPE_PING -> send(session, WsMessage.of(null, WsMessage.TYPE_PONG, null));
            case WsMessage.TYPE_PONG -> {
                // 心跳应答：连接存活即可，无需额外处理
            }
            default -> send(session, WsMessage.of(null, WsMessage.TYPE_ERROR, "不支持的消息类型"));
        }
    }

    /** 首帧鉴权：解析 JWT 并即时复查吊销状态，全部通过才允许订阅。 */
    private void handleAuth(WebSocketSession session, ClientFrame frame) {
        String token = frame.getToken();
        if (token == null || token.isBlank()) {
            log.warn("WebSocket 首帧鉴权缺少 token，断开：sessionId={}", session.getId());
            send(session, WsMessage.of(null, WsMessage.TYPE_AUTH_FAIL, "缺少 token"));
            sessionManager.closeQuietly(session, "鉴权失败");
            return;
        }
        LoginUser user;
        try {
            user = jwtService.parse(token);
        } catch (BizException e) {
            log.warn("WebSocket 首帧鉴权失败，断开：sessionId={}, code={}", session.getId(), e.getErrorCode().getCode());
            send(session, WsMessage.of(null, WsMessage.TYPE_AUTH_FAIL, "令牌无效或已过期"));
            sessionManager.closeQuietly(session, "鉴权失败");
            return;
        }
        // 与心跳同口径的即时复查：黑名单/版本/账号状态任一失效都拒绝（7.3）
        if (sessionManager.isTokenRevoked(user)) {
            log.warn("WebSocket 首帧鉴权命中已失效令牌，断开：sessionId={}, uid={}", session.getId(), user.getUserId());
            send(session, WsMessage.of(null, WsMessage.TYPE_AUTH_FAIL, "登录状态已失效"));
            sessionManager.closeQuietly(session, "鉴权失败");
            return;
        }
        sessionManager.markAuthenticated(session, user, token);
        send(session, WsMessage.of(null, WsMessage.TYPE_AUTH_OK, null));
        log.info("WebSocket 首帧鉴权通过：sessionId={}, uid={}, role={}", session.getId(), user.getUserId(), user.getRole());
    }

    /** 订阅：白名单校验不通过即拒绝并审计（越权订阅属强制入审计事件）。 */
    private void handleSubscribe(WebSocketSession session, String topic) {
        LoginUser user = sessionManager.currentUser(session);
        if (topic == null || topic.isBlank() || !WsSessionManager.canSubscribe(user, topic)) {
            recordSubscriptionViolation(session, user, topic);
            send(session, WsMessage.of(null, WsMessage.TYPE_ERROR, "无权订阅该主题"));
            return;
        }
        sessionManager.subscribe(session, topic);
        send(session, WsMessage.of(topic, WsMessage.TYPE_SUBSCRIBED, null));
        log.debug("WebSocket 订阅成功：sessionId={}, uid={}, topic={}", session.getId(),
                user == null ? null : user.getUserId(), topic);
    }

    /**
     * 越权订阅审计：WS 通道无 HTTP 请求上下文，IP/UA 取自握手请求头；
     * 订阅非本站/非本人主题属水平越权，错误码归 2002（见 6.4 与 7.6）。
     */
    private void recordSubscriptionViolation(WebSocketSession session, LoginUser user, String topic) {
        try {
            AuditLogEntity entity = new AuditLogEntity();
            entity.setOperatorId(user == null || user.getUserId() == null ? 0L : user.getUserId());
            entity.setOperatorRole(user == null || user.getRole() == null
                    ? "" : MaskUtils.truncate(user.getRole(), ROLE_MAX));
            entity.setIp(MaskUtils.truncate(resolveClientIp(session), IP_MAX));
            entity.setUa(MaskUtils.truncate(resolveUserAgent(session), UA_MAX));
            entity.setModule(AUDIT_MODULE);
            entity.setAction(AUDIT_ACTION_SUBSCRIBE_DENIED);
            entity.setRequestUri(AUDIT_URI);
            entity.setParamsMasked(MaskUtils.truncate(paramsJson(topic), PARAMS_MAX));
            entity.setResult(0);
            entity.setErrorCode(ErrorCode.NO_DATA_PERMISSION.getCode());
            entity.setCostMs(0);
            auditLogService.record(entity);
        } catch (Exception e) {
            // 审计不得影响拒绝动作本身
            log.error("越权订阅审计写入失败（已忽略）：sessionId={}", session.getId(), e);
        }
        log.warn("WebSocket 越权订阅已拒绝：sessionId={}, uid={}, topic={}", session.getId(),
                user == null ? null : user.getUserId(), topic);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionManager.remove(session);
        log.debug("WebSocket 连接关闭：sessionId={}, code={}, reason={}", session.getId(), status.getCode(), status.getReason());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket 传输异常，断开连接：sessionId={}, reason={}", session.getId(), exception.getMessage());
        sessionManager.closeQuietly(session, "传输异常");
    }

    /** 发送服务端帧；序列化或发送失败只记日志（不得因单个会话异常影响其它连接）。 */
    private void send(WebSocketSession session, WsMessage message) {
        try {
            sessionManager.sendText(session, JsonUtils.toJson(message));
        } catch (Exception e) {
            log.warn("WebSocket 帧发送失败：sessionId={}, reason={}", session.getId(), e.getMessage());
        }
    }

    /** 取握手请求中的客户端 IP：反代场景优先 X-Forwarded-For 首个地址，回退连接地址。 */
    private static String resolveClientIp(WebSocketSession session) {
        String forwarded = session.getHandshakeHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        if (session.getRemoteAddress() != null && session.getRemoteAddress().getAddress() != null) {
            return session.getRemoteAddress().getAddress().getHostAddress();
        }
        return "";
    }

    private static String resolveUserAgent(WebSocketSession session) {
        String ua = session.getHandshakeHeaders().getFirst("User-Agent");
        return ua == null ? "" : ua;
    }

    private static String paramsJson(String topic) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("topic", topic);
            return JsonUtils.toJson(params);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 客户端帧：仅取服务端关心的三个字段。
     * {@code ignoreUnknown} 保证前端附加字段（如重连次数）不会导致帧解析失败。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClientFrame {

        /** 帧类型（auth/subscribe/unsubscribe/ping/pong） */
        private String type;

        /** 首帧鉴权令牌（仅 auth 帧使用） */
        private String token;

        /** 订阅/退订主题（仅 subscribe/unsubscribe 帧使用） */
        private String topic;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }
    }
}
