package com.sems.unit.order;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sems.infra.CounterService;
import com.sems.infra.StockService;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.entity.LiabilityRecord;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.order.mapper.LiabilityRecordMapper;
import com.sems.module.order.service.action.OrderTransitionActions;
import com.sems.module.statemachine.*;
import com.sems.push.PushService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderTransitionActionsTest {
    private BagOrderMapper orders;
    private LiabilityRecordMapper liabilities;
    private CounterService counters;
    private StockService stock;
    private PushService push;
    private OrderTransitionActions actions;

    @BeforeEach
    void setup() {
        if (TableInfoHelper.getTableInfo(BagOrder.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), BagOrder.class);
        }
        orders = mock(BagOrderMapper.class);
        liabilities = mock(LiabilityRecordMapper.class);
        counters = mock(CounterService.class);
        stock = mock(StockService.class);
        push = mock(PushService.class);
        actions = new OrderTransitionActions(orders, liabilities, counters, stock, push);
    }

    @Test
    void borrowCreatesPendingOrderIncrementsStationStatisticAndDefersPush() {
        when(orders.selectOne(any())).thenReturn(null);
        when(stock.isDegraded()).thenReturn(false);
        actions.apply(BagEvent.BORROW, context(BagEvent.BORROW, Map.of(
                "orderNo", "BO-101", "userId", 20L, "stationId", 7L), null));

        verify(orders).insert(argThat((BagOrder order) -> "BO-101".equals(order.getOrderNo())
                && "B-1".equals(order.getBagCode())
                && OrderStatus.PENDING_SEND.code().equals(order.getStatus())
                && Integer.valueOf(0).equals(order.getTimeoutFlag()) && order.getCreatedAt() != null));
        verify(counters).statsTodayIncrement(7L, "borrow");
        verify(push).afterCommit(any(Runnable.class));
        verifyNoInteractions(liabilities);
    }

    @Test
    void duplicateClientRequestReplaysWithoutAnotherInsertOrStatistic() {
        BagOrder existing = order(OrderStatus.PENDING_SEND);
        existing.setOrderNo("BO-OLD");
        when(orders.selectOne(any())).thenReturn(existing);
        actions.apply(BagEvent.BORROW, context(BagEvent.BORROW,
                Map.of("orderNo", "BO-NEW", "userId", 20L, "stationId", 7L), null));

        verify(orders, never()).insert(any(BagOrder.class));
        verifyNoInteractions(counters, stock, liabilities);
        verify(push).afterCommit(any(Runnable.class));
    }

    @Test
    void concurrentBorrowDuplicateKeyIsReplayWhenExistingOrderCanBeLoaded() {
        BagOrder existing = order(OrderStatus.PENDING_SEND);
        when(orders.selectOne(any())).thenReturn(null, null, existing);
        when(orders.insert(any(BagOrder.class))).thenThrow(new DuplicateKeyException("duplicate clientRequestId"));
        actions.apply(BagEvent.BORROW, context(BagEvent.BORROW,
                Map.of("orderNo", "BO-102", "userId", 20L, "stationId", 7L), null));
        verify(counters, never()).statsTodayIncrement(anyLong(), anyString());
        verify(push).afterCommit(any(Runnable.class));
    }

    @Test
    void shipRequiresPendingOrderAndMovesOnlyOrderWithVersionGuard() {
        BagOrder order = order(OrderStatus.PENDING_SEND);
        when(orders.selectOne(any())).thenReturn(order);
        when(orders.updateById(any(BagOrder.class))).thenReturn(1);
        actions.apply(BagEvent.SHIP, context(BagEvent.SHIP, Map.of(
                "orderNo", "BO-103", "expressCompany", "顺丰", "trackingNo", "SF123",
                "recipientPhoneMask", "138****0000", "recipientPhoneHash", "hmac"), null));

        verify(orders).updateById(argThat((BagOrder update) -> OrderStatus.IN_USE.code().equals(update.getStatus())
                && "顺丰".equals(update.getExpressCompany()) && "SF123".equals(update.getTrackingNo())
                && "138****0000".equals(update.getRecipientPhoneMask())
                && "hmac".equals(update.getRecipientPhoneHash()) && update.getShippedAt() != null
                && Integer.valueOf(3).equals(update.getVersion())));
        verify(push).afterCommit(any(Runnable.class));
    }

    @Test
    void staleOrderVersionAbortsTheTransition() {
        when(orders.selectOne(any())).thenReturn(order(OrderStatus.PENDING_SEND));
        when(orders.updateById(any(BagOrder.class))).thenReturn(0);
        assertThrows(RuntimeException.class, () -> actions.apply(BagEvent.SHIP,
                context(BagEvent.SHIP, Map.of("orderNo", "BO-104", "expressCompany", "顺丰",
                        "trackingNo", "SF124"), null)));
        verifyNoInteractions(push);
    }

    @Test
    void returnMarkStoresReturnStationWithoutChangingBagAndMovesOrderToConfirmation() {
        when(orders.selectOne(any())).thenReturn(order(OrderStatus.IN_USE));
        when(orders.updateById(any(BagOrder.class))).thenReturn(1);
        actions.apply(BagEvent.RETURN_MARK, context(BagEvent.RETURN_MARK,
                Map.of("orderNo", "BO-105", "returnStationId", "9"), null));

        verify(orders).updateById(argThat((BagOrder update) -> OrderStatus.PENDING_CONFIRM.code().equals(update.getStatus())
                && Long.valueOf(9L).equals(update.getReturnStationId()) && update.getReturnMarkAt() != null
                && Integer.valueOf(3).equals(update.getVersion())));
        verify(push).afterCommit(any(Runnable.class));
    }

    @Test
    void lostConfirmationClosesOrderWritesLiabilityAndReleasesOpenCount() {
        when(orders.selectOne(any())).thenReturn(order(OrderStatus.IN_USE));
        when(orders.updateById(any(BagOrder.class))).thenReturn(1);
        actions.apply(BagEvent.LOST_CONFIRM, context(BagEvent.LOST_CONFIRM, Map.of(
                "orderNo", "BO-106", "responsibleUserId", "20", "conclusion", "用户遗失",
                "note", "核查完成"), null));

        verify(orders).updateById(argThat((BagOrder update) -> OrderStatus.LOST.code().equals(update.getStatus())
                && update.getLostAt() != null));
        verify(liabilities).insert(argThat((LiabilityRecord record) -> "ORDER".equals(record.getBizType())
                && "BO-106".equals(record.getBizNo()) && "遗失".equals(record.getType())
                && Long.valueOf(20).equals(record.getResponsibleUserId())
                && "CONFIRMED".equals(record.getStatus())));
        verify(stock).decrementOpen(20L);
        verify(push).afterCommit(any(Runnable.class));
    }

    @Test
    void unknownEventAndMissingBorrowAttributesFailClosed() {
        assertThrows(IllegalStateException.class, () -> actions.apply(BagEvent.CREATE,
                context(BagEvent.CREATE, Map.of(), null)));
        assertThrows(RuntimeException.class, () -> actions.apply(BagEvent.BORROW,
                context(BagEvent.BORROW, Map.of("orderNo", "BO", "userId", 20L), null)));
        verifyNoInteractions(orders, liabilities, counters, stock, push);
    }

    private static BagOrder order(OrderStatus status) {
        BagOrder order = new BagOrder();
        order.setId(2L);
        order.setOrderNo("BO-1");
        order.setBagCode("B-1");
        order.setUserId(20L);
        order.setStationId(7L);
        order.setStatus(status.code());
        order.setVersion(3);
        return order;
    }

    private static BagTransitionContext context(BagEvent event, Map<String, Object> attributes, String requestId) {
        TransitionCommand command = TransitionCommand.builder().bagCode("B-1").event(event)
                .operatorId(99L).stationId(7L)
                .clientRequestId(requestId == null ? "01234567-89ab-cdef-0123-456789abcdef" : requestId)
                .attributes(attributes).build();
        return BagTransitionContext.builder().cmd(command).operatorId(99L).build();
    }
}
