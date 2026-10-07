package com.sems.unit.security;

import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.event.AuditEvent;
import com.sems.security.AuthContext;
import com.sems.security.AuthzInterceptor;
import com.sems.security.LoginUser;
import com.sems.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthzInterceptorTest {
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final AuthzInterceptor interceptor = new AuthzInterceptor(publisher);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void requiresAuthenticationEvenWhenHandlerHasNoRoleAnnotation() throws Exception {
        BizException ex = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, handler("unrestricted")));
        assertEquals(1001, ex.getErrorCode().getCode());
    }

    @Test
    void methodRoleOverridesClassAndHigherRoleSatisfiesLowerThreshold() throws Exception {
        authenticate(Constants.ROLE_PLATFORM_ADMIN, 7L);
        assertTrue(interceptor.preHandle(request, response, handler("userOnly")));
    }

    @Test
    void lowerRoleIsDeniedAndPublishesSecurityAuditUsingFirstForwardedIp() throws Exception {
        authenticate(Constants.ROLE_USER, 42L);
        when(request.getRequestURI()).thenReturn("/api/v1/stations");
        when(request.getHeader("X-Forwarded-For")).thenReturn(" 203.0.113.7, 10.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("test-agent");
        AtomicReference<Object> published = new AtomicReference<>();
        doAnswer(invocation -> { published.set(invocation.getArgument(0)); return null; })
                .when(publisher).publishEvent(any(Object.class));

        BizException ex = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, handler("platformOnly")));

        assertEquals(2001, ex.getErrorCode().getCode());
        AuditEvent event = (AuditEvent) published.get();
        assertNotNull(event);
        assertEquals(42L, event.getOperatorId());
        assertEquals("203.0.113.7", event.getIp());
        assertEquals("安全", event.getModule());
        assertEquals("越权尝试", event.getAction());
        assertEquals("/api/v1/stations", event.getRequestUri());
        assertEquals(0, event.getResult());
        verify(publisher).publishEvent(any(Object.class));
    }

    @Test
    void auditFailureDoesNotChangeAuthorizationDenial() throws Exception {
        authenticate(Constants.ROLE_USER, 1L);
        when(request.getRequestURI()).thenReturn("/private");
        doThrow(new IllegalStateException("audit down")).when(publisher).publishEvent(any(Object.class));
        assertEquals(2001, assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, handler("platformOnly")))
                .getErrorCode().getCode());
    }

    @Test
    void nonControllerHandlerOnlyRequiresNoRoleProcessing() {
        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void blankRoleDeclarationFailsClosedAsConfigurationError() throws Exception {
        authenticate(Constants.ROLE_PLATFORM_ADMIN, 1L);
        BizException ex = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, handler("emptyRoles")));
        assertEquals(5000, ex.getErrorCode().getCode());
    }

    @Test
    void unknownRoleDeclarationFailsClosed() throws Exception {
        authenticate(Constants.ROLE_PLATFORM_ADMIN, 1L);
        BizException ex = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, handler("unknownRole")));
        assertEquals(5000, ex.getErrorCode().getCode());
    }

    private static void authenticate(String role, long id) {
        LoginUser user = new LoginUser();
        user.setUserId(id);
        user.setRole(role);
        AuthContext.set(user);
    }

    private static HandlerMethod handler(String name) throws Exception {
        Method method = Controller.class.getDeclaredMethod(name);
        return new HandlerMethod(new Controller(), method);
    }

    @RequireRole(Constants.ROLE_PLATFORM_ADMIN)
    static class Controller {
        void unrestricted() { }
        @RequireRole(Constants.ROLE_USER)
        void userOnly() { }
        void platformOnly() { }
        @RequireRole({})
        void emptyRoles() { }
        @RequireRole("ALIEN")
        void unknownRole() { }
    }
}
