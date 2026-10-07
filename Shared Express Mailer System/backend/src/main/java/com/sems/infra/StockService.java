package com.sems.infra;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.config.SemsProperties;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.statemachine.BagStatus;
import com.sems.security.JwtService;
import com.sems.security.LoginUser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 站点库存计数服务（Redis 侧，见《系统设计说明书》7.2「Redis 库存并发扣减方案」）。
 *
 * <p><b>职责边界</b>：本类只执行 Redis 计数与 Lua 脚本，不写任何业务校验——订单/袋状态、
 * 站点归属等校验一律在模块层完成；扣减失败按 Lua 返回值抛对应 {@link BizException}，
 * 由模块层决定重试或改走 DB 行级降级路径。
 *
 * <p><b>两阶段与回补路径</b>（7.2）：
 * <ul>
 *   <li>{@link #tryDeduct}：单脚本原子完成「在持袋数&lt;上限 + 日频次&lt;上限 + 库存 DECR + 写预扣凭据」；</li>
 *   <li>{@link #compensate}：R0 同步补偿，以预扣凭据存在性做幂等；</li>
 *   <li>{@link #deleteHold}：R1/R2 仅删凭据（不回补库存、不释放在持额度）；</li>
 *   <li>{@link #rebuildStationStock}：按 DB 权威口径 COUNT(IDLE) 重建计数并写哨兵键（初始化/恢复）。</li>
 * </ul>
 *
 * <p><b>熔断降级</b>（7.2「降级触发/恢复判定」）：连续 {@value #FAIL_THRESHOLD} 次 Redis
 * 命令异常（{@value #FAIL_WINDOW_MS} 毫秒窗口内）即打开熔断，{@link #isDegraded()} 暴露；
 * 此后 {@link #tryDeduct} 快速失败（5102），由上层改走 DB 行级降级路径。恢复判定为连续
 * {@value #PING_RECOVER_THRESHOLD} 次 PING 成功——由独立守护线程低频探测（不占用请求线程），
 * 随后按 7.2「恢复动作」重建 stock:available、borrow:open、borrow:daily 与 stock:sentinel
 * 后再关闭熔断；每日开单计数对账（7.5）继续做常规纠偏。
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    /**
     * 熔断阈值与窗口：3 次 / 10 秒（设计书 7.2 正文直接规定）。
     * 这类技术参数不在 SemsProperties（11.2 键清单）覆盖范围内，故以常量固守单一出处；
     * 业务时限/限额（预扣 TTL、在持上限、日频次上限等）一律读 {@link SemsProperties}。
     */
    private static final int FAIL_THRESHOLD = 3;

    private static final long FAIL_WINDOW_MS = 10_000L;

    /** 恢复判定：连续 PING 成功次数（7.2「恢复判定」）。 */
    private static final int PING_RECOVER_THRESHOLD = 3;

    /**
     * 恢复探测周期 5 秒（7.2 未规定具体周期）：探测跑在独立守护线程上，串行执行，
     * 与 3 秒的 Redis 命令超时（spring.data.redis.timeout）同量级，兼顾恢复及时性与探测开销。
     */
    private static final long PROBE_INTERVAL_MS = 5_000L;

    /** stock:init 互斥标记 TTL 300 秒（设计书 8.3 键表）：进程中途崩溃时标记自愈。 */
    private static final Duration INIT_MARKER_TTL = Duration.ofSeconds(300);

    /** 仅锁持有者可原子发布计数并释放标记，TTL到期后旧持有者不能覆盖新计数。 */
    private static final DefaultRedisScript<Long> COMPLETE_REBUILD = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end "
            + "redis.call('SET', KEYS[2], ARGV[2]) redis.call('SET', KEYS[3], '1') "
            + "redis.call('DEL', KEYS[1]) return 1", Long.class);
    /** 失败时只释放自己的锁，绝不删除后来者的新锁。 */
    private static final DefaultRedisScript<Long> RELEASE_INIT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);


    /** borrow:open 的 14 天滑动 TTL（8.3 键表）：仅在截断回写时补回（SET 会清除原 TTL）。 */
    private static final Duration OPEN_COUNT_TTL = Duration.ofDays(14);

    /** borrow:daily 键名的日期后缀格式。 */
    private static final DateTimeFormatter DAILY_KEY_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 恢复重建的分批条数（7.5：扫描类任务分批，每批最多 500 条，避免超长 IN 列表）。 */
    private static final int REBUILD_BATCH_SIZE = 500;

    /**
     * {@code borrow:open:} 键前缀：由键工厂样例键裁出，避免在恢复重建里另行硬编码键名
     * （键名唯一出处是 {@link RedisKeys} 与设计书 8.3）。
     */
    private static final String BORROW_OPEN_KEY_PREFIX =
            RedisKeys.borrowOpen(0L).substring(0, RedisKeys.borrowOpen(0L).lastIndexOf(':') + 1);

    /** 库存原子扣减脚本（KEYS 1-6、ARGV 1-5 与设计书 7.2 脚本逐行一致）。 */
    private DefaultRedisScript<Long> deductScript;

    /** R0 同步补偿脚本（凭据存在性幂等）。 */
    private DefaultRedisScript<Long> compensateScript;

    private final StringRedisTemplate redisTemplate;

    private final SemsProperties properties;

    private final StationMapper stationMapper;

    private final BagMapper bagMapper;

    private final BagOrderMapper bagOrderMapper;

    private final JwtService jwtService;

    // ===== 熔断器状态（写操作统一在 breakerLock 内，degraded 用 volatile 供无锁快路径读取） =====

    /** 熔断是否打开。 */
    private volatile boolean degraded = false;

    /** 当前连续失败次数（窗口起点为连续失败中的第一次）。 */
    private int failStreak = 0;

    /** 当前连续失败窗口的起点（毫秒时间戳）。 */
    private long failStreakStartAt = 0L;

    /** 熔断打开后连续 PING 成功次数。 */
    private final AtomicInteger pingSuccess = new AtomicInteger();

    private final Object breakerLock = new Object();

    private final Object probeLock = new Object();

    /** 恢复探测线程（仅熔断打开期间存在）。 */
    private volatile ScheduledExecutorService probeExecutor;

    /** 计数重建互斥锁：串行化本机所有重建（多实例场景由 Redis 的 stock:init 标记兜底）。 */
    private final ReentrantLock rebuildMutex = new ReentrantLock();

    public StockService(StringRedisTemplate redisTemplate, SemsProperties properties,
                        StationMapper stationMapper, BagMapper bagMapper, BagOrderMapper bagOrderMapper,
                        JwtService jwtService) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.stationMapper = stationMapper;
        this.bagMapper = bagMapper;
        this.bagOrderMapper = bagOrderMapper;
        this.jwtService = jwtService;
    }

    /**
     * 从 classpath 加载两个 Lua 脚本并设定返回值为整数。
     * 脚本缺失属部署错误，启动即失败，避免运行期把部署问题伪装成 5102 降级。
     */
    @PostConstruct
    void loadScripts() {
        ClassPathResource deductResource = new ClassPathResource("lua/stock_deduct.lua");
        ClassPathResource compensateResource = new ClassPathResource("lua/stock_compensate.lua");
        if (!deductResource.exists() || !compensateResource.exists()) {
            throw new IllegalStateException("缺少库存 Lua 脚本（lua/stock_deduct.lua 或 lua/stock_compensate.lua）");
        }

        deductScript = new DefaultRedisScript<>();
        deductScript.setScriptSource(new ResourceScriptSource(deductResource));
        deductScript.setResultType(Long.class);

        compensateScript = new DefaultRedisScript<>();
        compensateScript.setScriptSource(new ResourceScriptSource(compensateResource));
        compensateScript.setResultType(Long.class);

        log.info("库存 Lua 脚本加载完成：lua/stock_deduct.lua、lua/stock_compensate.lua");
    }

    /** 应用关闭时停止恢复探测线程，避免线程泄漏。 */
    @PreDestroy
    void shutdownProbe() {
        stopProbe();
    }

    /**
     * 申领阶段一：执行 Lua 原子扣减。失败按返回值抛对应 {@link BizException}（7.2 失败分支矩阵）：
     * 0→4101 库存不足、-1→5102 计数未就绪、-2→5103 对账中、-3→4104 在持袋数超限、
     * -4→4109 取袋频次超限。0/-1/-2/-3/-4 均未扣减、未自增，无需回补。
     *
     * <p>熔断打开或 Redis 命令异常时抛 5102（提示「库存服务降级中」），由上层捕获后
     * 改走 DB 行级降级路径。
     *
     * @param holdJson 预扣凭据 JSON（orderNo/stationId/bagCode），写入 {@code stock:hold:{orderNo}}
     */
    public void tryDeduct(String orderNo, Long stationId, Long userId, String holdJson) {
        if (degraded) {
            throw new BizException(ErrorCode.STOCK_NOT_READY, "库存服务降级中，请稍后重试");
        }
        List<String> keys = List.of(
                RedisKeys.stockAvailable(stationId),
                RedisKeys.stockHold(orderNo),
                RedisKeys.stockInit(stationId),
                RedisKeys.stockReconcile(stationId),
                RedisKeys.borrowOpen(userId),
                RedisKeys.borrowDaily(userId, LocalDate.now().format(DAILY_KEY_DATE)));
        Long result;
        try {
            result = redisTemplate.execute(deductScript, keys,
                    holdJson,                                                              // ARGV[1] 预扣凭据 JSON
                    String.valueOf(properties.getHold().getTtl().toSeconds()),             // ARGV[2] 凭据 TTL 秒
                    String.valueOf(properties.getBorrow().getOpenLimit()),                 // ARGV[3] 在持袋数上限
                    String.valueOf(properties.getBorrow().getDailyLimit()),                // ARGV[4] 取袋日频次上限
                    String.valueOf(dailyCounterTtlSeconds()));                             // ARGV[5] 日频次键 TTL 秒
            recordRedisSuccess();
        } catch (Exception e) {
            recordRedisFailure(e);
            log.error("库存扣减 Lua 执行异常（orderNo={}, stationId={}）：{}", orderNo, stationId, e.getMessage(), e);
            throw new BizException(ErrorCode.STOCK_NOT_READY, "库存服务降级中，请稍后重试");
        }
        if (result == null) {
            // 脚本必回整数；拿到空结果说明执行链路异常，按未就绪快速失败（DB 降级路径仍安全）
            log.error("库存扣减 Lua 返回空结果（orderNo={}, stationId={}），按未就绪处理", orderNo, stationId);
            throw new BizException(ErrorCode.STOCK_NOT_READY, "库存服务降级中，请稍后重试");
        }
        switch (result.intValue()) {
            case 1:
                return;                                             // 成功：Lua 内已完成「扣减+凭据+计数」四步
            case 0:
                throw new BizException(ErrorCode.STOCK_INSUFFICIENT);
            case -1:
                throw new BizException(ErrorCode.STOCK_NOT_READY);
            case -2:
                throw new BizException(ErrorCode.STOCK_RECONCILING);
            case -3:
                throw new BizException(ErrorCode.HOLDING_LIMIT);
            case -4:
                throw new BizException(ErrorCode.BORROW_DAILY_LIMIT);
            default:
                log.error("库存扣减 Lua 返回未知结果 {}（orderNo={}），按未就绪处理", result, orderNo);
                throw new BizException(ErrorCode.STOCK_NOT_READY, "库存服务降级中，请稍后重试");
        }
    }

    /** R0/R1/R2 删除预扣凭据（不存在则忽略）。R1/R2 只删凭据，不回补库存、不释放在持额度。 */
    public void deleteHold(String orderNo) {
        try {
            redisTemplate.delete(RedisKeys.stockHold(orderNo));
            recordRedisSuccess();
        } catch (Exception e) {
            recordRedisFailure(e);
            log.warn("删除预扣凭据失败（orderNo={}）：{}", orderNo, e.getMessage());
        }
    }

    /**
     * R0 同步补偿：单个 Lua 原子完成「存在凭据才 DEL + INCR 库存 + 在持袋数 −1」。
     *
     * <p>以预扣凭据存在性作幂等凭据，防止重复补偿：返回 {@code false} 表示凭据不存在
     * （从未预扣或已补偿过），不代表失败；Redis 异常返回 {@code false} 并记 ERROR，
     * 交由 R4 与每日开单计数对账兜底（7.2）。
     *
     * @return 本次是否真的执行了补偿
     */
    public boolean compensate(String orderNo, Long stationId, Long userId) {
        List<String> keys = List.of(
                RedisKeys.stockAvailable(stationId),
                RedisKeys.stockHold(orderNo),
                RedisKeys.borrowOpen(userId));
        try {
            // ARGV[1] 在补偿脚本中保留未用，占位以与扣减脚本参数位对齐
            Long result = redisTemplate.execute(compensateScript, keys, "");
            recordRedisSuccess();
            if (Long.valueOf(1L).equals(result)) {
                log.info("R0 库存补偿完成：orderNo={}, stationId={}, userId={}", orderNo, stationId, userId);
                return true;
            }
            log.info("R0 库存补偿跳过：预扣凭据不存在（orderNo={}），视为未预扣或已补偿", orderNo);
            return false;
        } catch (Exception e) {
            recordRedisFailure(e);
            log.error("R0 库存同步补偿失败（orderNo={}, stationId={}, userId={}），交由 R4 与每日开单计数对账兜底：{}",
                    orderNo, stationId, userId, e.getMessage(), e);
            return false;
        }
    }

    /** 离开已分配/使用中两态时在持袋数 −1（可为负则截断为 0 写回）。撤回/超时取消不调用本方法（7.2 R1/R2）。 */
    public void decrementOpen(Long userId) {
        if (degraded) {
            log.debug("库存熔断打开，跳过在持袋数递减（userId={}），恢复后由开单计数对账纠偏", userId);
            return;
        }
        String key = RedisKeys.borrowOpen(userId);
        try {
            Long current = redisTemplate.opsForValue().decrement(key);
            recordRedisSuccess();
            if (current != null && current < 0L) {
                // 计数本已为 0 仍被扣减（历史漂移/重复事件）→ 截断为 0 写回；
                // SET 会清除原 TTL，故显式补回 14 天滑动期（8.3）；每日开单计数对账亦会 SETEX 续期（7.5）
                redisTemplate.opsForValue().set(key, "0", OPEN_COUNT_TTL);
                log.warn("在持袋数计数已扣减至负数（userId={}），截断为 0 写回", userId);
            }
        } catch (Exception e) {
            recordRedisFailure(e);
            log.warn("在持袋数递减失败（userId={}），本次跳过，由每日开单计数对账纠偏：{}", userId, e.getMessage());
        }
    }

    /** 站点可用库存 +delta / −delta（激活上架、回收、上架入库、调拨等；0 不写）。 */
    public void increaseAvailable(Long stationId, int delta) {
        if (delta == 0) {
            return;
        }
        if (degraded) {
            // 降级期间不写 Redis 计数，避免恢复后计数偏高（7.2）；恢复流程按 DB 重建
            log.debug("库存熔断打开，跳过站点 {} 库存计数调整（delta={}）", stationId, delta);
            return;
        }
        try {
            redisTemplate.opsForValue().increment(RedisKeys.stockAvailable(stationId), delta);
            recordRedisSuccess();
        } catch (Exception e) {
            recordRedisFailure(e);
            log.warn("站点 {} 库存计数调整失败（delta={}），本次跳过，由 R4 对账纠正：{}", stationId, delta, e.getMessage());
        }
    }

    /**
     * 读站点可用库存（Redis 视图）。
     * 键不存在返回 {@code null}，调用方据此触发 {@link #rebuildStationStock}（契约 5.2）；
     * 熔断打开或读失败同样返回 {@code null}，由调用方回退 DB 权威口径。
     */
    public Integer getAvailable(Long stationId) {
        if (degraded) {
            return null;
        }
        String key = RedisKeys.stockAvailable(stationId);
        try {
            String value = redisTemplate.opsForValue().get(key);
            recordRedisSuccess();
            if (value == null) {
                return null;
            }
            try {
                int count = Integer.parseInt(value);
                if (count < 0) {
                    log.warn("站点 {} 库存计数为负数，回退数据库查询并等待对账", stationId);
                    return null;
                }
                return count;
            } catch (NumberFormatException e) {
                log.warn("站点 {} 库存计数非整数（值={}），按未就绪处理，等待 R4 对账重建", stationId, value);
                return null;
            }
        } catch (Exception e) {
            recordRedisFailure(e);
            log.warn("读取站点 {} 库存计数失败，按未就绪返回 null：{}", stationId, e.getMessage());
            return null;
        }
    }

    /**
     * 重建某站点计数（初始化/恢复，7.2）：SETNX init 互斥成功 → DEL 旧计数 → SET 可用计数
     * （DB 权威 COUNT(IDLE)，0 也写入）→ SET 哨兵 → DEL init。
     * 失败仅记日志，由 R4 对账兜底并告警，不向上抛。
     */
    public void rebuildStationStock(Long stationId) {
        rebuildMutex.lock();
        try {
            rebuildStation(stationId);
        } finally {
            rebuildMutex.unlock();
        }
    }

    /**
     * 遍历 station 表所有 ENABLED 站点重建计数（应用启动/Redis 恢复时单线程调用，7.2）。
     * 本方法幂等；与恢复重建共用同一把互斥锁，不会并发执行。
     */
    public void initializeAllStations() {
        rebuildMutex.lock();
        try {
            int failed = rebuildAllEnabledStations();
            if (failed > 0) {
                log.error("站点库存计数初始化存在 {} 个失败站点，由 R4 对账兜底并告警", failed);
            }
        } catch (Exception e) {
            log.error("站点库存计数初始化失败（站点查询异常等），由 R4 对账兜底：{}", e.getMessage(), e);
        } finally {
            rebuildMutex.unlock();
        }
    }

    /** 熔断是否打开（7.2 降级触发）；上层的申领流程据此改走 DB 行级降级路径。 */
    public boolean isDegraded() {
        return degraded;
    }

    /** 站点计数哨兵键是否存在（7.2「哨兵键校验」）：false 即视为未初始化，调用方应触发重建。 */
    public boolean isStationReady(Long stationId) {
        if (degraded) {
            return false;
        }
        try {
            Boolean exists = redisTemplate.hasKey(RedisKeys.stockSentinel(stationId));
            recordRedisSuccess();
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            recordRedisFailure(e);
            log.warn("校验站点 {} 计数哨兵失败，按未就绪处理：{}", stationId, e.getMessage());
            return false;
        }
    }

    /**
     * 静默续签（契约 5.2 划入本类）：剩余有效期 ≤ {@code sems.jwt.renew-threshold}（默认 30m）
     * 时换发新 token，否则原样返回（见《系统设计说明书》3.5 会话口径与 7.1）。
     *
     * <p>换发前先把旧 jti 写入黑名单（{@code auth:deny:{jti}}，TTL=剩余有效期），与 7.1
     * 「登出/续签时拉黑旧 jti」一致；黑名单写失败按降级期 fail-open 处理（记 WARN，
     * 设备级登出失效窗口 ≤ 剩余有效期，见 7.2 降级矩阵），不阻断续签。
     */
    public String renewToken(String token) {
        LoginUser user = jwtService.parse(token);
        Duration remaining = jwtService.remaining(token);
        Duration renewThreshold = properties.getJwt().getRenewThreshold();
        if (remaining != null && renewThreshold != null && remaining.compareTo(renewThreshold) > 0) {
            return token;
        }
        String oldJti = user.getJti();
        if (oldJti != null && remaining != null && !remaining.isZero() && !remaining.isNegative()) {
            try {
                redisTemplate.opsForValue().set(RedisKeys.authDeny(oldJti), "1", remaining);
                recordRedisSuccess();
            } catch (Exception e) {
                recordRedisFailure(e);
                log.warn("续签拉黑旧 jti 失败（降级期 fail-open）：jti={}, 原因={}", oldJti, e.getMessage());
            }
        }
        user.setJti(jwtService.newJti());   // 换发新 jti，避免与刚拉黑的旧凭据同号
        return jwtService.issue(user);
    }

    // ==================== 熔断与恢复（私有） ====================

    /**
     * 记录一次 Redis 命令异常：连续 {@value #FAIL_THRESHOLD} 次（{@value #FAIL_WINDOW_MS}
     * 毫秒窗口内，窗口起点为连续失败中的第一次）即打开熔断（7.2「降级触发」）。
     */
    private void recordRedisFailure(Throwable cause) {
        boolean opened;
        synchronized (breakerLock) {
            if (degraded) {
                return;                     // 已熔断：失败无需重复计数与告警
            }
            long now = System.currentTimeMillis();
            if (failStreak == 0 || now - failStreakStartAt > FAIL_WINDOW_MS) {
                failStreak = 1;
                failStreakStartAt = now;
            } else {
                failStreak++;
            }
            opened = failStreak >= FAIL_THRESHOLD;
            if (opened) {
                degraded = true;
                pingSuccess.set(0);
            }
        }
        if (opened) {
            log.error("Redis 连续 {} 次命令异常（{}ms 窗口），库存熔断打开：申领改走 DB 行级降级路径",
                    FAIL_THRESHOLD, FAIL_WINDOW_MS, cause);
            startProbe();
        }
    }

    /**
     * 记录一次 Redis 命令成功：清零连续失败计数。
     * 熔断打开期间普通命令的成功不提前关闭熔断——恢复只认连续 PING 成功（7.2「恢复判定」）。
     */
    private void recordRedisSuccess() {
        synchronized (breakerLock) {
            if (degraded) {
                return;
            }
            failStreak = 0;
            failStreakStartAt = 0L;
        }
    }

    /** 启动恢复探测线程（幂等；仅熔断打开时调用）。 */
    private void startProbe() {
        synchronized (probeLock) {
            if (probeExecutor != null && !probeExecutor.isShutdown()) {
                return;
            }
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sems-stock-probe");
                thread.setDaemon(true);
                return thread;
            });
            probeExecutor = executor;
            executor.scheduleWithFixedDelay(this::probeOnce, PROBE_INTERVAL_MS, PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS);
            log.warn("库存熔断恢复探测已启动：每 {}s PING 一次，连续 {} 次成功则重建计数并关闭熔断",
                    PROBE_INTERVAL_MS / 1000, PING_RECOVER_THRESHOLD);
        }
    }

    /** 停止恢复探测线程。 */
    private void stopProbe() {
        synchronized (probeLock) {
            if (probeExecutor != null) {
                probeExecutor.shutdown();
                probeExecutor = null;
            }
        }
    }

    /** 一次恢复探测：PING 成功则累计连击，连续 {@value #PING_RECOVER_THRESHOLD} 次成功即触发恢复。 */
    private void probeOnce() {
        if (!degraded) {
            stopProbe();                    // 熔断已被关闭，探测线程功成身退
            return;
        }
        boolean alive;
        try {
            alive = ping();
        } catch (Exception e) {
            alive = false;
            log.debug("Redis 恢复探测 PING 失败：{}", e.getMessage());
        }
        if (!alive) {
            pingSuccess.set(0);
            return;
        }
        int streak = pingSuccess.incrementAndGet();
        if (streak < PING_RECOVER_THRESHOLD) {
            log.info("Redis 恢复探测：连续第 {}/{} 次 PING 成功", streak, PING_RECOVER_THRESHOLD);
            return;
        }
        log.warn("Redis PING 连续 {} 次成功，判定已恢复，开始重建站点库存计数", PING_RECOVER_THRESHOLD);
        recoverFromDegradation();
    }

    /** 单次 PING（连接层异常直接抛出，由调用方按探测失败处理）。 */
    private boolean ping() {
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            return false;
        }
        try (RedisConnection connection = factory.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping());
        }
    }

    /**
     * 恢复流程（7.2「恢复动作」）：重建 stock:available、stock:sentinel、borrow:open、borrow:daily
     * → 全部成功才关闭熔断；失败保留下轮探测重试。
     */
    private void recoverFromDegradation() {
        boolean rebuilt;
        try {
            if (rebuildMutex.tryLock()) {
                try {
                    rebuilt = rebuildAllCounters() == 0;
                } finally {
                    rebuildMutex.unlock();
                }
            } else {
                rebuilt = false;            // 已有重建在进行：等下一轮探测
            }
        } catch (Exception e) {
            rebuilt = false;
            log.error("Redis 恢复后重建计数异常：{}", e.getMessage(), e);
        }
        if (!rebuilt) {
            pingSuccess.set(0);
            log.error("Redis 恢复后计数重建未全部成功，熔断保持打开，等待下一轮探测重试");
            return;
        }
        synchronized (breakerLock) {
            degraded = false;
            failStreak = 0;
            failStreakStartAt = 0L;
        }
        pingSuccess.set(0);
        log.warn("Redis 已恢复：站点库存计数、哨兵与借出计数（borrow:open/borrow:daily）重建完成，库存熔断关闭");
        stopProbe();
    }

    /**
     * 执行 7.2「恢复动作」要求的全部重建，返回失败项数：降级期间不写 Redis 计数，
     * 恢复时必须以 DB 权威口径整值重建 stock:available 与 stock:sentinel（逐启用站点）、
     * borrow:open、borrow:daily，全部成功后才允许关闭熔断。
     */
    private int rebuildAllCounters() {
        int failed = rebuildAllEnabledStations();
        if (!rebuildBorrowOpenCounters()) {
            failed++;
        }
        if (!rebuildBorrowDailyCounters()) {
            failed++;
        }
        return failed;
    }

    // ==================== 计数重建（私有） ====================

    /** 遍历全部 ENABLED 站点重建计数；返回失败站点数。调用方须持有 {@code rebuildMutex}。 */
    private int rebuildAllEnabledStations() {
        List<Long> stationIds = listEnabledStationIds();
        int failed = 0;
        for (Long stationId : stationIds) {
            if (!rebuildStation(stationId)) {
                failed++;
            }
        }
        log.info("站点库存计数重建：启用站点 {} 个，失败 {} 个", stationIds.size(), failed);
        return failed;
    }

    /**
     * 重建在持袋数计数 {@code borrow:open}（7.2 恢复动作、3.5 口径）：DB 权威口径 = 袋处于
     * {已分配, 使用中} 的<b>当前持有订单</b>（同袋 id 最大的订单）数，逐用户整值
     * SETEX 14 天（滑动 TTL 显式续期，3.5/8.3）。
     *
     * <p>同时扫描既有 {@code borrow:open:*} 键，把「降级期间袋已归还但递减被跳过」的用户一并归零：
     * 只重建在持用户会留下残留旧值，把这些用户挡在 4104 之外直到 TTL 到期。
     *
     * @return 重建是否成功；任一 Redis 写入失败返回 false，由下一轮探测重试
     */
    private boolean rebuildBorrowOpenCounters() {
        try {
            Map<Long, Integer> holderCounts = countHeldBagsByUser();
            Set<Long> userIds = new HashSet<>(holderCounts.keySet());
            userIds.addAll(scanExistingBorrowOpenUids());
            for (Long userId : userIds) {
                redisTemplate.opsForValue().set(RedisKeys.borrowOpen(userId),
                        String.valueOf(holderCounts.getOrDefault(userId, 0)), OPEN_COUNT_TTL);
            }
            log.info("borrow:open 重建完成：在持用户 {} 人，处理既有计数键 {} 个",
                    holderCounts.size(), userIds.size());
            return true;
        } catch (Exception e) {
            log.error("borrow:open 重建失败，熔断保持打开等待下一轮探测重试：{}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 逐用户统计「当前持有袋数」：袋处于已分配/使用中两态，持有者取该袋 id 最大的订单的用户
     * （与 7.2 降级路径、每日开单计数对账同一口径——历史订单不重复计入，已取消但袋未回收的计入）。
     */
    private Map<Long, Integer> countHeldBagsByUser() {
        List<String> bagCodes = bagMapper.selectList(new QueryWrapper<Bag>()
                        .select("bag_code")
                        .in("status", BagStatus.ALLOCATED.code(),
                                BagStatus.IN_USE.code()))
                .stream().map(Bag::getBagCode).toList();
        Map<String, BagOrder> latestByBag = new HashMap<>();
        for (int from = 0; from < bagCodes.size(); from += REBUILD_BATCH_SIZE) {
            List<String> chunk = bagCodes.subList(from, Math.min(from + REBUILD_BATCH_SIZE, bagCodes.size()));
            List<BagOrder> orders = bagOrderMapper.selectList(new LambdaQueryWrapper<BagOrder>()
                    .select(BagOrder::getId, BagOrder::getBagCode, BagOrder::getUserId)
                    .in(BagOrder::getBagCode, chunk));
            for (BagOrder order : orders) {
                latestByBag.merge(order.getBagCode(), order, (a, b) -> a.getId() >= b.getId() ? a : b);
            }
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (BagOrder holder : latestByBag.values()) {
            if (holder.getUserId() != null) {
                counts.merge(holder.getUserId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 扫描既有 {@code borrow:open:*} 键并裁出 uid（无键时返回空集合；脏键跳过不失败整轮）。 */
    private Set<Long> scanExistingBorrowOpenUids() {
        Set<Long> userIds = new HashSet<>();
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions()
                .match(BORROW_OPEN_KEY_PREFIX + "*")
                .count(REBUILD_BATCH_SIZE)
                .build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (key == null || key.length() <= BORROW_OPEN_KEY_PREFIX.length()) {
                    continue;
                }
                try {
                    userIds.add(Long.valueOf(key.substring(BORROW_OPEN_KEY_PREFIX.length())));
                } catch (NumberFormatException e) {
                    log.warn("borrow:open 键名不符合 uid 后缀约定，跳过不计：{}", key);
                }
            }
        }
        return userIds;
    }

    /**
     * 重建取袋日频次计数 {@code borrow:daily:{uid}:{yyyyMMdd}}（7.2 恢复动作、3.5 口径）：
     * DB 口径 = 当日订单数（与降级路径一致），与键内既有值取<b>较大者</b>——
     * 日频次按申领次数计且「含已撤回/已取消」（3.5），R0 补偿不退还的尝试次数不能被重建抹掉。
     *
     * @return 重建是否成功；任一 Redis 操作失败返回 false，由下一轮探测重试
     */
    private boolean rebuildBorrowDailyCounters() {
        try {
            LocalDate today = LocalDate.now();
            List<BagOrder> todayOrders = bagOrderMapper.selectList(new LambdaQueryWrapper<BagOrder>()
                    .select(BagOrder::getUserId)
                    .ge(BagOrder::getCreatedAt, today.atStartOfDay()));
            Map<Long, Integer> dbCounts = new HashMap<>();
            for (BagOrder order : todayOrders) {
                if (order.getUserId() != null) {
                    dbCounts.merge(order.getUserId(), 1, Integer::sum);
                }
            }
            Duration ttl = Duration.ofSeconds(dailyCounterTtlSeconds());
            String datePart = today.format(DAILY_KEY_DATE);
            for (Map.Entry<Long, Integer> entry : dbCounts.entrySet()) {
                String key = RedisKeys.borrowDaily(entry.getKey(), datePart);
                int existing = parseCount(redisTemplate.opsForValue().get(key));
                int target = Math.max(entry.getValue(), existing);
                redisTemplate.opsForValue().set(key, String.valueOf(target), ttl);
            }
            log.info("borrow:daily 重建完成：当日有订单的用户 {} 人", dbCounts.size());
            return true;
        } catch (Exception e) {
            log.error("borrow:daily 重建失败，熔断保持打开等待下一轮探测重试：{}", e.getMessage(), e);
            return false;
        }
    }

    /** 计数键取值解析：缺失/空白按 0，脏值按 0（重建以 DB 口径为准），负数截断为 0。 */
    private static int parseCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 重建单个站点计数；调用方须持有 {@code rebuildMutex}（多实例并发由 Redis 标记兜底）。
     * 任一步失败返回 false 并记日志，不向上抛。
     */
    private boolean rebuildStation(Long stationId) {
        String availableKey = RedisKeys.stockAvailable(stationId);
        String initKey = RedisKeys.stockInit(stationId);
        String sentinelKey = RedisKeys.stockSentinel(stationId);
        String owner = UUID.randomUUID().toString();
        try {
            // 1) 先抢初始化互斥标记，未抢到时不得删除另一重建持有者的库存。
            // SETNX 互斥标记（7.2）：初始化中该站点的申领由 Lua 首查标记返回 -1（5102）
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(initKey, owner, INIT_MARKER_TTL);
            if (!Boolean.TRUE.equals(acquired)) {
                log.info("站点 {} 计数重建已被并发的重建持有（stock:init 已存在），本次跳过", stationId);
                return false;
            }
            try {
                // 3) DB 为权威：按 COUNT(IDLE) 重写，COUNT=0 也写入 0 值；
                //    DB 异常单独归类——不能计入 Redis 熔断计数，否则 DB 抖动会误开库存熔断
                Long idleCount = countIdleBagsOrNull(stationId);
                if (idleCount == null) {
                    return false;
                }
                // Lua同时校验owner、发布计数/哨兵并解锁；过期旧持有者不再写入。
                Long published = redisTemplate.execute(COMPLETE_REBUILD,
                        List.of(initKey, availableKey, sentinelKey), owner, String.valueOf(idleCount));
                if (!Long.valueOf(1L).equals(published)) {
                    log.warn("站点 {} 初始化锁已过期或被替换，本次计数不发布", stationId);
                    return false;
                }
                recordRedisSuccess();
                log.info("站点 {} 库存计数重建完成：空闲（IDLE）袋 {} 只", stationId, idleCount);
                return true;
            } finally {
                // 5) 计数写回之后删标记；删除失败不影响本次结果（标记 300s 后自愈）
                try {
                    redisTemplate.execute(RELEASE_INIT, List.of(initKey), owner);
                } catch (Exception e) {
                    log.warn("删除站点 {} 的初始化互斥标记失败（标记 {} 秒后自愈）：{}",
                            stationId, INIT_MARKER_TTL.toSeconds(), e.getMessage());
                }
            }
        } catch (Exception e) {
            recordRedisFailure(e);
            log.error("站点 {} 库存计数重建失败，交由 R4 对账兜底并告警：{}", stationId, e.getMessage(), e);
            return false;
        }
    }

    /** 站点当前空闲袋数（DB 权威口径，见 7.2「可用库存口径」）；查询异常返回 null，由调用方按重建失败处理。 */
    private Long countIdleBagsOrNull(Long stationId) {
        try {
            // 列名取自已冻结的建表脚本（station_id/status），条件值为参数绑定，无 SQL 拼接
            Long count = bagMapper.selectCount(new QueryWrapper<Bag>()
                    .eq("station_id", stationId)
                    .eq("status", BagStatus.IDLE.code()));
            return count == null ? 0L : count;
        } catch (Exception e) {
            log.error("站点 {} 库存计数重建失败：DB 计数查询异常，交由 R4 对账兜底：{}", stationId, e.getMessage(), e);
            return null;
        }
    }

    /** 全部 ENABLED 站点 ID（重建计数的遍历范围）。 */
    private List<Long> listEnabledStationIds() {
        List<Station> stations = stationMapper.selectList(new QueryWrapper<Station>()
                .select("id")
                .eq("status", Constants.STATUS_ENABLED));
        return stations.stream().map(Station::getId).toList();
    }

    /** 取袋日频次键的 TTL 秒数：当日+1 天（8.3 键表），过期时点为次日结束，跨日后自然淘汰。 */
    private long dailyCounterTtlSeconds() {
        LocalDateTime expireAt = LocalDate.now().plusDays(2).atStartOfDay();
        return Math.max(1L, Duration.between(LocalDateTime.now(), expireAt).getSeconds());
    }
}
