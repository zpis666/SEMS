package com.sems.unit.job;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.config.SemsProperties;
import com.sems.job.RecycleAllocationTimeoutJob;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.station.entity.AllocationOrder;
import com.sems.module.station.mapper.AllocationOrderMapper;
import com.sems.push.PushService;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class RecoveryReminderJobTest {
 @org.junit.jupiter.api.BeforeAll static void metadata(){com.sems.unit.support.MybatisTestMetadata.init();}
 BagOrder order(){BagOrder o=new BagOrder();o.setId(1L);o.setOrderNo("单1");o.setBagCode("袋1");o.setStationId(1L);o.setReturnStationId(2L);o.setReturnMarkAt(LocalDateTime.now().minusDays(4));return o;}
 @Test void reminderTargetsReturnStationAndWarningsDoNotChangeBusinessState(){BagOrderMapper orders=mock(BagOrderMapper.class);AllocationOrderMapper allocations=mock(AllocationOrderMapper.class);PushService push=mock(PushService.class);BagOrder o=order();when(orders.selectPage(any(Page.class),any(Wrapper.class))).thenReturn(new Page<BagOrder>().setRecords(List.of(o)));AllocationOrder a=new AllocationOrder();a.setId(1L);a.setAllocationNo("调拨1");a.setFromStationId(1L);a.setToStationId(2L);when(allocations.selectPage(any(Page.class),any(Wrapper.class))).thenReturn(new Page<AllocationOrder>().setRecords(List.of(a)));when(orders.update(isNull(),any(Wrapper.class))).thenReturn(1);new RecycleAllocationTimeoutJob(orders,allocations,push,new SemsProperties()).scan();verify(push).pushStationRecycle(eq(2L),argThat(d->d.toString().contains("单1")));verify(allocations,never()).update(any(AllocationOrder.class),any(Wrapper.class));}
 @Test void missingReturnStationFallsBackToBorrowStationAndConcurrentReminderDoesNotRepeat(){BagOrderMapper orders=mock(BagOrderMapper.class);AllocationOrderMapper allocations=mock(AllocationOrderMapper.class);PushService push=mock(PushService.class);BagOrder o=order();o.setReturnStationId(null);when(orders.selectPage(any(Page.class),any(Wrapper.class))).thenReturn(new Page<BagOrder>().setRecords(List.of(o)));when(allocations.selectPage(any(Page.class),any(Wrapper.class))).thenReturn(new Page<AllocationOrder>());when(orders.update(isNull(),any(Wrapper.class))).thenReturn(1,0);RecycleAllocationTimeoutJob job=new RecycleAllocationTimeoutJob(orders,allocations,push,new SemsProperties());job.scan();job.scan();verify(push,times(1)).pushStationRecycle(eq(1L),any());}
 @Test void eachScanFailureIsIsolatedAndEmptyQueuesAreSafe(){BagOrderMapper orders=mock(BagOrderMapper.class);AllocationOrderMapper allocations=mock(AllocationOrderMapper.class);PushService push=mock(PushService.class);when(orders.selectPage(any(Page.class),any(Wrapper.class))).thenThrow(new IllegalStateException("数据库故障"));when(allocations.selectPage(any(Page.class),any(Wrapper.class))).thenReturn(new Page<AllocationOrder>());assertDoesNotThrow(()->new RecycleAllocationTimeoutJob(orders,allocations,push,new SemsProperties()).scan());verify(allocations).selectPage(any(Page.class),any(Wrapper.class));verifyNoInteractions(push);}
}
