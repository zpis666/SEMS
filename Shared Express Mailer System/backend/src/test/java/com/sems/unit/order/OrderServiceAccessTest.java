package com.sems.unit.order;

import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.infra.IdempotentService;
import com.sems.module.order.dto.ReturnMarkDto;
import com.sems.module.order.dto.LostConfirmDto;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.dto.OrderQuery;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.order.service.OrderService;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.statemachine.BagTransitionService;
import com.sems.module.statemachine.BagTransitionResult;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.statemachine.OrderStatus;
import com.sems.module.statemachine.TransitionCommand;
import com.sems.module.bag.entity.Bag;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.station.entity.Station;
import com.sems.push.PushService;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderServiceAccessTest {
    private static final String REQUEST_ID = "01234567-89ab-cdef-0123-456789abcdef";
    private BagOrderMapper orders;
    private BagMapper bags;
    private IdempotentService idempotency;
    private BagTransitionService transitions;
    private StationMapper stations;
    private PushService push;
    private OrderService service;

    @BeforeEach
    void setup() {
        orders = mock(BagOrderMapper.class);
        stations = mock(StationMapper.class);
        idempotency = mock(IdempotentService.class);
        transitions = mock(BagTransitionService.class);
        push = mock(PushService.class);
        bags = mock(BagMapper.class);
        service = new OrderService(orders, bags, stations,
                idempotency, transitions, push);
        AuthContext.clear();
    }

    @AfterEach
    void clearAuth() { AuthContext.clear(); }

    @Test
    void personalOrderQueriesRequireAuthentication() {
        assertThrows(BizException.class, () -> service.pageMine(new OrderQuery()));
        assertThrows(BizException.class, () -> service.detail("ORD-1"));
        verifyNoInteractions(orders);
    }

    @Test
    void personalOrderDetailHidesMissingAndForeignOrders() {
        LoginUser user = new LoginUser();
        user.setUserId(20L);
        user.setRole(Constants.ROLE_USER);
        AuthContext.set(user);
        when(orders.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> service.detail("ORD-MISSING"));
        BagOrder foreign = new BagOrder();
        foreign.setUserId(21L);
        when(orders.selectOne(any())).thenReturn(foreign);
        assertThrows(BizException.class, () -> service.detail("ORD-FOREIGN"));
        verify(orders, times(2)).selectOne(any());
    }

    @Test
    void invalidStatusFilterIsRejectedBeforeQuery() {
        OrderQuery query = new OrderQuery();
        query.setStatus("UNRECOGNIZED");
        assertThrows(BizException.class, () -> service.pageAll(query));
        verifyNoInteractions(orders);
    }

    @Test
    void returnMarkRejectsWrongOrderStateAndClearsItsIdempotencyReservation() {
        LoginUser user = new LoginUser();
        user.setUserId(20L);
        user.setRole(Constants.ROLE_USER);
        AuthContext.set(user);
        BagOrder order = new BagOrder();
        order.setUserId(20L);
        order.setStatus(OrderStatus.PENDING_SEND.code());
        when(idempotency.findCompleted(anyString(), anyString())).thenReturn(null);
        when(orders.selectOne(any())).thenReturn(order);
        ReturnMarkDto dto = new ReturnMarkDto();
        dto.setStationId(8L);

        assertThrows(BizException.class, () -> service.returnMark("ORD-1", dto, REQUEST_ID));

        verify(idempotency).clear(anyString(), eq(REQUEST_ID));
        verifyNoInteractions(transitions, stations, push);
    }

    @Test
    void returnMarkValidatesEnabledStationThenFiresTransitionAndRegistersNotice() {
        LoginUser user = new LoginUser();
        user.setUserId(20L);
        user.setRole(Constants.ROLE_USER);
        AuthContext.set(user);
        BagOrder order = new BagOrder();
        order.setUserId(20L);
        order.setStatus(OrderStatus.IN_USE.code());
        order.setBagCode("B-1");
        when(idempotency.findCompleted(anyString(), anyString())).thenReturn(null);
        when(orders.selectOne(any())).thenReturn(order);
        Station station = new Station();
        station.setId(8L);
        station.setName("天河站");
        station.setStatus(Constants.STATUS_ENABLED);
        when(stations.selectById(8L)).thenReturn(station);
        when(transitions.fire(any(TransitionCommand.class))).thenReturn(BagTransitionResult.builder()
                .bagCode("B-1").fromStatus(BagStatus.IN_USE).toStatus(BagStatus.IN_USE).replayed(false).build());
        ReturnMarkDto dto = new ReturnMarkDto();
        dto.setStationId(8L);

        service.returnMark("ORD-1", dto, REQUEST_ID);

        var captor = org.mockito.ArgumentCaptor.forClass(TransitionCommand.class);
        verify(transitions).fire(captor.capture());
        assertEquals("B-1", captor.getValue().getBagCode());
        assertEquals(com.sems.module.statemachine.BagEvent.RETURN_MARK, captor.getValue().getEvent());
        verify(push).afterCommit(any(Runnable.class));
        verify(idempotency).markCompleted(anyString(), eq(REQUEST_ID), anyString());
    }

    @Test
    void lostConfirmAfterFourteenDaysFiresLostTransitionAndCompletesIdempotency() {
        LoginUser admin = new LoginUser();
        admin.setUserId(1L);
        admin.setRole(Constants.ROLE_PLATFORM_ADMIN);
        AuthContext.set(admin);
        BagOrder order = new BagOrder();
        order.setOrderNo("ORD-LOST");
        order.setUserId(20L);
        order.setBagCode("B-LOST");
        order.setStationId(7L);
        order.setStatus(OrderStatus.IN_USE.code());
        order.setShippedAt(java.time.LocalDateTime.now().minusDays(15));
        Bag bag = new Bag();
        bag.setBagCode("B-LOST");
        bag.setStatus(BagStatus.IN_USE.code());
        when(orders.selectOne(any())).thenReturn(order);
        when(bags.selectOne(any())).thenReturn(bag);
        when(idempotency.findCompleted(anyString(), anyString())).thenReturn(null);
        when(transitions.fire(any(TransitionCommand.class))).thenReturn(BagTransitionResult.builder()
                .bagCode("B-LOST").fromStatus(BagStatus.IN_USE).toStatus(BagStatus.LOST).build());
        LostConfirmDto dto = new LostConfirmDto();
        dto.setConclusion("超过时限未归还，经核验遗失");
        dto.setNote("已联系用户");
        dto.setResponsibleUserId(20L);

        service.lostConfirm("ORD-LOST", dto, REQUEST_ID);

        var captor = org.mockito.ArgumentCaptor.forClass(TransitionCommand.class);
        verify(transitions).fire(captor.capture());
        assertEquals(BagEvent.LOST_CONFIRM, captor.getValue().getEvent());
        assertEquals("ORD-LOST", captor.getValue().getAttributes().get("orderNo"));
        verify(idempotency).markCompleted(anyString(), eq(REQUEST_ID), anyString());
    }

    @Test
    void lostConfirmBeforeFourteenDayWindowIsRejectedAndReservationCleared() {
        LoginUser admin = new LoginUser();
        admin.setUserId(1L);
        admin.setRole(Constants.ROLE_PLATFORM_ADMIN);
        AuthContext.set(admin);
        BagOrder order = new BagOrder();
        order.setOrderNo("ORD-TOO-EARLY");
        order.setUserId(20L);
        order.setBagCode("B-EARLY");
        order.setStatus(OrderStatus.IN_USE.code());
        order.setShippedAt(java.time.LocalDateTime.now().minusDays(13));
        Bag bag = new Bag();
        bag.setStatus(BagStatus.IN_USE.code());
        when(orders.selectOne(any())).thenReturn(order);
        when(bags.selectOne(any())).thenReturn(bag);
        when(idempotency.findCompleted(anyString(), anyString())).thenReturn(null);
        LostConfirmDto dto = new LostConfirmDto();
        dto.setConclusion("待核实");

        assertThrows(BizException.class, () -> service.lostConfirm("ORD-TOO-EARLY", dto, REQUEST_ID));

        verifyNoInteractions(transitions);
        verify(idempotency).clear(anyString(), eq(REQUEST_ID));
    }
}
