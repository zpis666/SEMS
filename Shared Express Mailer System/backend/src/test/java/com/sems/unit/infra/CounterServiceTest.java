package com.sems.unit.infra;

import com.sems.infra.CounterService;
import com.sems.infra.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CounterServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    private final CounterService service = new CounterService(redis);

    @Test
    void incrementOpenRenewsSlidingTtlAndReturnsNewValue() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(RedisKeys.borrowOpen(8L))).thenReturn(3L);

        assertEquals(3L, service.incrementOpen(8L));

        verify(redis).expire(RedisKeys.borrowOpen(8L), Duration.ofDays(14));
    }

    @Test
    void decrementBelowZeroClampsToZeroInsteadOfKeepingNegativeOpenCount() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.decrement(RedisKeys.borrowOpen(8L))).thenReturn(-1L);

        service.decrementOpenFloor0(8L);

        verify(values).set(RedisKeys.borrowOpen(8L), "0", Duration.ofDays(14));
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void decrementPositiveCountRenewsTtlAndNullResultDoesNothingFurther() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.decrement(RedisKeys.borrowOpen(8L))).thenReturn(2L, (Long) null);

        service.decrementOpenFloor0(8L);
        service.decrementOpenFloor0(8L);

        verify(redis, times(1)).expire(RedisKeys.borrowOpen(8L), Duration.ofDays(14));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void dailyCounterUsesDateSpecificKeyAndAtLeastOneDayTtl() {
        when(redis.opsForValue()).thenReturn(values);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        String key = RedisKeys.borrowDaily(5L,
                yesterday.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
        when(values.increment(key)).thenReturn(11L);

        assertEquals(11L, service.incrementDaily(5L, yesterday));

        verify(redis).expire(eq(key), argThat(ttl -> ttl.compareTo(Duration.ofDays(1)) >= 0
                && ttl.compareTo(Duration.ofDays(2)) <= 0));
    }

    @Test
    void dailyAndOpenReadsPreserveMissingAsNull() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RedisKeys.borrowOpen(2L))).thenReturn(null);
        when(values.get(RedisKeys.borrowDaily(2L, "20261007"))).thenReturn("4");

        assertNull(service.getOpenCount(2L));
        assertEquals(4, service.getDailyCount(2L, LocalDate.of(2026, 10, 7)));
    }

    @Test
    void statsHashMapsInvalidCounterToZeroAndRenewsExpiryOnIncrement() {
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(RedisKeys.statsToday(4L))).thenReturn(Map.of("borrow", "6", "return", "bad"));
        Map<String, Long> result = service.getStatsToday(4L);
        assertEquals(6L, result.get("borrow"));
        assertEquals(0L, result.get("return"));

        service.statsTodayIncrement(4L, "borrow");
        verify(hashes).increment(RedisKeys.statsToday(4L), "borrow", 1L);
        verify(redis).expire(eq(RedisKeys.statsToday(4L)), argThat(ttl -> ttl.compareTo(Duration.ofHours(4)) >= 0
                && ttl.compareTo(Duration.ofHours(28)) <= 0));
    }
}
