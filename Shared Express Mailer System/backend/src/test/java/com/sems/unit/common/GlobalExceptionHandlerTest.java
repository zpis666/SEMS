package com.sems.unit.common;

import com.sems.aspect.AuditLog;
import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.common.GlobalExceptionHandler;
import com.sems.common.Result;
import com.sems.common.event.AuditEvent;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GlobalExceptionHandlerTest {

    private ApplicationEventPublisher events;
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        events = mock(ApplicationEventPublisher.class);
        handler = new GlobalExceptionHandler(events);
    }

    @AfterEach
    void clearRequestState() {
        AuthContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("业务异常映射 HTTP 与统一响应；可重试错误附 Retry-After")
    void mapsBusinessStatusAndRetryHeader() {
        ResponseEntity<Result<Void>> ordinary = handler.handleBiz(
                new BizException(ErrorCode.BAG_NOT_FOUND, "袋不存在"));
        assertEquals(HttpStatus.OK, ordinary.getStatusCode());
        assertEquals(ErrorCode.BAG_NOT_FOUND.getCode(), ordinary.getBody().getCode());
        assertEquals("袋不存在", ordinary.getBody().getMessage());
        assertNull(ordinary.getBody().getData());
        assertFalse(ordinary.getHeaders().containsKey(HttpHeaders.RETRY_AFTER));

        ResponseEntity<Result<Void>> retry = handler.handleBiz(
                new BizException(ErrorCode.STOCK_RECONCILING, "库存对账中"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, retry.getStatusCode());
        assertEquals(ErrorCode.STOCK_RECONCILING.getCode(), retry.getBody().getCode());
        assertEquals("1", retry.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("请求体校验失败只返回字段名和提示，不把拒绝值带入响应")
    void bodyValidationReturnsSafeFieldErrors() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "phone", "13800001234", false,
                null, null, "手机号格式错误"));
        MethodParameter parameter = new MethodParameter(
                ValidationEndpoint.class.getDeclaredMethod("submit", SampleBody.class), 0);
        ResponseEntity<Result<java.util.Map<String, String>>> response = handler.handleMethodArgumentNotValid(
                new MethodArgumentNotValidException(parameter, binding));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), response.getBody().getCode());
        assertEquals("手机号格式错误", response.getBody().getData().get("phone"));
        assertEquals(java.util.Set.of("phone"), response.getBody().getData().keySet());
    }

    @Test
    @DisplayName("绑定、单参数、缺参和不支持方法统一映射为 3001")
    void mapsBindingAndRequestShapeFailures() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "query");
        binding.addError(new FieldError("query", "pageSize", "-1", false, null, null, "页大小非法"));
        ResponseEntity<Result<java.util.Map<String, String>>> bind = handler.handleBind(
                new org.springframework.validation.BindException(binding));
        assertEquals(400, bind.getStatusCode().value());
        assertEquals("页大小非法", bind.getBody().getData().get("pageSize"));

        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        Path property = mock(Path.class);
        org.mockito.Mockito.when(property.toString()).thenReturn("query.days");
        org.mockito.Mockito.when(violation.getPropertyPath()).thenReturn(property);
        org.mockito.Mockito.when(violation.getMessage()).thenReturn("天数超范围");
        ResponseEntity<Result<java.util.Map<String, String>>> constrained = handler.handleConstraintViolation(
                new ConstraintViolationException(Set.of(violation)));
        assertEquals(400, constrained.getStatusCode().value());
        assertEquals("天数超范围", constrained.getBody().getData().get("query.days"));

        ResponseEntity<Result<Void>> missing = handler.handleBadRequest(
                new MissingServletRequestParameterException("stationId", "Long"));
        assertEquals(400, missing.getStatusCode().value());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), missing.getBody().getCode());

        ResponseEntity<Result<Void>> method = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("TRACE"));
        assertEquals(400, method.getStatusCode().value());
        assertEquals("请求方法不支持", method.getBody().getMessage());
    }

    @Test
    @DisplayName("系统异常对外隐藏内部原因")
    void unexpectedFailureDoesNotLeakExceptionDetails() {
        ResponseEntity<Result<Void>> response = handler.handleUnexpected(
                new IllegalStateException("jdbc-password=private-secret"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), response.getBody().getCode());
        assertEquals(ErrorCode.SYSTEM_ERROR.getMessage(), response.getBody().getMessage());
        assertFalse(response.getBody().getMessage().contains("private-secret"));
        assertNull(response.getBody().getData());
    }

    @Test
    @DisplayName("水平越权事件记录可信代理链最右侧地址和当前操作人")
    void forbiddenAuditUsesRightmostForwardedAddress() {
        MockHttpServletRequest request = request("/api/v1/orders/foreign", "203.0.113.9, 10.0.0.2");
        LoginUser operator = new LoginUser();
        operator.setUserId(42L);
        operator.setRole("STATION_ADMIN");
        AuthContext.set(operator);

        ResponseEntity<Result<Void>> response = handler.handleBiz(
                new BizException(ErrorCode.NO_DATA_PERMISSION, "只能查看本站"));

        assertEquals(403, response.getStatusCode().value());
        verify(events).publishEvent(argThat((Object event) -> event instanceof AuditEvent audit
                && audit.getOperatorId() == 42L
                && audit.getOperatorRole().equals("STATION_ADMIN")
                && audit.getAction().equals("越权尝试")
                && audit.getErrorCode() == ErrorCode.NO_DATA_PERMISSION.getCode()
                && audit.getIp().equals("10.0.0.2")
                && audit.getRequestUri().equals("/api/v1/orders/foreign")));
    }

    @Test
    @DisplayName("2001 垂直越权不重复投递；有 AuditLog 的 2002 由切面负责，旁路失败不改变拒绝响应")
    void avoidsDuplicateForbiddenAuditsAndKeepsDenialOnPublisherFailure() throws Exception {
        request("/api/v1/bags", "198.51.100.7");
        handler.handleBiz(new BizException(ErrorCode.FORBIDDEN));
        verify(events, never()).publishEvent(any(Object.class));

        MockHttpServletRequest covered = request("/api/v1/orders/foreign", "198.51.100.7");
        covered.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                new HandlerMethod(new AuditedEndpoint(), AuditedEndpoint.class.getDeclaredMethod("write")));
        handler.handleBiz(new BizException(ErrorCode.NO_DATA_PERMISSION));
        verify(events, never()).publishEvent(any(Object.class));

        MockHttpServletRequest uncovered = request("/api/v1/other", "");
        uncovered.setRemoteAddr("192.0.2.8");
        doThrow(new IllegalStateException("audit offline")).when(events).publishEvent(any(Object.class));
        ResponseEntity<Result<Void>> response = handler.handleBiz(
                new BizException(ErrorCode.NO_DATA_PERMISSION, "拒绝访问"));
        assertEquals(403, response.getStatusCode().value());
        assertEquals(ErrorCode.NO_DATA_PERMISSION.getCode(), response.getBody().getCode());
        verify(events).publishEvent(argThat((Object event) -> event instanceof AuditEvent audit
                && audit.getIp().equals("192.0.2.8")));
    }

    private static MockHttpServletRequest request(String uri, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "unit-test");
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private static class SampleBody {
        @Valid
        Object nested;
    }

    private static class ValidationEndpoint {
        @SuppressWarnings("unused")
        void submit(@Valid SampleBody body) {
        }
    }

    private static class AuditedEndpoint {
        @AuditLog(module = "测试", action = "写入")
        public void write() {
        }
    }
}
