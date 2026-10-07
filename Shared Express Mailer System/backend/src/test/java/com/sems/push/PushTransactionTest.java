package com.sems.push;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
/** 库存与订单推送只能在事务提交后发布，旁路失败不影响业务。 */
class PushTransactionTest {
 @Test void callbacksAreDeferredUntilCommit(){PushService p=new PushService(mock(WsSessionManager.class));AtomicInteger seen=new AtomicInteger();TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);try{p.afterCommit(seen::incrementAndGet);assertEquals(0,seen.get());TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCommit());assertEquals(1,seen.get());}finally{TransactionSynchronizationManager.clear();}p.afterCommit(seen::incrementAndGet);assertEquals(2,seen.get());p.afterCommit(null);assertDoesNotThrow(()->p.afterCommit(()->{throw new IllegalStateException("回调失败");}));}
 @Test void businessTopicsAlsoRefreshPlatformAndFailureIsIsolated(){WsSessionManager sm=mock(WsSessionManager.class);PushService p=new PushService(sm);p.pushStationStock(2L,Map.of("available",3));p.pushStationRecycle(2L,Map.of("count",1));p.pushUserOrder(7L,Map.of("status","IN_USE"));verify(sm).broadcast(eq("station/2/stock"),contains("stock"));verify(sm).broadcast(eq("station/2/recycle"),contains("recycle"));verify(sm).broadcast(eq("user/7/order"),contains("order"));verify(sm,times(3)).broadcast(eq("platform/dashboard"),anyString());doThrow(new IllegalStateException("推送失败")).when(sm).broadcast(anyString(),anyString());assertDoesNotThrow(()->p.pushStationStock(2L,Map.of()));}
}
