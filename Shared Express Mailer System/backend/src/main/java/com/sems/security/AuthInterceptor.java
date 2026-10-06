package com.sems.security;

import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.config.SemsProperties;
import com.sems.infra.RedisKeys;
import com.sems.module.user.entity.SysUser;
import com.sems.module.user.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 认证拦截器：解析 {@code Authorization: Bearer <JWT>} 并校验「令牌—账号」一致性，
 * 通过后把登录态写入 {@link AuthContext}，供本请求后续代码取用
 * （见《系统设计说明书》7.1 拦截链、7.2 降级控制矩阵、9.2/9.3）。
 *
 * <p><b>职责边界</b>：本类只做认证（你是谁）。角色鉴权（{@link RequireRole}）由
 * AuthzInterceptor 负责；「本站/本人」数据归属校验在 Service 层（契约 10）。
 *
 * <p><b>preHandle 流程</b>（契约 4.5，顺序不可调换）：
 * <ol>
 *   <li>CORS 预检与白名单路径直接放行（白名单见 {@link #ANONYMOUS_PATHS}）；</li>
 *   <li>取 Authorization 头，缺失或非 Bearer 一律 1001；</li>
 *   <li>{@link JwtService#parse} 验签解析（内部固定 HS256）；</li>
 *   <li>jti 黑名单 {@code auth:deny:{jti}}（单设备登出/续签拉黑）：命中即 1001；
 *       <b>Redis 异常时 fail-open</b> 并记 WARN——设备级失效窗口 ≤ 令牌剩余有效期，
 *       这是设计书 7.2 明确的风险接受（账号级失效由下一步兜住）；</li>
 *   <li>token_version 与账号状态：先读 Redis 快照（{@code auth:userver:{uid}}，TTL 60s），
 *       未命中或 Redis 异常则直读 DB，<b>DB 为准</b>；版本不一致或 status != ENABLED 抛 1001；</li>
 *   <li>强制改密门禁：must_change_password=1 时仅放行
 *       「修改密码 / 退出登录 / 续签 / 查询本人信息」四条路径（契约 4.4）——
 *       <b>必须先于 {@code AuthContext.set} 判断</b>：preHandle 抛异常时 Spring 不会对本拦截器
 *       回调 afterCompletion（interceptorIndex 未记录），先 set 再抛会让登录态残留在池化线程上；</li>
 *   <li>{@code AuthContext.set}（此后请求结束由 afterCompletion 清理）。</li>
 * </ol>
 *
 * <p><b>白名单为什么必须在最前</b>：白名单是递归的「无认证入口」（探活、验证码、注册、登录、
 * 接口文档、WebSocket 握手），若先要令牌，登录接口本身就会被 401 锁死。
 *
 * <p><b>线程安全</b>：本类是 Spring 单例，全部状态靠方法局部变量与 {@link AuthContext}（ThreadLocal）承载，
 * 不持有请求级可变字段。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /**
     * 免认证路径（契约 4.5；Ant 通配 {@code /ws/**} 同时覆盖裸路径 {@code /ws}）。
     *
     * <p>这些端点各自有独立防护：登录/注册有验证码与锁定（9.2）、文档端点生产关闭（6.5）、
     * WebSocket 走首帧鉴权（7.3）；把它们放进白名单不等于「无防护」。
     */
    private static final List<String> ANONYMOUS_PATHS = List.of(
            "/api/health",
            "/api/v1/auth/captcha",
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/ws/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/doc.html"
    );

    /**
     * 强制改密期间的放行路径（契约 4.4）：
     * 修改密码、退出登录、续签、查询本人信息。
     *
     * <p>为什么不放行其他接口：{@code must_change_password=1} 的账号（预置管理员、被重置密码者）
     * 用的是口令已泄漏/仅作交付的口令，必须先换成本人口令才能进入业务面——
     * 否则「初始口令」就成了长期有效的后门。
     */
    private static final Set<String> MUST_CHANGE_PASSWORD_ALLOWED_PATHS = Set.of(
            "/api/v1/users/me/password",
            "/api/v1/auth/logout",
            "/api/v1/auth/refresh",
            "/api/v1/users/me"
    );

    /**
     * 账号快照（token_version + status + 强制改密标记）在 Redis 中的缓存时长。
     *
     * <p>60s 是设计书 7.1/8.3 固定的「读加速」窗口：token_version 被自增（禁用/改密/重置/调岗）时，
     * 业务侧会同步 {@code DEL auth:userver:{uid}} 立即失效，本 TTL 只兜住漏删与常规读压力。
     * SemsProperties 未设对应键（契约 3.3 冻结），故以常量落地；调整须同步设计书 7.1/8.3。
     */
    private static final Duration USER_VER_CACHE_TTL = Duration.ofSeconds(60);

    /** 账号快照编码的分隔符：{@code {tokenVersion}|{status}|{mustChangePassword 0/1}}。 */
    private static final String SNAPSHOT_SEPARATOR = "|";

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final JwtService jwtService;

    /** 配置对象（契约 4.5 要求构造注入）：降级 WARN 日志中用于说明设备级失效窗口。 */
    private final SemsProperties properties;

    private final StringRedisTemplate redisTemplate;

    private final SysUserMapper sysUserMapper;

    public AuthInterceptor(JwtService jwtService, SemsProperties properties,
                           StringRedisTemplate redisTemplate, SysUserMapper sysUserMapper) {
        this.jwtService = jwtService;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.sysUserMapper = sysUserMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 预检请求不携带 Authorization，且浏览器要求预检可匿名通过；
        // 预检由 MVC 的 PreFlightHandler 处理、不会触达业务接口，放行无越权风险。
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String path = pathWithinApplication(request);
        if (isAnonymousPath(path)) {
            return true;
        }
        // ① 取令牌：格式不符与「没带令牌」同样按未认证处理，不区分提示
        String authorization = request.getHeader(Constants.AUTH_HEADER);
        if (authorization == null || !authorization.startsWith(Constants.BEARER_PREFIX)) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        String token = authorization.substring(Constants.BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        // ② 验签解析（含 HS256 固定校验；任何失败均为 1001）
        LoginUser loginUser = jwtService.parse(token);
        // ③ 单设备登出/续签黑名单
        assertNotDenied(loginUser.getJti());
        // ④ 用户级全量吊销（token_version）与账号状态
        applyAccountState(loginUser);
        // ⑤ 强制改密门禁：必须放在 AuthContext.set 之前判断。
        //    原因（Spring 的 afterCompletion 触发条件）：HandlerExecutionChain.interceptorIndex
        //    仅在 preHandle 返回 true 之后才记录本拦截器；preHandle 抛异常时 applyPreHandle 直接上抛，
        //    triggerAfterCompletion 的循环根本轮不到本拦截器，AuthContext.clear() 会被跳过——
        //    完整的 LoginUser 将残留在 Tomcat 池化线程的 ThreadLocal 上，后续同线程请求可能以
        //    残留身份执行，构成身份串用与越权（AuthContext 类头警告的「最典型的越权事故」）。
        //    因此门禁先判、先抛，认证通过后才写入请求级上下文。
        if (loginUser.isMustChangePassword() && !MUST_CHANGE_PASSWORD_ALLOWED_PATHS.contains(path)) {
            // 双保险：即便将来有人把门禁挪回 set 之后，也保证抛异常前上下文已清空（clear 幂等无害）
            AuthContext.clear();
            throw new BizException(ErrorCode.FORBIDDEN, "请先修改初始密码");
        }
        // 认证通过：写入请求级上下文（afterCompletion 负责清理）
        AuthContext.set(loginUser);
        return true;
    }

    /** 请求结束必须清理 ThreadLocal：容器线程复用，残留身份会被下一个请求误用（越权）。 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }

    /** 白名单匹配：支持 Ant 通配（如 {@code /swagger-ui/**}），逐条比对。 */
    private static boolean isAnonymousPath(String path) {
        for (String pattern : ANONYMOUS_PATHS) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** 去掉应用上下文前缀，得到拦截器注册路径口径下的 URI（保证白名单常量与实际路径可比）。 */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /**
     * jti 黑名单校验：{@code auth:deny:{jti}} 存在即表示该端已登出/已续签。
     *
     * <p>Redis 异常时 fail-open（设计书 7.2 降级矩阵）：宁可让「单设备登出」的失效窗口
     * 拖到 ≤ access-ttl，也不能让 Redis 故障把全体用户挡在门外；账号级失效（禁用/重置/调岗）
     * 由 {@link #applyAccountState} 直读 DB 保证，仍然即时。降级放行记 WARN 留痕。
     */
    private void assertNotDenied(String jti) {
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.authDeny(jti)))) {
                throw new BizException(ErrorCode.UNAUTHENTICATED);
            }
        } catch (BizException e) {
            // 命中黑名单的正常拒绝，不能被下面的降级分支吞掉
            throw e;
        } catch (Exception e) {
            log.warn("Redis 黑名单校验失败，降级放行（fail-open）：jti={}, 设备级失效窗口 ≤ {}, 原因={}",
                    jti, properties.getJwt().getAccessTtl(), e.toString());
        }
    }

    /**
     * 校验并回填账号维度状态：token_version（用户级全量吊销）、status（账号启停）、
     * mustChangePassword（强制改密标记）。
     *
     * <p>读取顺序：Redis 快照（60s 读加速）→ 未命中/格式不认识/Redis 异常则直读 DB。
     * <b>DB 永远是权威</b>：快照只是「最近一次查库结果的窗口」，任何不确定都回退查询。
     */
    private void applyAccountState(LoginUser loginUser) {
        Long uid = loginUser.getUserId();
        AccountState state = readAccountStateFromRedis(uid);
        if (state == null) {
            SysUser user = sysUserMapper.selectById(uid);
            if (user == null) {
                // 用户已被删除：存量令牌立即失效
                throw new BizException(ErrorCode.UNAUTHENTICATED);
            }
            state = new AccountState(
                    user.getTokenVersion() == null ? 0 : user.getTokenVersion(),
                    user.getStatus(),
                    user.getMustChangePassword() != null && user.getMustChangePassword() != 0);
            writeAccountStateToRedis(uid, state);
        }
        long tokenVersion = loginUser.getTokenVersion() == null ? -1L : loginUser.getTokenVersion();
        if (state.tokenVersion() != tokenVersion) {
            // 版本不一致：令牌签发后被「禁用/改密/重置/调岗」等自增操作作废（设计书 7.1 第 2 条）
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        if (!Constants.STATUS_ENABLED.equals(state.status())) {
            // 账号已禁用：即使令牌仍在有效期、黑名单未命中，也立即拒绝（「封号即失效」）
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        loginUser.setMustChangePassword(state.mustChangePassword());
    }

    /**
     * 读账号快照缓存；未命中、格式不认识或 Redis 异常统一返回 {@code null}（交由调用方回退 DB）。
     *
     * <p>捕获所有异常而不区分类型，是 fail-safe 的取舍：账号状态判定必须能走 DB，
     * 不能让任何 Redis 客户端异常把认证链路整体打成 500。
     */
    private AccountState readAccountStateFromRedis(Long uid) {
        try {
            String raw = redisTemplate.opsForValue().get(RedisKeys.authUserVer(uid));
            if (raw == null) {
                return null;
            }
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 3) {
                // 兼容其它格式的写入：不认识就当作未命中，回 DB 取值并以本类格式覆写
                return null;
            }
            return new AccountState(Integer.parseInt(parts[0]), parts[1], "1".equals(parts[2]));
        } catch (Exception e) {
            log.warn("Redis 账号认证快照读取失败，降级直读数据库（DB 为准）：uid={}, 原因={}", uid, e.toString());
            return null;
        }
    }

    /** 回写账号快照；写失败只影响下次请求的读加速，本次认证已按 DB 结果完成，故仅记 WARN 不失败。 */
    private void writeAccountStateToRedis(Long uid, AccountState state) {
        try {
            redisTemplate.opsForValue().set(RedisKeys.authUserVer(uid), encode(state), USER_VER_CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis 账号认证快照写入失败（本次认证已按数据库结果完成）：uid={}, 原因={}", uid, e.toString());
        }
    }

    private static String encode(AccountState state) {
        return state.tokenVersion() + SNAPSHOT_SEPARATOR + state.status() + SNAPSHOT_SEPARATOR
                + (state.mustChangePassword() ? "1" : "0");
    }

    /**
     * 账号维度的认证快照。
     *
     * <p>三个字段必须同源同取、一起缓存：token_version 决定「旧令牌是否作废」、
     * status 决定「账号是否被封」、mustChangePassword 决定「是否只准走改密通道」。
     * 若为了省字节只缓存版本与状态、每次请求再查库补标记，60s 读缓存就失去意义；
     * 若把标记漏掉，强制改密会在缓存命中期间形同虚设。
     */
    private record AccountState(int tokenVersion, String status, boolean mustChangePassword) {
    }
}
