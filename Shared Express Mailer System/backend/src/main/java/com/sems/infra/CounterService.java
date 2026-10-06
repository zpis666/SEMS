package com.sems.infra;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 计数类 Redis 键的读写封装（见《系统设计说明书》8.3 键空间与 3.5 关键时限与计数口径表）：
 * 在持袋数（{@code borrow:open}，14 天滑动）、取袋日频次（{@code borrow:daily}，当日+1 天）、
 * 站点今日取/还（{@code stats:today}，Hash，当日+4 小时）。
 *
 * <p>为什么 TTL 在本类以具名常量固化：这三组 TTL 的口径唯一出处是设计书 3.5/8.3，
 * SemsProperties（设计书 11.2）未登记对应配置键，以具名常量集中登记可避免散落的魔法数字；
 * 后续若要求可配置化，整组迁入 SemsProperties 即可。
 *
 * <p>为什么每次写都要显式续期：Redis 的 INCR 不会重置既有 TTL，若不续期，
 * 「14 天滑动」会退化成「首次写入后固定过期」，在持袋数会在用户仍持袋时被清零。
 * 对账重建（R4）也要求以 SETEX 显式续期（3.5）。
 *
 * <p>Redis 异常不吞、原样上抛，交由上层熔断统一处置（见 7.2 降级矩阵）。
 */
@Service
public class CounterService {

    /** 在持袋数计数 TTL：14 天滑动（3.5/8.3；须覆盖订单最长停留期，故远大于 24h 业务窗口）。 */
    private static final Duration OPEN_TTL = Duration.ofDays(14);

    /** 取袋日频次的基础 TTL：1 天（8.3 的「当日+1 天」，与当日剩余时长相加）。 */
    private static final Duration DAILY_BASE_TTL = Duration.ofDays(1);

    /** 今日统计的基础 TTL：4 小时（8.3 的「当日 24 时+4h」，留给看板跨日读取的余量）。 */
    private static final Duration STATS_BASE_TTL = Duration.ofHours(4);

    /** 日频次键的日期段格式（8.3：{@code borrow:daily:{uid}:{yyyyMMdd}}）。 */
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redis;

    public CounterService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 读在持袋数；键不存在（未初始化或已过期）返回 null，由调用方按 7.2 的对账口径处理。 */
    public Integer getOpenCount(Long uid) {
        return toInteger(redis.opsForValue().get(RedisKeys.borrowOpen(uid)));
    }

    /** 写回在持袋数（对账重建用），同时以完整 14 天 TTL 显式续期。 */
    public void setOpenCount(Long uid, int value) {
        redis.opsForValue().set(RedisKeys.borrowOpen(uid), String.valueOf(value), OPEN_TTL);
    }

    /** 申领进入三态：+1；返回自增后的值，供调用方做「≤ 在持袋数上限」判定与超限回退。 */
    public long incrementOpen(Long uid) {
        String key = RedisKeys.borrowOpen(uid);
        Long count = redis.opsForValue().increment(key);
        redis.expire(key, OPEN_TTL);
        // INCR 连接异常时直接抛错、不会返回 null；null 分支仅为极端兜底
        return count == null ? 0L : count;
    }

    /** 袋离开三态：−1；DECR 减到负值（键曾被过期清理）时回写 0，计数不保留负数语义。 */
    public void decrementOpenFloor0(Long uid) {
        String key = RedisKeys.borrowOpen(uid);
        Long left = redis.opsForValue().decrement(key);
        if (left == null) {
            return;
        }
        if (left < 0) {
            // DECR 对不存在的键会先补 0 再减，产生 -1；在持袋数是「当前持有量」而非余额，
            // 负值没有业务含义，截断为 0 防止后续比较出现意外
            redis.opsForValue().set(key, "0", OPEN_TTL);
            return;
        }
        redis.expire(key, OPEN_TTL);
    }

    /** 读取袋日频次；键不存在（当日未申领）返回 null。 */
    public Integer getDailyCount(Long uid, LocalDate date) {
        return toInteger(redis.opsForValue().get(RedisKeys.borrowDaily(uid, format(date))));
    }

    /**
     * 取袋日频次 +1；返回自增后的值，供调用方做「≤ 日频次上限」判定。
     *
     * <p>TTL 按传入日期所在自然日的剩余秒数 + 1 天计算（3.5/8.3），
     * 这样跨日请求不会误续到旧日期的键上。
     */
    public long incrementDaily(Long uid, LocalDate date) {
        String key = RedisKeys.borrowDaily(uid, format(date));
        Long count = redis.opsForValue().increment(key);
        redis.expire(key, dailyTtl(date));
        return count == null ? 0L : count;
    }

    /**
     * 站点今日取/还计数 +1（Hash 域约定：{@code borrow}=取、{@code return}=还，见 8.3 写入点）。
     */
    public void statsTodayIncrement(Long stationId, String field) {
        String key = RedisKeys.statsToday(stationId);
        redis.opsForHash().increment(key, field, 1L);
        redis.expire(key, statsTtl());
    }

    /**
     * 读站点今日全部计数（供看板读取；平台看板按站点求和，见 8.3）。
     *
     * @return 字段名 → 计数值；键不存在返回空 Map，调用方按全 0 处理
     */
    public Map<String, Long> getStatsToday(Long stationId) {
        Map<Object, Object> entries = redis.opsForHash().entries(RedisKeys.statsToday(stationId));
        Map<String, Long> today = new LinkedHashMap<>(entries.size());
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            today.put(String.valueOf(entry.getKey()), parseCount(entry.getValue()));
        }
        return today;
    }

    /** 拼接日频次键的日期段。 */
    private static String format(LocalDate date) {
        return date.format(DAY_FORMAT);
    }

    /** 日频次 TTL = 该自然日剩余秒数 + 1 天；负数会立即删键，必须截断为 0。 */
    private static Duration dailyTtl(LocalDate date) {
        Duration remaining = Duration.between(LocalDateTime.now(), date.plusDays(1).atStartOfDay());
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }
        return remaining.plus(DAILY_BASE_TTL);
    }

    /** 今日统计 TTL = 当日剩余秒数 + 4 小时（按 JVM 时区；部署环境为 Asia/Shanghai）。 */
    private static Duration statsTtl() {
        LocalDateTime now = LocalDateTime.now();
        Duration remaining = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay());
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }
        return remaining.plus(STATS_BASE_TTL);
    }

    /** 计数键不存在返回 null；值由本服务/库存 Lua 写入，均为十进制整数。 */
    private static Integer toInteger(String raw) {
        return raw == null ? null : Integer.valueOf(raw);
    }

    /** 单个 Hash 域解析失败按 0 处理：不因一只脏字段打断整个看板读取。 */
    private static long parseCount(Object value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
