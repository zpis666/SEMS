package com.sems.module.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.common.util.MaskUtils;
import com.sems.config.SemsProperties;
import com.sems.infra.RateLimitService;
import com.sems.infra.RedisKeys;
import com.sems.module.audit.entity.LoginLog;
import com.sems.module.audit.service.AuditLogService;
import com.sems.module.auth.dto.LoginDto;
import com.sems.module.auth.dto.LoginVo;
import com.sems.module.auth.dto.RegisterDto;
import com.sems.module.auth.dto.LoginUserVo;
import com.sems.module.user.entity.SysRole;
import com.sems.module.user.entity.SysUser;
import com.sems.module.user.entity.SysUserRole;
import com.sems.module.user.mapper.SysRoleMapper;
import com.sems.module.user.mapper.SysUserMapper;
import com.sems.module.user.mapper.SysUserRoleMapper;
import com.sems.security.AuthContext;
import com.sems.security.JwtService;
import com.sems.security.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务：注册、登录（含失败锁定与图形验证码升级）、静默续签、登出
 * （见《系统设计说明书》5.1 用户管理模块、7.1 JWT 认证设计、9.2 认证与会话安全）。
 *
 * <p><b>登录防护链</b>（3.5/9.2/9.6）：应用层固定窗口限流（按 IP，超限 5101）→ 密码 BCrypt 比对（cost≥10）
 * → 连续失败 {@code sems.auth.lock-max-fails} 次后锁定 {@code sems.auth.lock-minutes} 分钟
 * （Redis 计数 {@code login:fail:{phone}}）→ 锁定期满后<b>首次</b>登录强制一次性图形验证码。
 *
 * <p><b>「锁定期满后首次登录须图形验证码」如何落地</b>：
 * {@code login:fail:{phone}} 会随锁定期满自然过期，届时无法再区分「从未失败过」与
 * 「刚被锁过」，因此本类另用一个私有键 {@code login:locked-once:{phone}}（TTL 1 天）
 * 承载「该账号曾被锁定」的事实——失败次数达到上限时写入、登录成功时删除；
 * 该键存在时登录必须携带 captchaId/captchaCode 并一次性校验通过，否则 3001。
 * 为什么需要这一步：锁定只拦住"当场暴力破解"，若不追加人机校验，攻击者可在每次
 * 锁定期满后用机器速度继续试下一批口令（15 分钟窗口 × 无限轮次），
 * 人机屏障把重试成本重新抬高。锁定窗口只有 15 分钟而记忆期取 1 天，
 * 是为了让「期满后的首次登录」有足够长的覆盖范围；1 天后仍未登录则视为自然观察期已过。
 *
 * <p><b>为什么登录/续签/登出不加 {@code @Transactional}</b>：登录成功路径只有一条 UPDATE
 * （单语句本身原子）；而失败路径要写 login_log，审计队列饱和时 login_log 会降级为
 * 「调用线程同步写」——若方法处于数据库事务中，该写入会随业务异常一起回滚，
 * 违背设计书 7.6/9.2「登录成功/失败均写 login_log、不能丢」的要求。
 * 注册需要「建账号 + 授角色」两步原子，故只有注册加事务。
 *
 * <p><b>Redis 故障口径</b>：登录失败锁定与应用层限流在降级期退化为 Nginx 按 IP 限流兜底、
 * 恢复后重置计数（设计书 7.2 降级矩阵）；jti 黑名单降级期 fail-open（设备级失效窗口
 * ≤ 令牌剩余有效期）。本类所有 Redis 访问均按此口径捕获异常并记 WARN，不静默也不硬失败。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * 「曾被锁定」记忆键前缀。未放入 {@code RedisKeys}：该键是 9.2 登录防护的落地细节，
     * 不属于 8.3 键空间规划中对外承诺的键，且 RedisKeys 已冻结不得修改。
     * 键格式 {@code login:locked-once:{phone}}，与 {@code login:fail:{phone}} 同族。
     */
    private static final String LOCKED_ONCE_KEY_PREFIX = "login:locked-once:";

    /** 曾锁定记忆的存活时长：1 天（覆盖"锁定期满后的首次登录"，见类头机制说明）。 */
    private static final Duration LOCKED_ONCE_TTL = Duration.ofDays(1);

    /**
     * 登录接口应用层限流：固定窗口 1 分钟 10 次（设计书 3.5"应用层限流窗口：登录/取袋/扫码查询"、
     * 9.6"登录……以 Redis 固定窗口限流，超限返回 5101"）。匿名接口无 uid，限流主体取来源 IP。
     */
    private static final int LOGIN_RATE_LIMIT_PER_MINUTE = 10;

    /** 应用层限流窗口长度：固定 1 分钟（设计书 3.5；与 {@link RateLimitService} 的显式传参约定一致）。 */
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    /** 登录限流键的 api 段（键口径 {@code rate:{uid或ip}:{api}}，见设计书 8.3）。 */
    private static final String LOGIN_RATE_API = "login";

    /**
     * 统一登录失败提示：不区分「账号不存在」与「密码错误」，避免账号枚举
     * （设计书 9.1 信息泄露对策：错误信息不暴露账号是否存在）。
     */
    private static final String LOGIN_FAILED_MESSAGE = "手机号或密码错误";

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CaptchaService captchaService;
    private final AuditLogService auditLogService;
    private final StringRedisTemplate redisTemplate;
    private final SemsProperties properties;
    private final RateLimitService rateLimitService;

    public AuthService(SysUserMapper sysUserMapper,
                       SysRoleMapper sysRoleMapper,
                       SysUserRoleMapper sysUserRoleMapper,
                       BCryptPasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       CaptchaService captchaService,
                       AuditLogService auditLogService,
                       StringRedisTemplate redisTemplate,
                       SemsProperties properties,
                       RateLimitService rateLimitService) {
        this.sysUserMapper = sysUserMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.captchaService = captchaService;
        this.auditLogService = auditLogService;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.rateLimitService = rateLimitService;
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    /**
     * 注册（见设计书 5.1）：手机号 + 密码 + 图形验证码 + 昵称，成功后获得 USER 角色。
     *
     * <p>匿名写接口不启用接口级幂等（基础层契约第 4 节：由验证码与唯一键另行防护）：
     * 验证码一次性消费挡住脚本重放，手机号唯一键挡住并发重复注册。
     *
     * @param dto 注册入参（字段格式已由 JSR-380 校验）
     */
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterDto dto) {
        // ① 先消费图形验证码：验证码不过不做任何库操作，匿名接口的第一道闸
        captchaService.verifyAndConsume(dto.getCaptchaId(), dto.getCaptchaCode());

        // ② 手机号唯一性预查（友好提示）；并发窗口由 uk_user_phone 唯一键兜底（见 ③ 的捕获）
        Long existing = sysUserMapper.selectCount(Wrappers.lambdaQuery(SysUser.class)
                .eq(SysUser::getPhone, dto.getPhone()));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该手机号已注册");
        }

        // ③ 建账号：BCrypt 加密存储；自助注册即启用、不强制改密，token_version 显式置 0
        SysUser user = new SysUser();
        user.setPhone(dto.getPhone());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname());
        user.setStatus(Constants.STATUS_ENABLED);
        user.setTokenVersion(0);
        user.setMustChangePassword(0);
        try {
            sysUserMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册撞唯一键：转为业务错误，不把 5000 暴露给前端（6.6）
            throw new BizException(ErrorCode.PARAM_INVALID, "该手机号已注册");
        }

        // ④ 授予普通用户角色：自助注册只能得到 USER；管理角色由平台管理员创建（设计书 4.2）
        SysRole userRole = sysRoleMapper.selectOne(Wrappers.lambdaQuery(SysRole.class)
                .eq(SysRole::getRoleCode, Constants.ROLE_USER));
        if (userRole == null) {
            // 预置角色缺失属部署问题：回滚注册并显式报错，不允许出现"无角色账号"（登录会按最小权限兜底）
            throw new BizException(ErrorCode.SYSTEM_ERROR, "系统角色数据缺失，请联系管理员");
        }
        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(userRole.getId());
        sysUserRoleMapper.insert(link);
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    /**
     * 登录（见设计书 5.1、9.2）：应用层限流（3.5/9.6，超限 5101）→ 校验锁定状态 → 账号状态
     * → 人机校验（曾锁定账号）→ 密码，成功后清计数、写登录日志、刷新最后登录时间，并签发 JWT。
     *
     * <p>匿名写接口不启用接口级幂等（基础层契约第 4 节）：防重放由验证码与失败锁定承担，
     * 幂等键也没有 uid 可挂。
     *
     * @param dto       登录入参
     * @param clientIp  客户端 IP（login_log.ip；调用方已按 X-Forwarded-For 首个地址解析）
     * @param userAgent 客户端 UA（login_log.ua；调用方已按列宽截断）
     * @return token / expiresAt / user（手机号掩码）
     */
    public LoginVo login(LoginDto dto, String clientIp, String userAgent) {
        String phone = dto.getPhone().trim();
        int maxFails = properties.getAuth().getLockMaxFails();

        // ⓪ 应用层限流：固定窗口 1 分钟 10 次，超限 5101（设计书 3.5「应用层限流窗口：登录/取袋/扫码查询」、
        //    9.6「登录……以 Redis 固定窗口限流」）。登录是匿名接口、没有 uid，限流主体取来源 IP
        //    （键口径 rate:{uid或ip}:{api}，见 8.3）；该计数是「按 IP 的接口频次防线」，
        //    与下面按手机号的失败锁定互为补充、各管一个维度。
        //    Redis 异常按 7.2 降级矩阵放行（退化为 Nginx login_zone 按 IP 兜底），
        //    否则 Redis 故障会把整个登录入口打成 5000。
        String rateSubject = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        try {
            rateLimitService.assertAcquire(rateSubject, LOGIN_RATE_API, LOGIN_RATE_LIMIT_PER_MINUTE, RATE_WINDOW);
        } catch (BizException e) {
            throw e;                                    // 5101 限流命中：照常拦截
        } catch (RuntimeException e) {
            log.warn("登录限流计数不可用（Redis 异常），按降级矩阵放行，由 Nginx 按 IP 限流兜底：ip={}, 原因={}",
                    rateSubject, e.toString());
        }

        // ① 锁定拦截：达上限直接拒绝，且不再 INCR——若继续失败也续期 TTL，
        //    攻击者可用持续重试把合法用户永久锁死；现在锁定窗口只会自然到期（≤15 分钟）
        if (readFailCount(phone) >= maxFails) {
            writeLoginLog(null, phone, false, "锁定", clientIp, userAgent);
            throw lockedException(phone);
        }

        // ② 查账号（手机号唯一键，单条命中）
        SysUser user = sysUserMapper.selectOne(Wrappers.lambdaQuery(SysUser.class)
                .eq(SysUser::getPhone, phone));

        // ③ 禁用账号：即使口令正确也拒绝（"封号即失效"，与拦截器口径一致）；先于密码校验，
        //    避免禁用账号继续消耗登录失败计数
        if (user != null && !Constants.STATUS_ENABLED.equals(user.getStatus())) {
            writeLoginLog(user, phone, false, "禁用", clientIp, userAgent);
            throw new BizException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }

        // ④ 曾锁定账号的人机校验（机制见类头）：锁定期满后的首次登录必须过图形验证码
        if (user != null && isLockedOnce(phone)) {
            try {
                captchaService.verifyAndConsume(dto.getCaptchaId(), dto.getCaptchaCode());
            } catch (BizException e) {
                // 验证码缺失/错误同样是登录失败：按 9.2「登录成功/失败均写 login_log」留痕
                writeLoginLog(user, phone, false, "验证码", clientIp, userAgent);
                throw e;
            }
        }

        // ⑤ 账号不存在：与密码错误同文案、同计数（防枚举）
        if (user == null) {
            int fails = incrementFailCount(phone);
            writeLoginLog(null, phone, false, "不存在", clientIp, userAgent);
            throwAfterFailure(phone, fails, maxFails);
        }

        // ⑥ 密码比对（BCrypt；失败计数与 ⑤ 同口径）
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            int fails = incrementFailCount(phone);
            writeLoginLog(user, phone, false, "密码错", clientIp, userAgent);
            throwAfterFailure(phone, fails, maxFails);
        }

        // ⑦ 成功：清失败计数与曾锁定记忆 → 写成功日志 → 刷新最后登录时间/IP → 签发令牌
        clearLoginFailState(phone);
        writeLoginLog(user, phone, true, "", clientIp, userAgent);
        updateLastLogin(user.getId(), clientIp);
        LoginUser principal = buildLoginUser(user);
        String token = jwtService.issue(principal);
        return buildLoginVo(principal, token);
    }

    // ------------------------------------------------------------------
    // 续签 / 登出
    // ------------------------------------------------------------------

    /**
     * 静默续签（见设计书 5.1、7.1）：旧 jti 入黑名单 → 重查数据库拿最新
     * tokenVersion/role/stationId → 签发新令牌，返回与登录相同的 {@link LoginVo} 结构。
     *
     * <p>为什么续签不启用接口级幂等（基础层契约第 4 节）：续签本身就是「让旧凭据失效、
     * 换发新凭据」，幂等回放旧结果（旧 token）会让旧凭据重新可用，与动作语义相反；
     * 重复调用只会各自拉黑一个 jti 并各自换发新 token，天然安全。
     *
     * @param token 当前请求携带的原始 JWT（用于取剩余有效期设置黑名单 TTL）
     */
    public LoginVo refresh(String token) {
        LoginUser current = AuthContext.require();
        // 旧令牌立即拉黑：续签即轮换，旧 jti 不能再用于任何请求（设计书 7.1 第 1 条）
        denyJti(token, current.getJti(), "续签");

        // 重查数据库：角色/站点绑定/token_version/状态以 DB 为准（Redis 只是读加速，见 7.1/7.2）
        SysUser user = sysUserMapper.selectById(current.getUserId());
        if (user == null || !Constants.STATUS_ENABLED.equals(user.getStatus())) {
            // 账号已删除/禁用：不得借续签"复活"失效会话，与拦截器同口径拒绝
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }

        // 新主体必须使用新的 jti：JwtService.issue 在 jti 为空时自动生成；
        // 若沿用旧 jti，新令牌一签发就命中刚写入的黑名单，自断路径
        LoginUser fresh = buildLoginUser(user);
        String newToken = jwtService.issue(fresh);
        return buildLoginVo(fresh, newToken);
    }

    /**
     * 退出登录（见设计书 5.1、7.1）：当前 jti 写入黑名单，TTL = 令牌剩余有效期
     * （令牌自然过期后黑名单键随之消失，不堆积）。
     *
     * <p>为什么登出不启用接口级幂等（基础层契约第 4 节）：登出的语义就是「让当前凭据失效」，
     * 幂等回放旧结果没有意义；重复登出至多重复写一次黑名单，天然幂等。
     *
     * @param token 当前请求携带的原始 JWT（用于取剩余有效期设置黑名单 TTL）
     */
    public void logout(String token) {
        LoginUser current = AuthContext.require();
        denyJti(token, current.getJti(), "登出");
    }

    // ------------------------------------------------------------------
    // 主体构造与出参组装
    // ------------------------------------------------------------------

    /**
     * 由账号记录构造登录主体（登录与续签共用）。
     *
     * <p>{@code jti} 与 {@code expiresAt} 留给 {@link JwtService#issue} 生成/回填
     * （契约 4.3：签发时自动生成 jti 并回填 expiresAt），此处不预设。
     */
    private LoginUser buildLoginUser(SysUser user) {
        LoginUser principal = new LoginUser();
        principal.setUserId(user.getId());
        principal.setPhone(user.getPhone());
        principal.setNickname(user.getNickname());
        principal.setRole(resolveRole(user));
        principal.setStationId(user.getStationId());
        principal.setTokenVersion(user.getTokenVersion() == null ? 0L : user.getTokenVersion().longValue());
        principal.setMustChangePassword(user.getMustChangePassword() != null && user.getMustChangePassword() != 0);
        return principal;
    }

    /**
     * 角色解析（模块契约 8.1）：
     * <ul>
     *   <li>查 {@code sys_user_role} 关联 {@code sys_role} 得角色码，多角色按等级取最高
     *       （PLATFORM_ADMIN &gt; STATION_ADMIN &gt; USER）——与
     *       {@code UserService#highestRoleCode} 同一口径，双角色账号不会被降级；</li>
     *   <li>站点绑定（{@code sys_user.station_id} 非空）只作为<b>下限</b>兜底：设计书 4.1 规定站点管理员
     *       经 station_id 一岗一站绑定（8.2 的库内口径亦保证仅站点管理员非空），角色表数据缺失时
     *       不应把站点管理员误判为 USER——但它不再短路最高角色的判定；</li>
     *   <li>角色表查不到任何角色、且无站点绑定时，兜底 USER（最小权限，fail-closed）。</li>
     * </ul>
     */
    private String resolveRole(SysUser user) {
        String resolved = Constants.ROLE_USER;
        List<SysUserRole> links = sysUserRoleMapper.selectList(
                Wrappers.lambdaQuery(SysUserRole.class).eq(SysUserRole::getUserId, user.getId()));
        if (!links.isEmpty()) {
            List<Long> roleIds = links.stream()
                    .map(SysUserRole::getRoleId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (!roleIds.isEmpty()) {
                // 用 selectByIds（BaseMapper 通用方法）：3.5.17 中 selectBatchIds 已标记过时，避免引入告警
                for (SysRole role : sysRoleMapper.selectByIds(roleIds)) {
                    resolved = higherRole(resolved, role.getRoleCode());
                }
            }
        }
        // 站点绑定下限：修复「station_id 非空即短路返回 STATION_ADMIN」把同时具备 PLATFORM_ADMIN
        // 与站点绑定的双角色账号降级为站点管理员的问题（与 4.2 权限模型、用户列表按最高角色展示
        // 的口径相矛盾，会导致该账号被 @RequireRole("PLATFORM_ADMIN") 接口误拒为 2001）。
        if (user.getStationId() != null
                && roleLevelOf(resolved) < roleLevelOf(Constants.ROLE_STATION_ADMIN)) {
            return Constants.ROLE_STATION_ADMIN;
        }
        return resolved;
    }

    /** 取等级更高的角色码；未知角色码等级记 0，不会覆盖已解析出的合法角色。 */
    private static String higherRole(String left, String right) {
        return roleLevelOf(right) > roleLevelOf(left) ? right : left;
    }

    /** 角色码转等级：与 {@code LoginUser#roleLevel()} 同口径（USER=1、STATION_ADMIN=2、PLATFORM_ADMIN=3）。 */
    private static int roleLevelOf(String roleCode) {
        if (Constants.ROLE_PLATFORM_ADMIN.equals(roleCode)) {
            return 3;
        }
        if (Constants.ROLE_STATION_ADMIN.equals(roleCode)) {
            return 2;
        }
        if (Constants.ROLE_USER.equals(roleCode)) {
            return 1;
        }
        return 0;
    }

    /** 组装登录/续签统一出参：手机号脱敏（设计书 9.5），字段与前端契约逐字对齐。 */
    private LoginVo buildLoginVo(LoginUser principal, String token) {
        LoginUserVo info = new LoginUserVo();
        info.setId(principal.getUserId());
        info.setPhone(MaskUtils.maskPhone(principal.getPhone()));
        info.setNickname(principal.getNickname());
        info.setRole(principal.getRole());
        info.setStationId(principal.getStationId());
        info.setMustChangePassword(principal.isMustChangePassword());

        LoginVo vo = new LoginVo();
        vo.setToken(token);
        vo.setExpiresAt(principal.getExpiresAt());
        vo.setUser(info);
        return vo;
    }

    // ------------------------------------------------------------------
    // 登录日志与账号落库
    // ------------------------------------------------------------------

    /**
     * 写登录日志（登录成功/失败均写，见设计书 7.6/9.2）。
     *
     * <p>统一走审计服务的登录日志通道（异步落库；队列饱和时同步写、绝不丢）；
     * 手机号以掩码形式留存，避免日志本身成为敏感数据泄露面（9.5）。
     *
     * @param user       关联账号；账号不存在传 {@code null}（落 user_id=0）
     * @param phone      本次尝试的手机号（掩码后入库）
     * @param success    是否成功（result 1/0）
     * @param failReason 失败原因（不存在/密码错/锁定/禁用/验证码）；成功传空串
     */
    private void writeLoginLog(SysUser user, String phone, boolean success, String failReason,
                               String clientIp, String userAgent) {
        LoginLog entry = new LoginLog();
        entry.setUserId(user == null ? 0L : user.getId());
        // phone_mask 列宽 20：按"列宽 − 1"传参，给省略号留位（MaskUtils.truncate 口径）
        entry.setPhoneMask(MaskUtils.truncate(MaskUtils.maskPhone(phone), 19));
        entry.setResult(success ? 1 : 0);
        entry.setFailReason(failReason == null ? "" : failReason);
        entry.setIp(clientIp == null ? "" : clientIp);
        entry.setUa(userAgent == null ? "" : userAgent);
        // createdAt 不赋值：库端 DEFAULT CURRENT_TIMESTAMP(3) 生成（见实体注释）
        auditLogService.recordLogin(entry);
    }

    /** 登录成功刷新最后登录时间与 IP（单条 UPDATE 原子，无需事务）。 */
    private void updateLastLogin(Long userId, String clientIp) {
        sysUserMapper.update(null, Wrappers.lambdaUpdate(SysUser.class)
                .eq(SysUser::getId, userId)
                .set(SysUser::getLastLoginAt, LocalDateTime.now())
                .set(SysUser::getLastLoginIp, clientIp == null ? "" : clientIp));
    }

    /**
     * 失败计数达到上限时：写「曾锁定」记忆（供锁定期满后的人机校验）并抛 1002；
     * 未达上限抛统一口径的 3001（前端在登录页对 3001 做轻提示，见 Login.vue）。
     *
     * <p>只有达阈值的那次（及之后）才返回 1002：锁定语义是「连续失败达阈值」（设计书 9.2）。
     */
    private void throwAfterFailure(String phone, int fails, int maxFails) {
        if (fails >= maxFails) {
            markLockedOnce(phone);
            throw lockedException(phone);
        }
        throw new BizException(ErrorCode.PARAM_INVALID, LOGIN_FAILED_MESSAGE);
    }

    /** 构造 1002 异常：message 携带剩余锁定分钟数，前端原样展示（Login.vue 对 1002 的处理）。 */
    private BizException lockedException(String phone) {
        long minutes = properties.getAuth().getLockMinutes();
        try {
            Long seconds = redisTemplate.getExpire(RedisKeys.loginFail(phone), TimeUnit.SECONDS);
            if (seconds != null && seconds > 0) {
                minutes = Math.max(1, (seconds + 59) / 60);
            }
        } catch (Exception e) {
            log.warn("Redis 锁定剩余时长读取失败，按配置值提示：phone={}, reason={}",
                    MaskUtils.maskPhone(phone), e.toString());
        }
        return new BizException(ErrorCode.ACCOUNT_LOCKED, "账号已锁定，请 " + minutes + " 分钟后重试");
    }

    // ------------------------------------------------------------------
    // Redis 辅助（全部按设计书 7.2 降级口径捕获异常、记 WARN，不静默也不硬失败）
    // ------------------------------------------------------------------

    /** 读失败计数；Redis 故障按 0 处理（降级期锁定由 Nginx login_zone 按 IP 限流兜底）。 */
    private int readFailCount(String phone) {
        try {
            String raw = redisTemplate.opsForValue().get(RedisKeys.loginFail(phone));
            return raw == null ? 0 : Integer.parseInt(raw.trim());
        } catch (Exception e) {
            log.warn("Redis 登录失败计数读取失败，降级为未锁定（Nginx 按 IP 限流兜底，设计书 7.2）：phone={}, reason={}",
                    MaskUtils.maskPhone(phone), e.toString());
            return 0;
        }
    }

    /**
     * 失败计数 +1，并把 TTL 重置为 {@code sems.auth.lock-minutes}：
     * 锁定窗口从最近一次失败起算，连续失败不会因 TTL 先期耗尽而提前解锁。
     *
     * @return 累计失败次数（Redis 故障返回 0，调用方按"未达上限"继续常规失败流程）
     */
    private int incrementFailCount(String phone) {
        try {
            String key = RedisKeys.loginFail(phone);
            Long count = redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, Duration.ofMinutes(properties.getAuth().getLockMinutes()));
            return count == null ? 0 : count.intValue();
        } catch (Exception e) {
            log.warn("Redis 登录失败计数写入失败，降级放行（Nginx 按 IP 限流兜底，设计书 7.2）：phone={}, reason={}",
                    MaskUtils.maskPhone(phone), e.toString());
            return 0;
        }
    }

    /** 登录成功清态：删失败计数与「曾锁定」记忆（设计书 9.2「恢复后重置计数」）。 */
    private void clearLoginFailState(String phone) {
        try {
            redisTemplate.delete(List.of(RedisKeys.loginFail(phone), lockedOnceKey(phone)));
        } catch (Exception e) {
            log.warn("Redis 登录失败计数清理失败（计数将在 TTL 到期后自然消失）：phone={}, reason={}",
                    MaskUtils.maskPhone(phone), e.toString());
        }
    }

    /** 「曾锁定」记忆是否存在（读失败按未标记处理：仅影响一次人机校验的严格性，不阻塞登录）。 */
    private boolean isLockedOnce(String phone) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(lockedOnceKey(phone)));
        } catch (Exception e) {
            log.warn("Redis 曾锁定标记读取失败，按未标记处理：phone={}, reason={}",
                    MaskUtils.maskPhone(phone), e.toString());
            return false;
        }
    }

    /** 写「曾锁定」记忆（TTL 1 天）；写失败仅记 WARN：该账号锁定期满后将不强制验证码。 */
    private void markLockedOnce(String phone) {
        try {
            redisTemplate.opsForValue().set(lockedOnceKey(phone), "1", LOCKED_ONCE_TTL);
        } catch (Exception e) {
            log.warn("Redis 曾锁定标记写入失败（该账号锁定期满后将不强制图形验证码）：phone={}, reason={}",
                    MaskUtils.maskPhone(phone), e.toString());
        }
    }

    private static String lockedOnceKey(String phone) {
        return LOCKED_ONCE_KEY_PREFIX + phone;
    }

    /**
     * 把 jti 写入 Redis 黑名单（登出/续签共用），TTL 取该令牌的剩余有效期——
     * 令牌自然过期后黑名单键也随之消失（设计书 7.1 第 1 条、8.3）。
     *
     * <p>Redis 故障时 fail-open 并记 WARN：与 AuthInterceptor 黑名单校验的降级口径一致，
     * 设备级失效窗口 ≤ 令牌剩余有效期（设计书 7.2 风险接受）。
     */
    private void denyJti(String token, String jti, String scene) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        Duration remaining = jwtService.remaining(token);
        if (remaining.isZero() || remaining.isNegative()) {
            // 已过期/不可解析：没有剩余有效期就无需拉黑（JwtService.remaining 的约定）
            return;
        }
        try {
            redisTemplate.opsForValue().set(RedisKeys.authDeny(jti), "1", remaining);
        } catch (Exception e) {
            log.warn("{}：jti 黑名单写入失败，降级 fail-open（设备级失效窗口 ≤ 令牌剩余有效期）：jti={}, reason={}",
                    scene, jti, e.toString());
        }
    }
}
