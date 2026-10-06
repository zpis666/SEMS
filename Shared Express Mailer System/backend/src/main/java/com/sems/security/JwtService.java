package com.sems.security;

import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.common.util.JsonUtils;
import com.sems.config.SemsProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 签发与校验（见《系统设计说明书》7.1、9.2，密钥管理见 11.3）。
 *
 * <p>令牌约定：
 * <ul>
 *   <li>算法固定 <b>HS256</b>：签发时显式指定，解析时<b>先读 JOSE 头部的 alg</b>，非 HS256
 *       直接拒绝——把算法选择权交给令牌本身正是算法混淆攻击的入口，必须在验签前掐断；</li>
 *   <li>载荷：{@code sub=uid}、{@code phone}、{@code nickname}、{@code role}、{@code stationId}
 *       （站点管理员专属）、{@code tokenVersion}、{@code jti}、{@code iat}、{@code exp}；</li>
 *   <li>有效期取 {@code sems.jwt.access-ttl}（默认 2h，见设计书 3.5），不写死；</li>
 *   <li>密钥取 {@code sems.jwt.secret}，长度不足 32 字节（256 位，RFC 7518 §3.2 下限）时
 *       <b>启动即失败</b>——弱密钥若拖到首次登录才暴露，运行期很难发现；</li>
 *   <li><b>双密钥轮换窗口</b>（设计书 7.1/11.3「旧密钥只验不签，过渡 24h」）：新密钥只签发；
 *       配置了 {@code sems.jwt.previous-secret} 时，验签在新密钥失败后回退旧密钥，
 *       窗口取 {@code sems.jwt.rotation-grace}（默认 24h，自启动起算），窗口外不再接受旧签名。</li>
 * </ul>
 *
 * <p>本类只回答「令牌是否由本系统签发且未过期」；是否被登出拉黑（jti 黑名单）、
 * 账号是否被禁用/令牌版本是否过期（token_version/status）由 {@link AuthInterceptor}
 * 结合 Redis 与数据库判定，两者职责分离。
 *
 * <p>线程安全：{@link JwtParser} 与 {@link SecretKey} 均为不可变对象，构建一次即可并发复用。
 */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** HS256 密钥长度下限：RFC 7518 §3.2 要求 HMAC 密钥不短于哈希输出长度（256 位 = 32 字节）。 */
    private static final int MIN_SECRET_BYTES = 32;

    /** 必需的签名算法 id（JJWT 的 HS256 实现名，即 JOSE 头部 alg 的取值）。 */
    private static final String REQUIRED_ALG = Jwts.SIG.HS256.getId();

    /** 自定义声明名（契约 4.3 冻结；sub/jti/iat/exp 走 JJWT 标准方法，不在此列）。 */
    private static final String CLAIM_PHONE = "phone";
    private static final String CLAIM_NICKNAME = "nickname";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_STATION_ID = "stationId";
    private static final String CLAIM_TOKEN_VERSION = "tokenVersion";

    private final SemsProperties properties;

    /** 当前（新）HMAC 密钥：只签发（仅存于内存，不入库不入码，见设计书 11.3 密钥清单）。 */
    private final SecretKey key;

    /** 复用解析器：固定当前密钥验签，避免每次解析重复构建。 */
    private final JwtParser parser;

    /** 轮换窗口内的旧密钥解析器（旧密钥只验不签）；未配置轮换时为 {@code null}。 */
    private final JwtParser previousParser;

    /** 旧密钥验签截止时刻（启动时刻 + {@code sems.jwt.rotation-grace}）；未配置轮换时为 {@code null}。 */
    private final Instant previousKeyAcceptUntil;

    public JwtService(SemsProperties properties) {
        this.properties = properties;
        SemsProperties.Jwt jwt = properties == null ? null : properties.getJwt();
        this.key = buildKey(jwt == null ? null : jwt.getSecret(), "sems.jwt.secret", "SEMS_JWT_SECRET");
        this.parser = Jwts.parser().verifyWith(this.key).build();

        String previousSecret = jwt == null ? null : jwt.getPreviousSecret();
        if (previousSecret == null || previousSecret.isBlank()) {
            this.previousParser = null;
            this.previousKeyAcceptUntil = null;
        } else {
            // 双密钥轮换窗口（设计书 7.1/11.3）：旧密钥只验不签，窗口自本次启动起算 rotation-grace。
            // 为什么窗口自启动起算：轮换环境变量必然伴随重启，启动时刻即「轮换生效时刻」；
            // 到期后旧签名一律拒绝（fail-closed）——access token 最长 2h，到期后已无合法旧令牌，
            // 窗口只是设计书给定的 24h 运维过渡余量。
            Duration grace = resolveRotationGrace(jwt);
            SecretKey previousKey = buildKey(previousSecret, "sems.jwt.previous-secret",
                    "SEMS_JWT_PREVIOUS_SECRET");
            this.previousParser = Jwts.parser().verifyWith(previousKey).build();
            this.previousKeyAcceptUntil = Instant.now().plus(grace);
            log.warn("JWT 双密钥轮换窗口已启用：旧密钥只验不签，接受窗口至 {}（{}）；"
                            + "过渡期结束后请从配置中移除 sems.jwt.previous-secret",
                    previousKeyAcceptUntil, grace);
        }
    }

    /** 取旧密钥验签窗口：配置非法时启动即失败，避免「窗口未知」被当作无限制接受。 */
    private static Duration resolveRotationGrace(SemsProperties.Jwt jwt) {
        Duration grace = jwt.getRotationGrace();
        if (grace == null || grace.isZero() || grace.isNegative()) {
            throw new IllegalStateException("sems.jwt.rotation-grace 必须为正的时长，当前=" + grace);
        }
        return grace;
    }

    /** 由配置构建单把 HMAC 密钥；配置缺失或强度不足时抛异常快速失败。 */
    private static SecretKey buildKey(String secret, String configKey, String envHint) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(configKey + " 未配置："
                    + "请通过 application-*.yml 或环境变量 " + envHint + " 提供 ≥32 字节随机密钥");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(configKey + " 长度不足 32 字节（当前 " + secretBytes.length
                    + " 字节）：HS256 要求密钥 ≥256 位，请改用 ≥32 字节的高熵随机串（见《系统设计说明书》7.1/11.3）");
        }
        return Keys.hmacShaKeyFor(secretBytes);
    }

    /**
     * 签发访问令牌。
     *
     * <p>副作用（有意为之）：{@code jti} 缺失时自动生成并回填到 {@code principal}，
     * {@code expiresAt} 也回填。登出拉黑、续签判断使用的 jti 必须与令牌内一致，
     * 回填可避免调用方漏设导致「签出去的 jti 与后续拉黑的 jti 对不上」。
     *
     * @param principal 登录态主体，必须已设置 {@code userId}
     * @return 紧凑序列化（三段式）的 JWT
     */
    public String issue(LoginUser principal) {
        if (principal == null || principal.getUserId() == null) {
            throw new IllegalArgumentException("签发 JWT 需要已设置 userId 的 LoginUser");
        }
        Duration accessTtl = properties.getJwt().getAccessTtl();
        if (accessTtl == null || accessTtl.isZero() || accessTtl.isNegative()) {
            throw new IllegalStateException("sems.jwt.access-ttl 必须为正的时长，当前=" + accessTtl);
        }
        Instant issuedAt = Instant.now();
        Instant expiration = issuedAt.plus(accessTtl);
        if (principal.getJti() == null || principal.getJti().isBlank()) {
            principal.setJti(newJti());
        }
        principal.setExpiresAt(LocalDateTime.ofInstant(expiration, ZoneId.systemDefault()));
        return Jwts.builder()
                .subject(String.valueOf(principal.getUserId()))
                .claim(CLAIM_PHONE, principal.getPhone())
                .claim(CLAIM_NICKNAME, principal.getNickname())
                .claim(CLAIM_ROLE, principal.getRole())
                .claim(CLAIM_STATION_ID, principal.getStationId())
                .claim(CLAIM_TOKEN_VERSION, principal.getTokenVersion())
                .id(principal.getJti())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiration))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并校验令牌。
     *
     * <p>失败原因对调用方一律折叠为 1001（过期、签名不符、格式错误、算法不符……）：
     * 向前端区分「签名错」还是「已过期」等于给攻击者递调试信息（见设计书 9.1 信息泄露对策）。
     *
     * @throws BizException {@link ErrorCode#UNAUTHENTICATED} 令牌非法或已过期
     */
    public LoginUser parse(String token) {
        Claims claims = verifyClaims(token);
        try {
            LoginUser user = new LoginUser();
            user.setUserId(Long.valueOf(claims.getSubject()));
            user.setPhone(asString(claims.get(CLAIM_PHONE)));
            user.setNickname(asString(claims.get(CLAIM_NICKNAME)));
            user.setRole(asString(claims.get(CLAIM_ROLE)));
            user.setStationId(asLong(claims.get(CLAIM_STATION_ID)));
            user.setTokenVersion(asLong(claims.get(CLAIM_TOKEN_VERSION)));
            user.setJti(claims.getId());
            user.setExpiresAt(LocalDateTime.ofInstant(claims.getExpiration().toInstant(), ZoneId.systemDefault()));
            if (user.getTokenVersion() == null || user.getJti() == null || user.getJti().isBlank()) {
                // 本系统签发的令牌必带 tokenVersion 与 jti：前者是吊销比对基准，后者是黑名单键的一部分。
                // 两者缺失只可能来自伪造或跨系统令牌，按非法令牌处理。
                throw new BizException(ErrorCode.UNAUTHENTICATED);
            }
            return user;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            // 载荷结构异常（sub 非数字、exp 缺失等）同属非法令牌，不泄露具体栈信息
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
    }

    /**
     * 令牌剩余有效期（登出/续签时用于设置黑名单 TTL，见设计书 7.1 第 1 条）。
     *
     * <p>已过期或不可解析返回 {@link Duration#ZERO}：没有剩余有效期就无需拉黑，
     * 调用方（如登出）据此跳过写 Redis，避免拿 0/负 TTL 去 SETEX 报错。
     */
    public Duration remaining(String token) {
        try {
            Claims claims = verifyClaims(token);
            Date expiration = claims.getExpiration();
            if (expiration == null) {
                return Duration.ZERO;
            }
            Duration left = Duration.between(Instant.now(), expiration.toInstant());
            return left.isNegative() ? Duration.ZERO : left;
        } catch (BizException e) {
            return Duration.ZERO;
        }
    }

    /** 新令牌唯一标识：UUID 去掉连字符（32 位十六进制），与库表审计字段口径一致。 */
    public String newJti() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 验签并取出载荷，三步走：
     * <ol>
     *   <li>先解码 JOSE 头部读 alg（不验签也能读，正因如此必须先读），非 HS256 立即拒绝；</li>
     *   <li>用当前密钥 {@code parser} 验签并校验 exp；</li>
     *   <li>当前密钥失败且处于双密钥轮换窗口内时，改用旧密钥 {@code previousParser} 再验一次
     *       （旧密钥只验不签，设计书 7.1/11.3）。</li>
     * </ol>
     * 第二步的 {@code verifyWith(SecretKey)} 还会拒绝把非对称公钥当 HMAC 密钥使用的输入，
     * 与第一步形成双层防护；任何分支的失败原因仍对外折叠为 1001。
     */
    private Claims verifyClaims(String token) {
        assertHs256Header(token);
        try {
            return extractClaims(parser, token);
        } catch (JwtException | IllegalArgumentException currentKeyFailure) {
            JwtParser fallback = previousParserWithinGrace();
            if (fallback == null) {
                throw new BizException(ErrorCode.UNAUTHENTICATED);
            }
            try {
                return extractClaims(fallback, token);
            } catch (JwtException | IllegalArgumentException previousKeyFailure) {
                throw new BizException(ErrorCode.UNAUTHENTICATED);
            }
        }
    }

    /** 用指定解析器验签并取出载荷；载荷缺失按非法令牌处理（1001）。 */
    private static Claims extractClaims(JwtParser parser, String token) {
        Jws<Claims> jws = parser.parseSignedClaims(token);
        Claims claims = jws.getPayload();
        if (claims == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        return claims;
    }

    /**
     * 取轮换窗口内可用的旧密钥解析器；未配置或已过窗口返回 {@code null}（fail-closed）。
     *
     * <p>窗口在每次验签时实时判定（而非启动时一次性置位），保证「过渡 24h」是真实的时间窗：
     * 到期后即使运维忘记清理 {@code previous-secret}，旧签名令牌也不再被接受。
     */
    private JwtParser previousParserWithinGrace() {
        if (previousParser == null || previousKeyAcceptUntil == null) {
            return null;
        }
        return Instant.now().isBefore(previousKeyAcceptUntil) ? previousParser : null;
    }

    /**
     * 读取 JOSE 头部并固定 alg=HS256。
     *
     * <p>此处刻意「先读头、后验签」：若反过来先验签，算法选择权就交给了攻击者可控的头部，
     * 一旦将来引入非对称密钥或密钥轮换，alg 混淆（如 RS256→HS256）即可伪造身份。
     */
    private static void assertHs256Header(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            // JWS 紧凑格式必须是 header.payload.signature 三段
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        String headerJson;
        try {
            headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        String alg;
        try {
            Map<?, ?> header = JsonUtils.parse(headerJson, Map.class);
            Object value = header == null ? null : header.get("alg");
            alg = value == null ? null : String.valueOf(value);
        } catch (RuntimeException e) {
            // 头部不是合法 JSON 对象：同属非法令牌，统一 1001（不区分 JsonUtils 的包装异常类型）
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        if (!REQUIRED_ALG.equals(alg)) {
            log.warn("拒绝非 HS256 令牌：header.alg={}", alg);
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
    }

    /** 声明值转字符串；null 保持 null（如站点管理员之外的 stationId）。 */
    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 声明值转 Long；JSON 数字可能反序列化为 Integer/Long，统一走 Number 处理。 */
    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
