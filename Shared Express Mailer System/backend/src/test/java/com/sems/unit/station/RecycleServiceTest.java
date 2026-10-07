package com.sems.unit.station;

import com.sems.common.ErrorCode;
import com.sems.config.SemsProperties;
import com.sems.infra.IdempotentService;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.bag.entity.Bag;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.stats.service.StatsService;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.statemachine.BagTransitionResult;
import com.sems.module.statemachine.BagTransitionService;
import com.sems.module.statemachine.OrderStatus;
import com.sems.module.statemachine.TransitionCommand;
import com.sems.module.station.dto.RecycleConfirmDto;
import com.sems.module.station.dto.RecycleConfirmItemDto;
import com.sems.module.station.service.RecycleService;
import com.sems.module.station.service.StationService;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecycleServiceTest {
    private final StationService stations = mock(StationService.class);
    private final BagMapper bags = mock(BagMapper.class);
    private final BagOrderMapper orders = mock(BagOrderMapper.class);
    private final BagTransitionService transitions = mock(BagTransitionService.class);
    private final IdempotentService idempotency = mock(IdempotentService.class);
    private final SemsProperties properties = new SemsProperties();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final StatsService stats = mock(StatsService.class);
    private final RecycleService service = new RecycleService(stations, bags, orders, transitions,
            idempotency, properties, redis, events, stats);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> redisValues = mock(ValueOperations.class);

    @org.junit.jupiter.api.AfterEach
    void clearAuth() { AuthContext.clear(); }

    RecycleServiceTest() { properties.getBatch().setMaxSize(2); }

    @Test
    void recycleConfirmRequiresNonemptyListAfterStationAccessChecks() {
        RecycleConfirmDto empty = new RecycleConfirmDto();
        empty.setBags(List.of());
        var ex = assertThrows(com.sems.common.BizException.class,
                () -> service.recycleConfirm(3L, empty, "request"));
        assertSame(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        verify(stations).requireStation(3L);
        verify(stations).assertStationAccess(3L);
        verifyNoInteractions(idempotency, transitions);
    }

    @Test
    void recycleConfirmRejectsOversizeAndNullEntriesBeforeTakingIdempotencyKeys() {
        RecycleConfirmItemDto item = new RecycleConfirmItemDto();
        RecycleConfirmDto tooMany = new RecycleConfirmDto();
        tooMany.setBags(List.of(item, item, item));
        assertSame(ErrorCode.PARAM_INVALID, assertThrows(com.sems.common.BizException.class,
                () -> service.recycleConfirm(3L, tooMany, "request")).getErrorCode());

        RecycleConfirmDto withNull = new RecycleConfirmDto();
        withNull.setBags(Arrays.asList((RecycleConfirmItemDto) null));
        assertSame(ErrorCode.PARAM_INVALID, assertThrows(com.sems.common.BizException.class,
                () -> service.recycleConfirm(3L, withNull, "request")).getErrorCode());
        verifyNoInteractions(idempotency, transitions);
    }

    @Test
    void successfulCrossStationMarkedReturnRoutesAndWritesAuditAndDailyCount() {
        LoginUser operator = new LoginUser();
        operator.setUserId(18L);
        operator.setRole("STATION_ADMIN");
        operator.setStationId(3L);
        AuthContext.set(operator);
        when(idempotency.findCompleted(anyString(), eq("crid"))).thenReturn(null);
        when(idempotency.tryAcquireBag(eq("crid"), anyString())).thenReturn(true);
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.increment("risk:confirm:3:18")).thenReturn(1L);

        String code = "SJB-L-2609-00001-4";
        Bag bag = new Bag();
        bag.setBagCode(code);
        bag.setStationId(2L);
        bag.setStatus(BagStatus.IN_USE.code());
        when(bags.selectOne(any())).thenReturn(bag);
        BagOrder order = new BagOrder();
        order.setId(41L);
        order.setOrderNo("BO41");
        order.setBagCode(code);
        order.setStatus(OrderStatus.PENDING_CONFIRM.code());
        when(orders.selectList(any())).thenReturn(List.of(order));
        BagTransitionResult transitioned = BagTransitionResult.builder()
                .bagCode(code).fromStatus(BagStatus.IN_USE).toStatus(BagStatus.TO_DISINFECT).build();
        when(transitions.fire(any(TransitionCommand.class))).thenReturn(transitioned);

        RecycleConfirmItemDto item = new RecycleConfirmItemDto();
        item.setBagCode(code);
        RecycleConfirmDto dto = new RecycleConfirmDto();
        dto.setBags(List.of(item));
        var result = service.recycleConfirm(3L, dto, "crid");

        assertEquals(1, result.getSuccess());
        assertEquals(0, result.getFailed());
        assertEquals(1, result.getCrossStation());
        assertEquals(BagEvent.RECOVER_CONFIRM.code(), item.getEvent());
        assertEquals(BagStatus.TO_DISINFECT.code(), item.getToStatus());
        verify(redis).expire("risk:confirm:3:18", java.time.Duration.ofMinutes(10));
        verify(stats).incrementTodayReturn(3L);
        verify(idempotency).markCompleted(anyString(), eq("crid"), anyString());
        org.mockito.ArgumentCaptor<Object> eventCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        assertInstanceOf(com.sems.common.event.AuditEvent.class, eventCaptor.getValue());
        com.sems.common.event.AuditEvent audit = (com.sems.common.event.AuditEvent) eventCaptor.getValue();
        assertEquals("回收确认-跨站", audit.getAction());
        assertTrue(audit.getParamsMasked().contains(code));
    }
}
