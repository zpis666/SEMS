package com.sems.unit.audit;

import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.module.audit.entity.AuditLogEntity;
import com.sems.module.audit.mapper.AuditLogMapper;
import com.sems.module.audit.mapper.LoginLogMapper;
import com.sems.module.audit.service.AuditLogService;
import com.sems.module.audit.service.AuditQueryService;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditQueryServiceTest {
    private final AuditLogMapper auditMapper = mock(AuditLogMapper.class);
    private final LoginLogMapper loginMapper = mock(LoginLogMapper.class);
    private final AuditLogService auditService = mock(AuditLogService.class);
    private final AuditQueryService service = new AuditQueryService(auditMapper, loginMapper, auditService);

    @AfterEach
    void clear() { AuthContext.clear(); }

    @Test
    void fullIpViewReturnsOriginalAddressAndAuditsTheSpecificRecord() {
        LoginUser operator = new LoginUser();
        operator.setUserId(5L);
        operator.setRole("PLATFORM_ADMIN");
        AuthContext.set(operator);
        AuditLogEntity record = new AuditLogEntity();
        record.setIp("203.0.113.45");
        when(auditMapper.selectById(71L)).thenReturn(record);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.4, 10.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("test-client");
        when(request.getRequestURI()).thenReturn("/api/v1/audit-logs/71/ip");

        assertEquals("203.0.113.45", service.viewAuditLogIp(71L, request));

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditService).record(captor.capture());
        AuditLogEntity audit = captor.getValue();
        assertEquals(5L, audit.getOperatorId());
        assertEquals("审计查询", audit.getModule());
        assertEquals("查看完整IP", audit.getAction());
        assertEquals("198.51.100.4", audit.getIp());
        assertEquals("/api/v1/audit-logs/71/ip", audit.getRequestUri());
        assertTrue(audit.getParamsMasked().contains("audit_log"));
        assertTrue(audit.getParamsMasked().contains("71"));
    }

    @Test
    void missingRecordDoesNotCreateAnIpViewAudit() {
        when(auditMapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.viewAuditLogIp(99L, null));

        assertSame(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        verifyNoInteractions(auditService);
    }

    @Test
    void nullStoredIpIsReturnedAsEmptyStringAndAuditWorksWithoutRequestContext() {
        AuditLogEntity record = new AuditLogEntity();
        record.setIp(null);
        when(auditMapper.selectById(3L)).thenReturn(record);

        assertEquals("", service.viewAuditLogIp(3L, null));

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditService).record(captor.capture());
        assertEquals(0L, captor.getValue().getOperatorId());
        assertEquals("", captor.getValue().getIp());
        assertEquals("", captor.getValue().getRequestUri());
    }
}
