package com.sems.push;
import com.sems.common.*;
import com.sems.infra.RedisKeys;
import com.sems.security.*;
import com.sems.module.user.entity.SysUser;
import com.sems.module.user.mapper.SysUserMapper;
import com.sems.module.audit.entity.AuditLogEntity;
import com.sems.module.audit.service.AuditLogService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
/** WebSocket 首帧认证、订阅越权和账号即时失效回归。 */
class WebSocketSecurityTest {
 JwtService jwt=mock(JwtService.class);
 StringRedisTemplate redis=mock(StringRedisTemplate.class);
 ValueOperations<String,String> values=mock(ValueOperations.class);
 SysUserMapper users=mock(SysUserMapper.class);
 WsSessionManager manager;
 WebSocketSession session=mock(WebSocketSession.class);
 @BeforeEach void init(){ when(redis.opsForValue()).thenReturn(values);when(session.getId()).thenReturn("连接1");when(session.isOpen()).thenReturn(true);when(session.getHandshakeHeaders()).thenReturn(new HttpHeaders());manager=new WsSessionManager(jwt,redis,users); }
 @AfterEach void close(){manager.shutdown();}
 LoginUser user(String role){LoginUser u=new LoginUser();u.setUserId(7L);u.setStationId(2L);u.setRole(role);u.setJti("令牌1");u.setTokenVersion(1L);return u;}
 @ParameterizedTest @CsvSource({"USER,user/7/order,true","USER,user/8/order,false","USER,platform/dashboard,false","STATION_ADMIN,station/2/stock,true","STATION_ADMIN,station/2/recycle,true","STATION_ADMIN,station/3/stock,false","PLATFORM_ADMIN,platform/dashboard,true","PLATFORM_ADMIN,station/3/recycle,true","PLATFORM_ADMIN,user/7/order,false","未知,station/2/stock,false","USER,user/7/order/extra,false"})
 void subscriptionWhitelist(String role,String topic,boolean allowed){assertEquals(allowed,WsSessionManager.canSubscribe(user(role),topic));assertFalse(WsSessionManager.canSubscribe(null,topic));assertFalse(WsSessionManager.canSubscribe(user(role),null));}
 @Test void authenticatedSubscriptionBroadcastAndCleanup() throws Exception {
 assertFalse(manager.subscribe(session,"station/2/stock"));manager.register(session);assertFalse(manager.isAuthenticated(session));manager.markAuthenticated(session,user("STATION_ADMIN"),"有效令牌");assertTrue(manager.isAuthenticated(session));assertEquals(7L,manager.currentUser(session).getUserId());assertTrue(manager.subscribe(session,"station/2/stock"));manager.broadcast("station/2/stock","库存3");verify(session).sendMessage(argThat(m->m.getPayload().equals("库存3")));manager.unsubscribe(session,"station/2/stock");manager.broadcast("station/2/stock","库存4");verify(session,times(1)).sendMessage(any());manager.remove(session);assertFalse(manager.isAuthenticated(session));assertNull(manager.currentUser(session));manager.unsubscribe(session,null);manager.remove(session);manager.broadcast("不存在","消息");}
 @Test void brokenSocketIsRemovedWithoutFailingBusiness() throws Exception { manager.register(session);manager.markAuthenticated(session,user("USER"),"token");doThrow(new IOException("断线")).when(session).sendMessage(any());assertDoesNotThrow(()->manager.sendText(session,"消息"));assertFalse(manager.isAuthenticated(session));manager.sendText(null,"消息");when(session.isOpen()).thenReturn(false);manager.sendText(session,"消息");}
 @Test void revokedAndDisabledAccountsAreRejected(){LoginUser u=user("USER");assertTrue(manager.isTokenRevoked(null));when(redis.hasKey(RedisKeys.authDeny(u.getJti()))).thenReturn(true);assertTrue(manager.isTokenRevoked(u));when(redis.hasKey(anyString())).thenReturn(false);when(values.get(anyString())).thenReturn("2|ENABLED|0");assertTrue(manager.isTokenRevoked(u));when(values.get(anyString())).thenReturn("1|DISABLED|0");assertTrue(manager.isTokenRevoked(u));when(values.get(anyString())).thenReturn("1|ENABLED|0");assertFalse(manager.isTokenRevoked(u));}
 @Test void accountCacheMissUsesAuthoritativeDatabase(){LoginUser u=user("USER");assertTrue(manager.isTokenRevoked(u));SysUser stored=new SysUser();stored.setTokenVersion(1);stored.setStatus("ENABLED");when(users.selectById(7L)).thenReturn(stored);assertFalse(manager.isTokenRevoked(u));stored.setTokenVersion(2);assertTrue(manager.isTokenRevoked(u));stored.setTokenVersion(1);stored.setStatus("DISABLED");assertTrue(manager.isTokenRevoked(u));}
 @Test void heartbeatClosesExpiredSessionAndPingsValidOne() throws Exception { manager.register(session);LoginUser u=user("USER");manager.markAuthenticated(session,u,"令牌");when(jwt.parse("令牌")).thenReturn(u);when(values.get(anyString())).thenReturn("1|ENABLED|0");manager.heartbeat();verify(session).sendMessage(argThat(m->m.getPayload().toString().contains("ping")));when(jwt.parse("令牌")).thenThrow(new BizException(ErrorCode.UNAUTHENTICATED));manager.heartbeat();verify(session).close(any(CloseStatus.class));assertFalse(manager.isAuthenticated(session));}
 @Test void handlerRequiresAuthAndAuditsForbiddenTopics() throws Exception {
 WsSessionManager sm=mock(WsSessionManager.class);AuditLogService audit=mock(AuditLogService.class);SemsWsHandler h=new SemsWsHandler(sm,jwt,audit);h.afterConnectionEstablished(session);verify(sm).register(session);
 h.handleTextMessage(session,new TextMessage("无效JSON"));verify(sm).closeQuietly(session,"消息格式不合法");
 h.handleTextMessage(session,new TextMessage("{\"type\":\"subscribe\"}"));verify(sm).closeQuietly(session,"首帧必须为鉴权帧");
 h.handleTextMessage(session,new TextMessage("{\"type\":\"auth\",\"token\":\"\"}"));verify(sm,atLeastOnce()).closeQuietly(eq(session),anyString());
 LoginUser u=user("USER");when(jwt.parse("有效")).thenReturn(u);h.handleTextMessage(session,new TextMessage("{\"type\":\"auth\",\"token\":\"有效\"}"));verify(sm).markAuthenticated(session,u,"有效");
 when(sm.isAuthenticated(session)).thenReturn(true);when(sm.currentUser(session)).thenReturn(u);h.handleTextMessage(session,new TextMessage("{\"type\":\"subscribe\",\"topic\":\"user/8/order\"}"));verify(audit).record(argThat(a->a.getErrorCode()==2002 && a.getOperatorId()==7L));verify(sm,never()).subscribe(session,"user/8/order");
 h.handleTextMessage(session,new TextMessage("{\"type\":\"subscribe\",\"topic\":\"user/7/order\"}"));verify(sm).subscribe(session,"user/7/order");h.handleTextMessage(session,new TextMessage("{\"type\":\"unsubscribe\",\"topic\":\"user/7/order\"}"));verify(sm).unsubscribe(session,"user/7/order");
 for(String type:List.of("ping","pong","未知"))h.handleTextMessage(session,new TextMessage("{\"type\":\""+type+"\"}"));h.handleTextMessage(session,new TextMessage("bad"));h.afterConnectionClosed(session,CloseStatus.NORMAL);verify(sm).remove(session);h.handleTransportError(session,new IOException("断线"));verify(sm).closeQuietly(session,"传输异常");}
}
