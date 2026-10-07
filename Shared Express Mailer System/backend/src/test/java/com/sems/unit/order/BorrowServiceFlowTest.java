package com.sems.unit.order;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sems.common.Constants;
import com.sems.config.SemsProperties;
import com.sems.infra.*;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.dto.BorrowDto;
import com.sems.module.order.dto.ShippingDto;
import com.sems.module.order.dto.OrderDetailVo;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.entity.StockCompensationLog;
import com.sems.module.order.mapper.*;
import com.sems.module.order.service.BorrowService;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.*;
import com.sems.module.statemachine.*;
import com.sems.push.PushService;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BorrowServiceFlowTest {
    private BagMapper bags;
    private StationMapper stations;
    private AllocationDetailMapper allocationDetails;
    private AllocationOrderMapper allocationOrders;
    private BagOrderMapper orders;
    private StockCompensationLogMapper compensationLogs;
    private StockService stock;
    private IdempotentService idempotency;
    private RateLimitService rateLimit;
    private BagTransitionService transitions;
    private com.sems.module.order.service.OrderService orderService;
    private SemsProperties properties;
    private BorrowService service;

    @BeforeEach
    void setup() {
        initTable(BagOrder.class);
        initTable(com.sems.module.station.entity.AllocationDetail.class);
        initTable(com.sems.module.station.entity.AllocationOrder.class);
        bags = mock(BagMapper.class);
        stations = mock(StationMapper.class);
        allocationDetails = mock(AllocationDetailMapper.class);
        allocationOrders = mock(AllocationOrderMapper.class);
        orders = mock(BagOrderMapper.class);
        compensationLogs = mock(StockCompensationLogMapper.class);
        stock = mock(StockService.class);
        idempotency = mock(IdempotentService.class);
        rateLimit = mock(RateLimitService.class);
        transitions = mock(BagTransitionService.class);
        orderService = mock(com.sems.module.order.service.OrderService.class);
        properties = new SemsProperties();
        service = new BorrowService(bags, stations, allocationDetails, allocationOrders, orders,
                compensationLogs, stock, idempotency, rateLimit, transitions,
                orderService, properties,
                mock(PlatformTransactionManager.class));
        when(idempotency.findCompleted(anyString(), anyString())).thenReturn(null);
        when(allocationDetails.selectList(any())).thenReturn(java.util.List.of());
        AuthContext.clear();
    }

    @AfterEach
    void clearAuth() { AuthContext.clear(); }

    @Test
    void successfulBorrowReservesStockThenFiresBorrowTransitionAndReturnsPersistedOrder() {
        LoginUser user = user();
        AuthContext.set(user);
        Bag bag = new Bag();
        bag.setBagCode("SJB-L-2609-00001-4");
        bag.setStatus(BagStatus.IDLE.code());
        bag.setStationId(7L);
        when(bags.selectOne(any())).thenReturn(bag);
        Station station = new Station();
        station.setId(7L);
        station.setName("越秀站");
        station.setStatus(Constants.STATUS_ENABLED);
        when(stations.selectById(7L)).thenReturn(station);
        when(stock.isDegraded()).thenReturn(false);
        when(transitions.fire(any(TransitionCommand.class))).thenReturn(BagTransitionResult.builder()
                .bagCode(bag.getBagCode()).fromStatus(BagStatus.IDLE).toStatus(BagStatus.ALLOCATED).build());
        BagOrder persisted = new BagOrder();
        persisted.setOrderNo("BO-1");
        persisted.setBagCode(bag.getBagCode());
        persisted.setStationId(7L);
        persisted.setStatus(OrderStatus.PENDING_SEND.code());
        when(orders.selectOne(any())).thenReturn(persisted);
        BorrowDto dto = new BorrowDto();
        dto.setBagCode(bag.getBagCode());
        String requestId = "01234567-89ab-cdef-0123-456789abcdef";

        var result = service.borrow(dto, requestId);

        assertEquals("BO-1", result.getOrderNo());
        assertEquals("SJB-L-2609-00001-4", result.getBagCode());
        assertEquals(7L, result.getStationId());
        assertEquals("越秀站", result.getStationName());
        assertEquals(OrderStatus.PENDING_SEND.code(), result.getStatus());
        assertTrue(result.getExpiresHint().contains("前提交寄件信息"));
        var command = org.mockito.ArgumentCaptor.forClass(TransitionCommand.class);
        verify(transitions).fire(command.capture());
        assertEquals(BagEvent.BORROW, command.getValue().getEvent());
        assertEquals(requestId, command.getValue().getClientRequestId());
        verify(stock).tryDeduct(anyString(), eq(7L), eq(20L), anyString());
        verify(stock, never()).deleteHold(anyString());
        verify(idempotency).markCompleted(anyString(), eq(requestId), anyString());
        verifyNoInteractions(compensationLogs);
    }

    @Test
    void occupiedOrUnusableBagsNeverReachStockReservation() {
        AuthContext.set(user());
        Bag bag = new Bag();
        bag.setBagCode("SJB-L-2609-00001-4");
        bag.setStatus(BagStatus.LOST.code());
        bag.setStationId(7L);
        when(bags.selectOne(any())).thenReturn(bag);
        BorrowDto dto = new BorrowDto();
        dto.setBagCode(bag.getBagCode());

        assertThrows(RuntimeException.class,
                () -> service.borrow(dto, "01234567-89ab-cdef-0123-456789abcdef"));

        verifyNoInteractions(stock, transitions);
        verify(idempotency).clear(anyString(), anyString());
    }

    @Test
    void pendingAllocationReferenceBlocksBorrowBeforeInventoryReservation() {
        AuthContext.set(user());
        Bag bag = new Bag();
        bag.setBagCode("SJB-L-2609-00001-4");
        bag.setStatus(BagStatus.IDLE.code());
        bag.setStationId(7L);
        when(bags.selectOne(any())).thenReturn(bag);
        var detail = new com.sems.module.station.entity.AllocationDetail();
        detail.setAllocationId(44L);
        when(allocationDetails.selectList(any())).thenReturn(java.util.List.of(detail));
        when(allocationOrders.selectCount(any())).thenReturn(1L);
        BorrowDto dto = new BorrowDto();
        dto.setBagCode(bag.getBagCode());

        assertThrows(RuntimeException.class,
                () -> service.borrow(dto, "01234567-89ab-cdef-0123-456789abcdef"));

        verifyNoInteractions(stock, transitions);
        verify(idempotency).clear(anyString(), anyString());
    }

    @Test
    void databaseDuplicateOrderErrorTriggersCompensationWhenOrderDidNotCommit() {
        AuthContext.set(user());
        Bag bag = new Bag();
        bag.setBagCode("SJB-L-2609-00001-4");
        bag.setStatus(BagStatus.IDLE.code());
        bag.setStationId(7L);
        when(bags.selectOne(any())).thenReturn(bag);
        Station station = new Station();
        station.setId(7L);
        station.setName("越秀站");
        station.setStatus(Constants.STATUS_ENABLED);
        when(stations.selectById(7L)).thenReturn(station);
        when(stock.isDegraded()).thenReturn(false);
        when(transitions.fire(any(TransitionCommand.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(orders.selectOne(any())).thenReturn(null);
        when(stock.compensate(anyString(), anyLong(), anyLong())).thenReturn(true);
        BorrowDto dto = new BorrowDto();
        dto.setBagCode(bag.getBagCode());

        assertThrows(DuplicateKeyException.class,
                () -> service.borrow(dto, "01234567-89ab-cdef-0123-456789abcdef"));

        verify(stock).compensate(anyString(), eq(7L), eq(20L));
        verify(compensationLogs).insert(any(StockCompensationLog.class));
        verify(idempotency).clear(anyString(), anyString());
    }

    @Test
    void shippingStoresOnlyMaskedAndHashedRecipientPhoneAndDeletesHoldAfterTransition() {
        AuthContext.set(user());
        BagOrder order = new BagOrder();
        order.setId(2L);
        order.setOrderNo("BO-SHIP");
        order.setBagCode("SJB-L-2609-00001-4");
        order.setUserId(20L);
        order.setStationId(7L);
        order.setStatus(OrderStatus.PENDING_SEND.code());
        order.setVersion(3);
        when(orders.selectOne(any())).thenReturn(order);
        when(orders.selectCount(any())).thenReturn(0L);
        when(transitions.fire(any(TransitionCommand.class))).thenReturn(BagTransitionResult.builder()
                .bagCode(order.getBagCode()).fromStatus(BagStatus.ALLOCATED).toStatus(BagStatus.IN_USE).build());
        OrderDetailVo detail = new OrderDetailVo();
        when(orderService.detail("BO-SHIP")).thenReturn(detail);
        ShippingDto dto = new ShippingDto();
        dto.setExpressCompany("顺丰速运");
        dto.setTrackingNo("SF123456789");
        dto.setRecipientPhone("13800138000");

        var result = service.shipping("BO-SHIP", dto, "21234567-89ab-cdef-0123-456789abcdef");

        assertSame(detail, result);
        var captor = org.mockito.ArgumentCaptor.forClass(TransitionCommand.class);
        verify(transitions).fire(captor.capture());
        assertEquals(BagEvent.SHIP, captor.getValue().getEvent());
        assertEquals("138****8000", captor.getValue().getAttributes().get("recipientPhoneMask"));
        assertNotEquals("13800138000", captor.getValue().getAttributes().get("recipientPhoneHash"));
        verify(stock).deleteHold("BO-SHIP");
        verify(idempotency).markCompleted(anyString(), anyString(), anyString());
    }

    @Test
    void withdrawCancelsPendingOrderAndDeletesHoldWithoutStockCompensation() {
        AuthContext.set(user());
        BagOrder order = new BagOrder();
        order.setId(3L);
        order.setOrderNo("BO-WITHDRAW");
        order.setBagCode("SJB-L-2609-00001-4");
        order.setUserId(20L);
        order.setStatus(OrderStatus.PENDING_SEND.code());
        order.setVersion(4);
        when(orders.selectOne(any())).thenReturn(order);
        when(orders.updateById(any(BagOrder.class))).thenReturn(1);

        service.withdraw("BO-WITHDRAW", "31234567-89ab-cdef-0123-456789abcdef");

        verify(orders).updateById(argThat((BagOrder update) -> OrderStatus.CANCELLED.code().equals(update.getStatus())
                && update.getCancelledAt() != null && Integer.valueOf(4).equals(update.getVersion())));
        verify(stock).deleteHold("BO-WITHDRAW");
        verifyNoInteractions(transitions, compensationLogs);
        verify(idempotency).markCompleted(anyString(), anyString(), anyString());
    }

    private static LoginUser user() {
        LoginUser user = new LoginUser();
        user.setUserId(20L);
        user.setRole(Constants.ROLE_USER);
        return user;
    }

    private static void initTable(Class<?> type) {
        if (TableInfoHelper.getTableInfo(type) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
    }
}
