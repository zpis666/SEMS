package com.sems.unit.infra;

import com.sems.common.ErrorCode;
import com.sems.infra.RateLimitService;
import com.sems.infra.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RateLimitService service = new RateLimitService(redis);

    @Test
    void fixedWindowExpiresOnlyAtFirstRequestAndRejectsAfterLimit() {
        when(redis.opsForValue()).thenReturn(values);
        String key = RedisKeys.rate("user-7", "login");
        when(values.increment(key)).thenReturn(1L, 2L, 3L);

        assertTrue(service.tryAcquire("user-7", "login", 2, Duration.ofMinutes(1)));
        assertTrue(service.tryAcquire("user-7", "login", 2, Duration.ofMinutes(1)));
        assertFalse(service.tryAcquire("user-7", "login", 2, Duration.ofMinutes(1)));

        verify(redis, times(1)).expire(key, Duration.ofMinutes(1));
    }

    @Test
    void assertAcquireReturnsRetryableRateLimitError() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(4L);

        var ex = assertThrows(com.sems.common.BizException.class,
                () -> service.assertAcquire("ip", "captcha", 3, Duration.ofMinutes(1)));
        assertSame(ErrorCode.RATE_LIMITED, ex.getErrorCode());
    }

    @Test
    void nullIncrementResultIsTreatedAsFallbackPermit() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(null);

        assertTrue(service.tryAcquire("ip", "captcha", 1, Duration.ofMinutes(1)));
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }
}
