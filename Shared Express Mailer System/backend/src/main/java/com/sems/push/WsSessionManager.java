package com.sems.push;

import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.util.JsonUtils;
import com.sems.infra.RedisKeys;
import com.sems.module.user.entity.SysUser;
import com.sems.module.user.mapper.SysUserMapper;
import com.sems.security.JwtService;
import com.sems.security.LoginUser;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket 会话管理：订阅白名单、主题索引、推送广播、心跳复查与会话失效处置
 * （见《系统设计说明书》7.3）。
 *
 * <p>关键设计：
 * <ul>
 *   <li><b>首帧鉴权</b>：连接建立后 10 秒内必须发送 {@code {"type":"auth","token":"<JWT>"}}，
 *       到点未鉴权的会话由独立调度器精确断开（不依赖 30 秒心跳节拍）；</li>
 *   <li><b>订阅白名单</b>：服务端按 JWT 声明生成（{@link #canSubscribe}），
 *       站点管理员只能订本站、平台管理员可订全部站点主题与看板、用户只能订本人订单主题；</li>
 *   <li><b>心跳复查</b>：30 秒一次 ping，同时按 HTTP 侧同口径复查令牌
 *       （jti 黑名单 + token_version + 账号状态 + exp），任一失效即主动断开——
 *       禁用/调岗账号的推送通道随心跳同步失效（7.1/7.3）。</li>
 * </ul>
 *
 * <p>时限说明：10 秒与 30 秒是设计书 7.3 固定的协议时限，11.2 配置键清单未设可配置项，
 * 故以常量固化并注明出处，不走 {@code SemsProperties}。
 *
 * <p>并发模型：会话表与主题索引均为 {@link ConcurrentHashMap}；发送在同一会话对象上互斥
 * （WebSocketSession 允许并发读、不允许并发写）。
 */
@Component
public class WsSessionManager {

    private static final Logger log = LoggerFactory.getLogger(WsSessionManager.class);

    /** 平台看板主题（仅平台管理员可订，7.3 主题授权表） */
    public static final String TOPIC_PLATFORM_DASHBOARD = "platform/dashboard";

    /** 首帧鉴权超时：连接后 10 秒内未通过鉴权即断开（设计书 7.3 固定值） */
    private static final long FIRST_FRAME_AUTH_TIMEOUT_MS = 10_000L;

    /** 心跳间隔：30 秒（设计书 7.3 固定值） */
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;

    /** 站点主题格式：station/{id}/stock 或 station/{id}/recycle */
    private static final Pattern STATION_TOPIC = Pattern.compile("^station/(\\d+)/(stock|recycle)$");

    /** 用户订单主题格式：user/{uid}/order */
    private static final Pattern USER_TOPIC = Pattern.compile("^user/(\\d+)/order$");

    /** 会话注册表：sessionId 到会话状态 */
    private final Map<String, WsSession> sessions = new ConcurrentHashMap<>();

    /** 主题索引：topic 到已订阅会话集合（推送按主题广播） */
    private final Map<String, Set<WebSocketSession>> topicSessions = new ConcurrentHashMap<>();

    /** 首帧鉴权超时调度器：单线程守护线程，只为保证 10 秒时限精确 */
    private final ScheduledExecutorService handshakeTimeout = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ws-handshake-timeout");
        thread.setDaemon(true);
        return thread;
    });

    private final JwtService jwtService;
    private final StringRedisTemplate stringRedisTemplate;

    /** 账号状态兜底查询：token_version 快照未命中/Redis 不可用时直读 DB（与 HTTP 侧同语义，7.1/7.2）。 */
    private final SysUserMapper sysUserMapper;

    public WsSessionManager(JwtService jwtService, StringRedisTemplate stringRedisTemplate,
                            SysUserMapper sysUserMapper) {
        this.jwtService = jwtService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.sysUserMapper = sysUserMapper;
    }

    // ------------------------------------------------------------------
    // 会话生命周期
    // ------------------------------------------------------------------

    /** 连接建立：登记会话并启动「首帧鉴权 10 秒超时」计时（7.3）。 */
    public void register(WebSocketSession session) {
        WsSession ws = new WsSession(session);
        sessions.put(session.getId(), ws);
        handshakeTimeout.schedule(() -> {
            if (!ws.authenticated() && sessions.get(session.getId()) == ws) {
                log.info("WebSocket 首帧鉴权超时，断开连接：sessionId={}", session.getId());
                closeQuietly(session, "首帧鉴权超时");
            }
        }, FIRST_FRAME_AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /** 首帧鉴权通过：绑定登录用户与原始令牌（令牌留存是为了心跳时重新校验）。 */
    public void markAuthenticated(WebSocketSession session, LoginUser user, String token) {
        WsSession ws = sessions.get(session.getId());
        if (ws == null) {
            return;
        }
        ws.user = user;
        ws.token = token;
    }

    /** 会话是否已通过首帧鉴权；未鉴权会话不允许订阅或接收推送。 */
    public boolean isAuthenticated(WebSocketSession session) {
        WsSession ws = sessions.get(session.getId());
        return ws != null && ws.authenticated();
    }

    /** 取会话绑定的登录用户（未鉴权返回 null）。 */
    public LoginUser currentUser(WebSocketSession session) {
        WsSession ws = sessions.get(session.getId());
        return ws == null ? null : ws.user;
    }

    /** 摘除会话：清理注册表与全部主题订阅（幂等，可重复调用）。 */
    public void remove(WebSocketSession session) {
        if (session == null) {
            return;
        }
        WsSession ws = sessions.remove(session.getId());
        if (ws == null) {
            return;
        }
        for (String topic : ws.topics) {
            Set<WebSocketSession> targets = topicSessions.get(topic);
            if (targets != null) {
                targets.remove(session);
                if (targets.isEmpty()) {
                    topicSessions.remove(topic, targets);
                }
            }
        }
        ws.topics.clear();
    }

    /** 关闭并摘除会话；关闭失败只记日志（连接可能已被容器断开）。 */
    public void closeQuietly(WebSocketSession session, String reason) {
        remove(session);
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.close(new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), reason));
                }
            }
        } catch (Exception e) {
            log.debug("WebSocket 关闭异常（忽略）：sessionId={}, reason={}", session.getId(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 订阅
    // ------------------------------------------------------------------

    /**
     * 订阅白名单（设计书 7.3 主题授权表）：不信任客户端声明，仅依据 JWT 解析出的用户归属判定。
     *
     * <p>注意：{@code user/{uid}/order} 仅限本人，平台管理员也不得订阅他人订单主题
     * （该主题承载收件人信息等隐私数据，平台侧看板走 platform/dashboard）。
     */
    public static boolean canSubscribe(LoginUser user, String topic) {
        if (user == null || topic == null || user.getRole() == null) {
            return false;
        }
        if (Constants.ROLE_USER.equals(user.getRole())) {
            Matcher matcher = USER_TOPIC.matcher(topic);
            return user.getUserId() != null && matcher.matches()
                    && String.valueOf(user.getUserId()).equals(matcher.group(1));
        }
        if (Constants.ROLE_STATION_ADMIN.equals(user.getRole())) {
            Matcher matcher = STATION_TOPIC.matcher(topic);
            return user.getStationId() != null && matcher.matches()
                    && String.valueOf(user.getStationId()).equals(matcher.group(1));
        }
        if (Constants.ROLE_PLATFORM_ADMIN.equals(user.getRole())) {
            return TOPIC_PLATFORM_DASHBOARD.equals(topic) || STATION_TOPIC.matcher(topic).matches();
        }
        return false;
    }

    /** 记录订阅关系；调用方必须先校验 {@link #canSubscribe} 与会话鉴权状态。 */
    public boolean subscribe(WebSocketSession session, String topic) {
        WsSession ws = sessions.get(session.getId());
        if (ws == null || !ws.authenticated() || topic == null || topic.isBlank()) {
            return false;
        }
        ws.topics.add(topic);
        topicSessions.computeIfAbsent(topic, key -> ConcurrentHashMap.newKeySet()).add(session);
        return true;
    }

    /** 取消订阅；主题下无会话时同步清理索引，避免键无限累积。 */
    public void unsubscribe(WebSocketSession session, String topic) {
        WsSession ws = sessions.get(session.getId());
        if (ws == null || topic == null) {
            return;
        }
        ws.topics.remove(topic);
        Set<WebSocketSession> targets = topicSessions.get(topic);
        if (targets != null) {
            targets.remove(session);
            if (targets.isEmpty()) {
                topicSessions.remove(topic, targets);
            }
        }
    }

    // ------------------------------------------------------------------
    // 推送与心跳
    // ------------------------------------------------------------------

    /** 向单个会话发送文本；失败只记日志并摘除会话，绝不向调用方抛异常（推送失败不影响主业务）。 */
    public void sendText(WebSocketSession session, String payload) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.warn("WebSocket 消息发送失败，摘除会话：sessionId={}, reason={}", session.getId(), e.getMessage());
            remove(session);
        }
    }

    /** 按主题广播（订阅集合为空时静默返回）。 */
    public void broadcast(String topic, String payload) {
        Set<WebSocketSession> targets = topicSessions.get(topic);
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (WebSocketSession session : targets) {
            sendText(session, payload);
        }
    }

    /**
     * 心跳任务（每 30 秒，7.3）：先按 HTTP 侧同口径复查会话令牌，失效即断开；
     * 有效会话发送 ping 保活（前端可回 pong，服务端不强制）。
     */
    @Scheduled(fixedDelay = HEARTBEAT_INTERVAL_MS)
    public void heartbeat() {
        for (WsSession ws : sessions.values()) {
            WebSocketSession session = ws.session;
            if (!session.isOpen()) {
                remove(session);
                continue;
            }
            if (!ws.authenticated()) {
                // 未鉴权会话由 10 秒超时调度器负责断开，这里不重复处理
                continue;
            }
            // ① 复查签名与有效期（exp 过期、载荷被篡改都在此抛出）
            LoginUser fresh;
            try {
                fresh = jwtService.parse(ws.token);
            } catch (BizException e) {
                // token 过期或损坏（含续签拉黑旧 jti 的情形）属预期断连：前端重连前先刷新 token
                log.info("WebSocket 心跳复查令牌无效，主动断开：sessionId={}, code={}", session.getId(), e.getErrorCode().getCode());
                closeQuietly(session, "令牌无效");
                continue;
            } catch (Exception e) {
                // 复查过程自身异常按 fail-open 处理，不因基础设施抖动误杀在线会话
                log.warn("WebSocket 心跳复查异常（fail-open）：sessionId={}, reason={}", session.getId(), e.getMessage());
                continue;
            }
            // ② 复查吊销与账号状态（黑名单 + token_version；Redis 异常时 fail-open）
            if (isTokenRevoked(fresh)) {
                log.info("WebSocket 心跳复查发现令牌已失效，主动断开：sessionId={}, uid={}", session.getId(), fresh.getUserId());
                closeQuietly(session, "令牌已失效");
                continue;
            }
            try {
                sendText(session, JsonUtils.toJson(WsMessage.of(null, WsMessage.TYPE_PING, null)));
            } catch (Exception e) {
                log.warn("WebSocket 心跳 ping 发送失败：sessionId={}, reason={}", session.getId(), e.getMessage());
            }
        }
    }

    /**
     * 令牌是否已吊销（与 HTTP 侧同口径，7.1/7.2）：
     * ① jti 黑名单存在即已吊销（单设备登出）；② token_version 与账号状态决定账号级失效
     * （禁用/改密/调岗会自增 token_version 并 DEL 快照）。
     *
     * <p>降级口径与 HTTP 侧一致：jti 黑名单在 Redis 异常时 fail-open（设备级失效窗口 ≤ 令牌剩余
     * 有效期，7.2 的明确风险接受）；账号级失效不受影响——快照未命中或 Redis 不可用都回退直读 DB
     * 比对（DB 为权威），保证「封号即失效、WS 通道在下次心跳（≤30s）断开」不依赖 HTTP 侧兜底。
     */
    public boolean isTokenRevoked(LoginUser user) {
        if (user == null || user.getJti() == null) {
            return true;
        }
        // ① 单设备登出黑名单：Redis 异常时 fail-open，不阻断下面的账号级复查
        try {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeys.authDeny(user.getJti())))) {
                return true;
            }
        } catch (Exception e) {
            log.warn("WebSocket 令牌黑名单复查失败（本项 fail-open，账号级失效不受影响）：{}", e.getMessage());
        }
        // ② 账号级失效：token_version + 账号状态
        return isAccountRevoked(user);
    }

    /**
     * 账号级失效复查（与 HTTP 侧 {@code AuthInterceptor#applyAccountState} 同一套语义）：
     * 先读 60s 快照读加速；<b>快照未命中（含 Redis 读取失败）一律回退直读 DB</b>，
     * 不存在/版本不一致/非启用态都判为已吊销——禁用/改密/调岗会自增版本并 DEL 快照，
     * 若缓存缺失就跳过比对，被禁用账号的 WS 连接会一直存活（7.1 的兜底不能只靠 HTTP 侧）。
     */
    private boolean isAccountRevoked(LoginUser user) {
        String cached = null;
        try {
            cached = stringRedisTemplate.opsForValue().get(RedisKeys.authUserVer(user.getUserId()));
        } catch (Exception e) {
            log.warn("WebSocket 账号快照读取失败，回退直读数据库（与 HTTP 侧同语义）：uid={}, reason={}",
                    user.getUserId(), e.getMessage());
        }
        if (cached != null && !cached.isBlank()) {
            Long currentVersion = leadingNumber(cached);
            if (currentVersion != null && user.getTokenVersion() != null
                    && !currentVersion.equals(user.getTokenVersion())) {
                return true;
            }
            // 账号快照中的状态段必须为 ENABLED；未知格式不误杀（见 snapshotEnabled）
            if (!snapshotEnabled(cached)) {
                return true;
            }
            return false;
        }
        // 快照缺失（未命中或 Redis 故障）：直读 DB 比对，保持与 HTTP 侧「DB 为准」同一套语义
        try {
            SysUser dbUser = sysUserMapper.selectById(user.getUserId());
            if (dbUser == null) {
                // 用户已被删除：存量令牌立即失效（同 HTTP 侧）
                return true;
            }
            long dbVersion = dbUser.getTokenVersion() == null ? 0L : dbUser.getTokenVersion();
            if (user.getTokenVersion() == null || dbVersion != user.getTokenVersion()) {
                return true;
            }
            return !Constants.STATUS_ENABLED.equals(dbUser.getStatus());
        } catch (Exception e) {
            // DB 也不可用：本轮 fail-open，避免基础设施抖动误杀在线会话；下一轮心跳继续复查
            log.warn("WebSocket 账号状态回退数据库查询失败（本轮 fail-open）：uid={}, reason={}",
                    user.getUserId(), e.getMessage());
            return false;
        }
    }

    /**
     * 取缓存值的前导数字：安全层的账号快照以「{tokenVersion}|{status}|{mustChangePassword}」
     * 编码，本方法取其中的版本号；无法识别时返回 null（调用方按 fail-open 跳过版本比对，绝不误杀）。
     */
    private static Long leadingNumber(String value) {
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        try {
            return Long.parseLong(value.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 判定账号快照是否处于启用态：按安全层编码取状态段比对 ENABLED；
     * 若缓存退化为仅版本号的精简形式（无分隔符），则以「不含 DISABLED 即视为启用」兜底，
     * 保证未知格式只会漏判、不会误杀在线会话。
     */
    private static boolean snapshotEnabled(String snapshot) {
        int first = snapshot.indexOf('|');
        if (first < 0) {
            return !snapshot.contains(Constants.STATUS_DISABLED);
        }
        int second = snapshot.indexOf('|', first + 1);
        String status = second < 0 ? snapshot.substring(first + 1) : snapshot.substring(first + 1, second);
        return Constants.STATUS_ENABLED.equals(status);
    }

    @PreDestroy
    public void shutdown() {
        handshakeTimeout.shutdownNow();
    }

    /** 单个会话的服务端状态：鉴权结果、原始令牌与已订阅主题。 */
    private static final class WsSession {

        private final WebSocketSession session;
        private final Set<String> topics = ConcurrentHashMap.newKeySet();
        private volatile LoginUser user;
        private volatile String token;

        private WsSession(WebSocketSession session) {
            this.session = session;
        }

        private boolean authenticated() {
            return user != null;
        }
    }
}
