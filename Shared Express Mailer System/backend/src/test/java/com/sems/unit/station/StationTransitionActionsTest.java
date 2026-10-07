package com.sems.unit.station;

import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.infra.CounterService;
import com.sems.infra.StockService;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.entity.StockRecord;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.bag.mapper.StockRecordMapper;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.statemachine.BagTransitionContext;
import com.sems.module.statemachine.OrderStatus;
import com.sems.module.statemachine.TransitionAction;
import com.sems.module.statemachine.TransitionCommand;
import com.sems.module.station.service.action.StationTransitionActions;
import com.sems.push.PushService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StationTransitionActionsTest {
    private final StockService stock = mock(StockService.class);
    private final CounterService counters = mock(CounterService.class);
    private final StockRecordMapper stockRecords = mock(StockRecordMapper.class);
    private final BagMapper bags = mock(BagMapper.class);
    private final BagOrderMapper orders = mock(BagOrderMapper.class);
    private final PushService pushes = mock(PushService.class);
    private final StationTransitionActions dispatcher = new StationTransitionActions(
            stock, counters, stockRecords, bags, orders, pushes);

    @Test
    void unusedReturnIncreasesStockWritesInboundRecordAndDecrementsOpenCount() {
        Bag bag = bag(BagStatus.ALLOCATED, "SJB-L-2609-00001-4", 0);
        BagOrder order = order(30L, OrderStatus.CANCELLED, 8L);
        when(orders.selectById(30L)).thenReturn(order);

        action(BagEvent.RECOVER_UNUSED).apply(context(bag, BagEvent.RECOVER_UNUSED, Map.of("orderId", 30L)));

        verify(stock).increaseAvailable(7L, 1);
        verify(counters).decrementOpenFloor0(8L);
        var captor = org.mockito.ArgumentCaptor.forClass(StockRecord.class);
        verify(stockRecords).insert(captor.capture());
        assertEquals("IN", captor.getValue().getType());
        assertEquals(7L, captor.getValue().getStationId());
        assertEquals(bag.getBagCode(), captor.getValue().getBagCode());
        verifyNoInteractions(pushes);
        verify(orders, never()).update(any(BagOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void unusedReturnRejectsUnexpectedBagStateAndDoesNotWriteSideEffects() {
        Bag bag = bag(BagStatus.IDLE, "SJB-L-2609-00002-5", 0);
        assertThrows(IllegalStateException.class,
                () -> action(BagEvent.RECOVER_UNUSED).apply(context(bag, BagEvent.RECOVER_UNUSED, Map.of())));

        verifyNoInteractions(stock, counters, stockRecords, orders, pushes);
    }

    @Test
    void allocatedConfirmCancelsPendingOrderAndDefersPushUntilCommit() {
        Bag bag = bag(BagStatus.ALLOCATED, "SJB-L-2609-00003-6", 2);
        BagOrder order = order(31L, OrderStatus.PENDING_SEND, 9L);
        when(orders.selectById(31L)).thenReturn(order);
        when(orders.update(isNull(BagOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        org.mockito.ArgumentCaptor<Runnable> callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);

        action(BagEvent.RECOVER_CONFIRM).apply(context(bag, BagEvent.RECOVER_CONFIRM, Map.of("orderId", 31L)));

        verify(orders).update(isNull(BagOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(counters).decrementOpenFloor0(9L);
        verify(bags, never()).update(any(Bag.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(pushes).afterCommit(callback.capture());
        callback.getValue().run();
        verify(pushes).pushUserOrder(eq(9L), argThat(data -> data.toString().contains("CANCELLED")));
    }

    @Test
    void inUseConfirmFinishesOrderIncrementsUseCountAndDecrementsOpenCount() {
        Bag bag = bag(BagStatus.IN_USE, "SJB-L-2609-00004-7", 4);
        BagOrder order = order(32L, OrderStatus.PENDING_CONFIRM, 10L);
        when(orders.selectById(32L)).thenReturn(order);
        when(orders.update(isNull(BagOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        when(bags.update(isNull(Bag.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);

        action(BagEvent.RECOVER_CONFIRM).apply(context(bag, BagEvent.RECOVER_CONFIRM, Map.of("orderId", 32L)));

        verify(orders).update(isNull(BagOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(bags).update(isNull(Bag.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(counters).decrementOpenFloor0(10L);
        verify(pushes).afterCommit(any(Runnable.class));
    }

    @Test
    void directDropFinishesOrderAndTreatsNullUseCountAsZero() {
        Bag bag = bag(BagStatus.IN_USE, "SJB-L-2609-00005-8", null);
        BagOrder order = order(33L, OrderStatus.PENDING_SEND, 11L);
        when(orders.selectById(33L)).thenReturn(order);
        when(orders.update(isNull(BagOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        when(bags.update(isNull(Bag.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);

        action(BagEvent.RECOVER_DIRECT).apply(context(bag, BagEvent.RECOVER_DIRECT, Map.of("orderId", 33L)));

        verify(orders).update(isNull(BagOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(bags).update(isNull(Bag.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(counters).decrementOpenFloor0(11L);
        verify(pushes).afterCommit(any(Runnable.class));
    }

    @Test
    void failedConditionalOrderUpdateAbortsAccompanyingActions() {
        Bag bag = bag(BagStatus.IN_USE, "SJB-L-2609-00006-9", 1);
        BagOrder order = order(34L, OrderStatus.PENDING_CONFIRM, 12L);
        when(orders.selectById(34L)).thenReturn(order);
        when(orders.update(isNull(BagOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> action(BagEvent.RECOVER_CONFIRM).apply(
                        context(bag, BagEvent.RECOVER_CONFIRM, Map.of("orderId", 34L))));

        assertSame(ErrorCode.ORDER_STATE_NOT_ALLOWED, ex.getErrorCode());
        verifyNoInteractions(counters, pushes);
        verify(bags, never()).update(any(Bag.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void delayedShippedReturnOfCancelledAllocationStillIncrementsUseCount() {
        Bag bag = bag(BagStatus.ALLOCATED, "SJB-L-2609-00007-0", null);
        BagOrder order = order(35L, OrderStatus.CANCELLED, 13L);
        when(orders.selectById(35L)).thenReturn(order);
        when(bags.update(isNull(Bag.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);

        action(BagEvent.RECOVER_CONFIRM).apply(context(bag, BagEvent.RECOVER_CONFIRM,
                Map.of("orderId", 35L, "shippedBefore", true)));

        verify(bags).update(isNull(Bag.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(counters).decrementOpenFloor0(13L);
        verify(orders, never()).update(any(BagOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verifyNoInteractions(pushes);
    }

    @Test
    void completedOrderNeedsNoSecondOrderUpdateButStillCountsUse() {
        Bag bag = bag(BagStatus.IN_USE, "SJB-L-2609-00008-1", 2);
        BagOrder order = order(36L, OrderStatus.FINISHED, 14L);
        when(orders.selectById(36L)).thenReturn(order);
        when(bags.update(isNull(Bag.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);

        action(BagEvent.RECOVER_CONFIRM).apply(context(bag, BagEvent.RECOVER_CONFIRM, Map.of("orderId", 36L)));

        verify(orders, never()).update(any(BagOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(bags).update(isNull(Bag.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(counters).decrementOpenFloor0(14L);
        verifyNoInteractions(pushes);
    }

    @Test
    void directDropCanCloseLatestOrderWhenCommandOmitsOrderId() {
        Bag bag = bag(BagStatus.IN_USE, "SJB-L-2609-00009-2", 1);
        BagOrder order = order(37L, OrderStatus.PENDING_SEND, 15L);
        when(orders.selectList(any())).thenReturn(java.util.List.of(order));
        when(orders.update(isNull(BagOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(1);
        when(bags.update(isNull(Bag.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);

        action(BagEvent.RECOVER_DIRECT).apply(context(bag, BagEvent.RECOVER_DIRECT, Map.of()));

        verify(orders).selectList(any());
        verify(orders).update(isNull(BagOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(counters).decrementOpenFloor0(15L);
    }

    @Test
    void directRecoveryRejectsNonInUseBagBeforeDatabaseChanges() {
        Bag bag = bag(BagStatus.IDLE, "SJB-L-2609-00010-3", 1);

        assertThrows(IllegalStateException.class,
                () -> action(BagEvent.RECOVER_DIRECT).apply(context(bag, BagEvent.RECOVER_DIRECT, Map.of())));

        verifyNoInteractions(orders, bags, counters, pushes);
    }

    private TransitionAction action(BagEvent event) {
        return switch (event) {
            case RECOVER_UNUSED -> new StationTransitionActions.RecoverUnusedAction(dispatcher);
            case RECOVER_CONFIRM -> new StationTransitionActions.RecoverConfirmAction(dispatcher);
            case RECOVER_DIRECT -> new StationTransitionActions.RecoverDirectAction(dispatcher);
            default -> throw new IllegalArgumentException("unsupported test action");
        };
    }

    private static BagTransitionContext context(Bag bag, BagEvent event, Map<String, Object> attributes) {
        TransitionCommand command = TransitionCommand.builder().event(event).stationId(7L).targetStationId(7L)
                .operatorId(5L).operatorRole("STATION_ADMIN").attributes(attributes).build();
        return BagTransitionContext.builder().cmd(command).bag(bag).operatorId(5L).build();
    }

    private static Bag bag(BagStatus status, String code, Integer uses) {
        Bag bag = new Bag();
        bag.setBagCode(code);
        bag.setStatus(status.code());
        bag.setUseCount(uses);
        bag.setStationId(6L);
        return bag;
    }

    private static BagOrder order(Long id, OrderStatus status, Long userId) {
        BagOrder order = new BagOrder();
        order.setId(id);
        order.setUserId(userId);
        order.setOrderNo("BO-" + id);
        order.setBagCode("SJB-L-2609-00001-4");
        order.setStatus(status.code());
        order.setVersion(0);
        return order;
    }
}
