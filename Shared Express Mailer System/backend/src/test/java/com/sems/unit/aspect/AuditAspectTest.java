package com.sems.unit.aspect;
import com.sems.aspect.*;
import com.sems.common.*;
import com.sems.module.audit.entity.AuditLogEntity;
import com.sems.module.audit.service.AuditLogService;
import com.sems.security.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
/** 验证切面在失败路径仍审计且敏感参数递归脱敏。 */
class AuditAspectTest {
 static class Endpoint { @AuditLog(module="快递袋管理",action="状态修正") public void run(){} }
 @AfterEach void clear(){AuthContext.clear();RequestContextHolder.resetRequestAttributes();}
 AuditLog annotation()throws Exception{return Endpoint.class.getMethod("run").getAnnotation(AuditLog.class);}
 @Test void successCapturesOperatorNetworkAndMasksNestedSecrets()throws Throwable {AuditLogService records=mock(AuditLogService.class);AuditAspect a=new AuditAspect(records);ProceedingJoinPoint p=mock(ProceedingJoinPoint.class);when(p.proceed()).thenReturn("成功");LoginUser u=new LoginUser();u.setUserId(7L);u.setRole("PLATFORM_ADMIN");AuthContext.set(u);MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/v1/bags/袋/status-correct");request.addHeader("X-Forwarded-For","127.0.0.1,10.0.0.1");request.addHeader("User-Agent","测试浏览器");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));when(p.getArgs()).thenReturn(new Object[]{request,null,Map.of("password","密文前明文","items",List.of(Map.of("recipientPhone","13800000000","remark","核对后修正")),"phoneNumber","13900000000")});assertEquals("成功",a.around(p,annotation()));ArgumentCaptor<AuditLogEntity> c=ArgumentCaptor.forClass(AuditLogEntity.class);verify(records).record(c.capture());AuditLogEntity e=c.getValue();assertEquals(7L,e.getOperatorId());assertEquals(1,e.getResult());assertEquals("10.0.0.1",e.getIp());assertTrue(e.getParamsMasked().contains("核对后修正"));assertFalse(e.getParamsMasked().contains("13800000000"));assertFalse(e.getParamsMasked().contains("13900000000"));assertFalse(e.getParamsMasked().contains("密文前明文"));}
 @Test void failuresAreRethrownAndMandatoryAuditActionsNormalized()throws Throwable {for(ErrorCode code:List.of(ErrorCode.FORBIDDEN,ErrorCode.NO_DATA_PERMISSION,ErrorCode.BAG_ILLEGAL_TRANSITION,ErrorCode.PARAM_INVALID)){AuditLogService records=mock(AuditLogService.class);ProceedingJoinPoint p=mock(ProceedingJoinPoint.class);BizException error=new BizException(code);when(p.proceed()).thenThrow(error);AuditAspect a=new AuditAspect(records);AuditLog ann=annotation();assertSame(error,assertThrows(BizException.class,()->a.around(p,ann)));verify(records).record(argThat(e->e.getResult()==0&&e.getErrorCode()==code.getCode()&&(code==ErrorCode.FORBIDDEN||code==ErrorCode.NO_DATA_PERMISSION?e.getAction().equals("越权尝试"):code==ErrorCode.BAG_ILLEGAL_TRANSITION?e.getAction().equals("状态机拒绝"):e.getAction().equals("状态修正"))));}}
 @Test void systemErrorAndAuditOutageDoNotChangeOriginalOutcome()throws Throwable {AuditLogService records=mock(AuditLogService.class);ProceedingJoinPoint p=mock(ProceedingJoinPoint.class);IllegalArgumentException failure=new IllegalArgumentException("参数处理异常");when(p.proceed()).thenThrow(failure);AuditAspect a=new AuditAspect(records);AuditLog ann=annotation();assertSame(failure,assertThrows(IllegalArgumentException.class,()->a.around(p,ann)));verify(records).record(argThat(e->e.getErrorCode()==5000));doThrow(new IllegalStateException("审计不可用")).when(records).record(any(AuditLogEntity.class));doReturn("业务仍成功").when(p).proceed();assertEquals("业务仍成功",a.around(p,ann));}
}
