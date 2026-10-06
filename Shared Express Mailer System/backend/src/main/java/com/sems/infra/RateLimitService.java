package com.sems.infra;

import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 固定窗口限流（见《系统设计说明书》9.6「限流」与 3.5「应用层限流窗口」）。
 *
 * <p>算法：对 {@code rate:{subject}:{api}} 执行 INCR，返回值即本窗口内第几次请求；
 * 仅当返回 1（窗口首请求）时设置窗口过期，因此窗口边界固定、不随流量滑动。
 * 调用方把窗口长度（如 3.5 的「固定窗口 1 分钟」）与限额显式传入，
 * 本服务不内置任何数字，避免阈值散落、便于按接口差异化配置。
 *
 * <p>Redis 异常不吞、原样上抛，交由上层熔断统一处置（见 7.2 降级矩阵）——
 * 限流属于安全防护，静默放行等于关掉防护。
 */
@Service
public class RateLimitService {

    private final StringRedisTemplate redis;

    public RateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试获取一次配额。
     *
     * @param subject 限流主体：登录后为 uid，匿名接口为来源 ip（见 8.3 rate 键口径）
     * @param api     接口标识，同一主体的不同接口各自独立计数
     * @param limit   窗口内允许的最大请求次数
     * @param window  固定窗口长度
     * @return true = 放行；false = 窗口内已超限（是否抛错由调用方决定，见 {@link #assertAcquire}）
     */
    public boolean tryAcquire(String subject, String api, int limit, Duration window) {
        String key = RedisKeys.rate(subject, api);
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            // INCR 在连接异常时会直接抛异常，不会返回 null；此分支仅为极端兜底，
            // 按放行处理，避免把正常请求全部误伤
            return true;
        }
        if (count == 1L) {
            redis.expire(key, window);
        }
        return count <= limit;
    }

    /** 与 {@link #tryAcquire} 同语义，超限直接抛 5101；敏感接口入口一行调用即可完成防护。 */
    public void assertAcquire(String subject, String api, int limit, Duration window) {
        if (!tryAcquire(subject, api, limit, window)) {
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
    }
}
