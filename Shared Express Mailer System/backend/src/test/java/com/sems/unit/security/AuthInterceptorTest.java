package com.sems.unit.security;

import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.config.SemsProperties;
import com.sems.infra.RedisKeys;
import com.sems.module.user.entity.SysUser;
import com.sems.module.user.mapper.SysUserMapper;
import com.sems.security.AuthContext;
import com.sems.security.AuthInterceptor;
import com.sems.security.JwtService;
import com.sems.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthInterceptorTest {
    private final JwtService jwt = mock(JwtService.class);
    private final SemsProperties properties = new SemsProperties();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(jwt, properties, redis, users);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    @AfterEach
    void clear() { AuthContext.clear(); }

    @Test
    void preflightAndAnonymousEndpointsDoNotRequireToken() {
        when(request.getMethod()).thenReturn("OPTIONS");
        when(request.getHeader("Origin")).thenReturn("https://client.example");
        when(request.getHeader("Access-Control-Request-Method")).thenReturn("GET");
        assertTrue(interceptor.preHandle(request, response, new Object()));

        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/context/api/health");
        when(request.getContextPath()).thenReturn("/context");
        assertTrue(interceptor.preHandle(request, response, new Object()));
        verifyNoInteractions(jwt, users);
    }

    @Test
    void missingOrEmptyBearerTokenFailsClosed() {
        when(request.getRequestURI()).thenReturn("/api/v1/orders");
        when(request.getHeader(Constants.AUTH_HEADER)).thenReturn(null);
        assertEquals(1001, assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, new Object())).getErrorCode().getCode());
        when(request.getHeader(Constants.AUTH_HEADER)).thenReturn(Constants.BEARER_PREFIX + "  ");
        assertEquals(1001, assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, new Object())).getErrorCode().getCode());
        verifyNoInteractions(jwt, users);
    }

    @Test
    void validCachedAccountSnapshotSetsRequestIdentityAndCompletionClearsIt() {
        LoginUser login = login(false);
        when(request.getRequestURI()).thenReturn("/api/v1/orders");
        when(request.getHeader(Constants.AUTH_HEADER)).thenReturn("Bearer token-value");
        when(jwt.parse("token-value")).thenReturn(login);
        when(redis.hasKey(RedisKeys.authDeny("jti-1"))).thenReturn(false);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RedisKeys.authUserVer(17L))).thenReturn("2|ENABLED|0");

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertSame(login, AuthContext.get());
        verify(users, never()).selectById(any());

        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(AuthContext.get());
    }

    @Test
    void disabledDatabaseAccountRejectsTokenEvenWhenRedisSnapshotMissing() {
        LoginUser login = login(false);
        when(request.getRequestURI()).thenReturn("/api/v1/orders");
        when(request.getHeader(Constants.AUTH_HEADER)).thenReturn("Bearer token-value");
        when(jwt.parse("token-value")).thenReturn(login);
        when(redis.hasKey(RedisKeys.authDeny("jti-1"))).thenReturn(false);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RedisKeys.authUserVer(17L))).thenReturn(null);
        SysUser disabled = new SysUser();
        disabled.setTokenVersion(2);
        disabled.setStatus(Constants.STATUS_DISABLED);
        disabled.setMustChangePassword(0);
        when(users.selectById(17L)).thenReturn(disabled);

        assertEquals(1001, assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, new Object())).getErrorCode().getCode());
        assertNull(AuthContext.get());
        verify(values).set(eq(RedisKeys.authUserVer(17L)), eq("2|DISABLED|0"), any(java.time.Duration.class));
    }

    @Test
    void mustChangePasswordBlocksBusinessRequestBeforeSettingThreadLocal() {
        LoginUser login = login(true);
        when(request.getRequestURI()).thenReturn("/api/v1/orders");
        when(request.getHeader(Constants.AUTH_HEADER)).thenReturn("Bearer token-value");
        when(jwt.parse("token-value")).thenReturn(login);
        when(redis.hasKey(RedisKeys.authDeny("jti-1"))).thenReturn(false);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RedisKeys.authUserVer(17L))).thenReturn("2|ENABLED|1");

        assertEquals(2001, assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, new Object())).getErrorCode().getCode());
        assertNull(AuthContext.get());
    }

    @Test
    void revokedJtiIsRejectedBeforeAccountLookup() {
        LoginUser login = login(false);
        when(request.getRequestURI()).thenReturn("/api/v1/orders");
        when(request.getHeader(Constants.AUTH_HEADER)).thenReturn("Bearer token-value");
        when(jwt.parse("token-value")).thenReturn(login);
        when(redis.hasKey(RedisKeys.authDeny("jti-1"))).thenReturn(true);

        assertEquals(1001, assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, new Object())).getErrorCode().getCode());
        verifyNoInteractions(users);
    }

    private static LoginUser login(boolean mustChange) {
        LoginUser login = new LoginUser();
        login.setUserId(17L);
        login.setRole(Constants.ROLE_USER);
        login.setJti("jti-1");
        login.setTokenVersion(2L);
        login.setMustChangePassword(mustChange);
        return login;
    }
}
