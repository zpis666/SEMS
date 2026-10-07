package com.sems.unit.infra;

import com.sems.infra.IdempotentService;
import com.sems.infra.RedisKeys;
import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotentServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final IdempotentService service = new IdempotentService(redis);

    @AfterEach
    void clear() {
        AuthContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void findsCompletedResultAndProcessingMarkerByCurrentUserScopedKey() {
        LoginUser current = new LoginUser();
        current.setUserId(31L);
        AuthContext.set(current);
        when(redis.opsForValue()).thenReturn(values);
        String key = RedisKeys.idempotent(31L, "POST /bags", "req-1");
        when(values.get(key)).thenReturn("{\"status\":\"COMPLETED\",\"result\":\"{\\\"id\\\":7}\"}",
                "{\"status\":\"PROCESSING\"}", null);

        assertEquals("{\"id\":7}", service.findCompleted("POST /bags", "req-1"));
        assertEquals(IdempotentService.PROCESSING, service.findCompleted("POST /bags", "req-1"));
        assertNull(service.findCompleted("POST /bags", "req-1"));
        verify(values, times(3)).get(key);
    }

    @Test
    void anonymousRequestsUseAnonymousKeyAndProcessingMarkerHasTtl() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        service.markProcessing("POST /register", "request-2");

        verify(values).setIfAbsent(eq(RedisKeys.idempotent(null, "POST /register", "request-2")),
                argThat(raw -> raw.contains("PROCESSING")), eq(Duration.ofMinutes(10)));
    }

    @Test
    void completedValueIsWrittenAsReplayableStringAndCanBeCleared() {
        when(redis.opsForValue()).thenReturn(values);
        String key = RedisKeys.idempotent(null, "PATCH /me", "req-3");

        service.markCompleted("PATCH /me", "req-3", "{\"name\":\"new\"}");
        service.clear("PATCH /me", "req-3");

        verify(values).set(eq(key), argThat(raw -> raw.contains("COMPLETED") && raw.contains("new")),
                eq(Duration.ofMinutes(10)));
        verify(redis).delete(key);
    }

    @Test
    void completedCacheWriteWaitsForCommitAndUsesCapturedUserKey() {
        LoginUser current = new LoginUser();
        current.setUserId(41L);
        AuthContext.set(current);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        String key = RedisKeys.idempotent(41L, "POST /allocations", "request-commit");

        service.markProcessing("POST /allocations", "request-commit");
        service.markCompleted("POST /allocations", "request-commit", "{\"id\":9}");
        verify(values, never()).set(eq(key), anyString(), any(Duration.class));

        LoginUser other = new LoginUser();
        other.setUserId(99L);
        AuthContext.set(other);
        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
        synchronization.afterCommit();
        synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(values).set(eq(key), argThat(raw -> raw.contains("COMPLETED") && raw.contains("id")),
                eq(Duration.ofMinutes(10)));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void rollbackClearsProcessingPlaceholderWithoutWritingCompletedResult() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        String key = RedisKeys.idempotent(null, "POST /inventory-checks", "request-rollback");

        service.markProcessing("POST /inventory-checks", "request-rollback");
        service.markCompleted("POST /inventory-checks", "request-rollback", "not-committed");
        TransactionSynchronizationManager.getSynchronizations().get(0)
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(redis).delete(key);
        verify(values, never()).set(eq(key), anyString(), any(Duration.class));
    }

    @Test
    void competingRequestWithSameIdIsRejectedAsRetryableInsteadOfRunningTwice() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false, null);

        BizException conflict = assertThrows(BizException.class,
                () -> service.markProcessing("POST /bags", "same-id"));
        BizException unknown = assertThrows(BizException.class,
                () -> service.markProcessing("POST /bags", "unknown-acquire-result"));

        assertEquals(ErrorCode.STOCK_RECONCILING, conflict.getErrorCode());
        assertEquals(ErrorCode.STOCK_RECONCILING, unknown.getErrorCode());
    }

    @Test
    void redisOutageFailsOpenToDatabaseFallbackInsteadOfBreakingWrites() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("offline"));
        doThrow(new RedisConnectionFailureException("offline"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        doThrow(new RedisConnectionFailureException("offline")).when(redis).delete(anyString());

        assertNull(service.findCompleted("POST /orders", "r"));
        assertDoesNotThrow(() -> service.markProcessing("POST /orders", "r"));
        assertDoesNotThrow(() -> service.markCompleted("POST /orders", "r", "done"));
        assertDoesNotThrow(() -> service.clear("POST /orders", "r"));
    }

    @Test
    void perBagDedupReturnsRedisResultAndFailsOpenOnRedisOutage() {
        when(redis.opsForValue()).thenReturn(values);
        String key = RedisKeys.idempotentBag("crid", "SJB-L-2609-00001-4");
        when(values.setIfAbsent(eq(key), eq("1"), eq(Duration.ofMinutes(10)))).thenReturn(false, true);
        assertFalse(service.tryAcquireBag("crid", "SJB-L-2609-00001-4"));
        assertTrue(service.tryAcquireBag("crid", "SJB-L-2609-00001-4"));

        when(values.setIfAbsent(eq(key), eq("1"), eq(Duration.ofMinutes(10))))
                .thenThrow(new RedisConnectionFailureException("offline"));
        assertTrue(service.tryAcquireBag("crid", "SJB-L-2609-00001-4"));
    }
}
