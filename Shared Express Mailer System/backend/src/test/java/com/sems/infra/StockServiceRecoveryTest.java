package com.sems.infra;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sems.common.*;
import com.sems.config.SemsProperties;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.StationMapper;
import com.sems.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
/** Lua原子返回码、库存互斥初始化、熔断恢复与令牌续签回归。 */
class StockServiceRecoveryTest {
 StringRedisTemplate redis=mock(StringRedisTemplate.class);ValueOperations<String,String> v=mock(ValueOperations.class);StationMapper stations=mock(StationMapper.class);BagMapper bags=mock(BagMapper.class);BagOrderMapper orders=mock(BagOrderMapper.class);JwtService jwt=mock(JwtService.class);SemsProperties p=new SemsProperties();StockService service;
 @BeforeEach void init(){com.sems.unit.support.MybatisTestMetadata.init();p.getHold().setTtl(Duration.ofHours(25));p.getBorrow().setOpenLimit(3);p.getBorrow().setDailyLimit(10);p.getJwt().setRenewThreshold(Duration.ofMinutes(30));when(redis.opsForValue()).thenReturn(v);service=new StockService(redis,p,stations,bags,orders,jwt);service.loadScripts();}
 @AfterEach void close(){service.shutdownProbe();}
 @ParameterizedTest @CsvSource({"0,4101","-1,5102","-2,5103","-3,4104","-4,4109","99,5102"})
 void deductionReturnCodesEnforceDistinctBusinessLimits(long result,int code){when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(result);assertEquals(code,assertThrows(BizException.class,()->service.tryDeduct("单1",1L,7L,"凭证")).getErrorCode().getCode());}
 @Test void successfulDeductionAndCompensationOnlyExecuteOnceAtRedisLayer(){when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(1L,1L,0L);assertDoesNotThrow(()->service.tryDeduct("单1",1L,7L,"凭证"));assertTrue(service.compensate("单1",1L,7L));assertFalse(service.compensate("单1",1L,7L));verify(redis,times(3)).execute(any(RedisScript.class),anyList(),any(Object[].class));service.deleteHold("单1");verify(redis).delete(RedisKeys.stockHold("单1"));}
 @Test void nullLuaResultIsUnavailableAndRedisFailuresOpenCircuit(){assertEquals(ErrorCode.STOCK_NOT_READY,assertThrows(BizException.class,()->service.tryDeduct("单",1L,7L,"凭证")).getErrorCode());when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenThrow(new IllegalStateException("Redis断线"));for(int i=0;i<3;i++)assertThrows(BizException.class,()->service.tryDeduct("单",1L,7L,"凭证"));assertTrue(service.isDegraded());assertFalse(service.isStationReady(1L));assertNull(service.getAvailable(1L));service.increaseAvailable(1L,1);service.decrementOpen(7L);assertThrows(BizException.class,()->service.tryDeduct("单",1L,7L,"凭证"));}
 @Test void availableCountersClampBadDataAndOpenCountCannotBecomeNegative(){when(v.get(RedisKeys.stockAvailable(1L))).thenReturn(null,"坏值","-2","3");assertNull(service.getAvailable(1L));assertNull(service.getAvailable(1L));assertNull(service.getAvailable(1L));assertEquals(3,service.getAvailable(1L));when(v.decrement(RedisKeys.borrowOpen(7L))).thenReturn(-1L);service.decrementOpen(7L);verify(v).set(RedisKeys.borrowOpen(7L),"0",Duration.ofDays(14));service.increaseAvailable(1L,0);service.increaseAvailable(1L,2);verify(v).increment(RedisKeys.stockAvailable(1L),2L);}
 @Test void busyInitializationDoesNotDeleteExistingInventory(){service.rebuildStationStock(1L);verify(redis,never()).delete(RedisKeys.stockAvailable(1L));verify(bags,never()).selectCount(any(Wrapper.class));}
 @Test void initializationUsesIdleDatabaseCountAndAlwaysReleasesMarker(){when(v.setIfAbsent(eq(RedisKeys.stockInit(1L)),anyString(),any(Duration.class))).thenReturn(true);when(bags.selectCount(any(Wrapper.class))).thenReturn(4L);when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(1L);service.rebuildStationStock(1L);verify(redis).execute(any(RedisScript.class),eq(List.of(RedisKeys.stockInit(1L),RedisKeys.stockAvailable(1L),RedisKeys.stockSentinel(1L))),anyString(),eq("4"));verify(redis).execute(any(RedisScript.class),eq(List.of(RedisKeys.stockInit(1L))),anyString());when(bags.selectCount(any(Wrapper.class))).thenThrow(new IllegalStateException("数据库故障"));service.rebuildStationStock(1L);verify(redis,times(2)).execute(any(RedisScript.class),eq(List.of(RedisKeys.stockInit(1L))),anyString());assertFalse(service.isDegraded());}
 @Test void expiredOwnerCannotPublishOrDeleteAnotherOwnersLock(){when(v.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(true);when(bags.selectCount(any(Wrapper.class))).thenReturn(4L);when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(0L);service.rebuildStationStock(1L);verify(v,never()).set(eq(RedisKeys.stockAvailable(1L)),anyString());verify(redis,never()).delete(RedisKeys.stockInit(1L));verify(redis).execute(any(RedisScript.class),eq(List.of(RedisKeys.stockInit(1L))),anyString());}
 @Test void allEnabledStationsRebuildAndMissingCountBecomesZero(){Station a=new Station();a.setId(1L);Station b=new Station();b.setId(2L);when(stations.selectList(any(Wrapper.class))).thenReturn(List.of(a,b));when(v.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(true);when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(1L);service.initializeAllStations();verify(redis).execute(any(RedisScript.class),eq(List.of(RedisKeys.stockInit(1L),RedisKeys.stockAvailable(1L),RedisKeys.stockSentinel(1L))),anyString(),eq("0"));verify(redis).execute(any(RedisScript.class),eq(List.of(RedisKeys.stockInit(2L),RedisKeys.stockAvailable(2L),RedisKeys.stockSentinel(2L))),anyString(),eq("0"));when(stations.selectList(any(Wrapper.class))).thenThrow(new IllegalStateException("故障"));assertDoesNotThrow(service::initializeAllStations);}
 @Test void shortLivedTokenIsRotatedAndLongLivedTokenUnchanged(){LoginUser u=new LoginUser();u.setJti("旧jti");when(jwt.parse("原令牌")).thenReturn(u);when(jwt.remaining("原令牌")).thenReturn(Duration.ofHours(1));assertEquals("原令牌",service.renewToken("原令牌"));when(jwt.remaining("原令牌")).thenReturn(Duration.ofMinutes(5));when(jwt.newJti()).thenReturn("新jti");when(jwt.issue(u)).thenReturn("新令牌");assertEquals("新令牌",service.renewToken("原令牌"));assertEquals("新jti",u.getJti());verify(v).set(RedisKeys.authDeny("旧jti"),"1",Duration.ofMinutes(5));}
 @Test void recoveryRebuildsCurrentHolderAndDailyMaxInsteadOfDroppingQuota(){ReflectionTestUtils.setField(service,"degraded",true);Cursor<String> cursor=mock(Cursor.class);when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);when(cursor.hasNext()).thenReturn(true,false);when(cursor.next()).thenReturn(RedisKeys.borrowOpen(8L));Bag b=new Bag();b.setBagCode("袋1");when(bags.selectList(any(Wrapper.class))).thenReturn(List.of(b));BagOrder old=new BagOrder();old.setId(1L);old.setBagCode("袋1");old.setUserId(8L);BagOrder latest=new BagOrder();latest.setId(2L);latest.setBagCode("袋1");latest.setUserId(7L);when(orders.selectList(any(Wrapper.class))).thenReturn(List.of(old,latest));String daily=RedisKeys.borrowDaily(7L,LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));when(v.get(daily)).thenReturn("5");ReflectionTestUtils.invokeMethod(service,"recoverFromDegradation");assertFalse(service.isDegraded());verify(v).set(RedisKeys.borrowOpen(7L),"1",Duration.ofDays(14));verify(v).set(RedisKeys.borrowOpen(8L),"0",Duration.ofDays(14));verify(v).set(eq(daily),eq("5"),any(Duration.class));}
}
