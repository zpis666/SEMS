package com.sems.unit.audit;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.common.event.AuditEvent;
import com.sems.module.audit.entity.*;
import com.sems.module.audit.mapper.*;
import com.sems.module.audit.service.AuditLogService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
/** 审计异步/降级持久化及应急文件重放只保留失败行。 */
class AuditLogPersistenceTest {
 @TempDir Path dir;
 AuditLogMapper audit=mock(AuditLogMapper.class);LoginLogMapper login=mock(LoginLogMapper.class);SysMetricMapper metrics=mock(SysMetricMapper.class);ThreadPoolTaskExecutor executor=mock(ThreadPoolTaskExecutor.class);AuditLogService service=new AuditLogService(audit,login,metrics,executor);
 @BeforeEach void sync(){doAnswer(i->{((Runnable)i.getArgument(0)).run();return null;}).when(executor).execute(any(Runnable.class));}
 AuditLogEntity event(String action){AuditLogEntity e=new AuditLogEntity();e.setAction(action);e.setResult(1);e.setErrorCode(0);return e;}
 @Test void auditAndLoginAreStoredAndNullEventsIgnored(){service.record(null);service.recordLogin(null);AuditLogEntity e=event("状态修正");service.record(e);verify(audit).insert(e);LoginLog l=new LoginLog();l.setUserId(7L);service.recordLogin(l);verify(login).insert(l);AuditEvent source=new AuditEvent();source.setAction("越权");source.setOperatorId(7L);source.setErrorCode(2002);service.onAuditEvent(source);verify(audit).insert(argThat((AuditLogEntity a)->Objects.equals(a.getOperatorId(),7L)&&Objects.equals(a.getErrorCode(),2002)));service.onAuditEvent(null);}
 @Test void rejectedExecutorKeepsCriticalAuditAndLoginSynchronous(){doThrow(new IllegalStateException("线程池已关闭")).when(executor).execute(any(Runnable.class));AuditLogEntity e=event("报废");service.record(e);verify(audit).insert(e);LoginLog l=new LoginLog();service.recordLogin(l);verify(login).insert(l);}
 @Test void failedPersistenceUsesIsolatedFallbackAndReplayRemovesOnlySuccesses()throws Exception {Path file=dir.resolve("audit-fallback-"+LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)+".log");try(MockedStatic<Path> paths=mockStatic(Path.class,CALLS_REAL_METHODS)){paths.when(()->Path.of("logs",file.getFileName().toString())).thenReturn(file);paths.when(()->Path.of("logs")).thenReturn(dir);doThrow(new IllegalStateException("数据库故障")).when(audit).insert(any(AuditLogEntity.class));service.record(event("查询"));assertTrue(Files.exists(file));verify(metrics).increment(eq(AuditLogService.METRIC_AUDIT_DROP_COUNT),any(LocalDate.class));doReturn(1).when(audit).insert(any(AuditLogEntity.class));service.replayFallbackFiles();assertFalse(Files.exists(file));}}
 @Test void replayMalformedAndUnknownLinesHasExplicitRetentionPolicy(){assertEquals(false,ReflectionTestUtils.invokeMethod(service,"replayLine","坏JSON"));assertEquals(true,ReflectionTestUtils.invokeMethod(service,"replayLine","{\"kind\":\"UNKNOWN\",\"payload\":{}}"));assertEquals(true,ReflectionTestUtils.invokeMethod(service,"replayLine","null"));assertEquals(true,ReflectionTestUtils.invokeMethod(service,"replayLine","{\"kind\":\"LOGIN\",\"payload\":{\"userId\":7}}"));verify(login).insert(argThat((LoginLog l)->l.getUserId()==7L));}
 @Test void queryDefaultsAndMetricFailureDoNotExposeInfrastructure(){when(audit.selectPage(any(Page.class),any(Wrapper.class))).thenReturn(new Page<AuditLogEntity>());when(login.selectPage(any(Page.class),any(Wrapper.class))).thenReturn(new Page<LoginLog>());assertNotNull(service.pageAuditLogs(0,999,null,null,null,null,null));assertNotNull(service.pageLoginLogs(0,0,null,null,null,null));assertEquals(0L,service.getAuditDropCount());when(metrics.selectOne(any(Wrapper.class))).thenThrow(new IllegalStateException("故障"));assertEquals(0L,service.getAuditDropCount());}
}
