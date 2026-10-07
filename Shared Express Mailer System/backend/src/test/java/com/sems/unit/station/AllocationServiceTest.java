package com.sems.unit.station;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sems.config.SemsProperties;
import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.infra.IdempotentService;
import com.sems.module.audit.service.AuditLogService;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.mapper.LiabilityRecordMapper;
import com.sems.module.statemachine.BagTransitionService;
import com.sems.module.station.entity.AllocationDetail;
import com.sems.module.station.entity.AllocationOrder;
import com.sems.module.station.entity.Station;
import com.sems.module.bag.entity.Bag;
import com.sems.module.order.entity.LiabilityRecord;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.statemachine.BagTransitionResult;
import com.sems.module.station.dto.AllocationCancelDto;
import com.sems.module.station.dto.AllocationDispatchDto;
import com.sems.module.station.dto.AllocationReceiveDto;
import com.sems.module.station.dto.AllocationLostDto;
import com.sems.module.station.dto.AllocationRemoveDto;
import com.sems.module.station.dto.CreateAllocationDto;
import com.sems.module.station.mapper.AllocationDetailMapper;
import com.sems.module.station.mapper.AllocationOrderMapper;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.station.service.AllocationService;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AllocationServiceTest {
    private final AllocationOrderMapper orders = mock(AllocationOrderMapper.class);
    private final AllocationDetailMapper details = mock(AllocationDetailMapper.class);
    private final StationMapper stations = mock(StationMapper.class);
    private final BagMapper bags = mock(BagMapper.class);
    private final LiabilityRecordMapper liabilities = mock(LiabilityRecordMapper.class);
    private final BagTransitionService transitions = mock(BagTransitionService.class);
    private final IdempotentService idempotency = mock(IdempotentService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final SemsProperties properties = new SemsProperties();
    private final AllocationService service = new AllocationService(orders, details, stations, bags, liabilities,
            transitions, idempotency, audit, properties);

    AllocationServiceTest() { properties.getBatch().setMaxSize(500); }

    @AfterEach
    void clearAuth() { AuthContext.clear(); }

    @Test
    void blankBagCodeIsNeverConsideredLockedAndDoesNotQueryDatabase() {
        assertFalse(service.isBagLocked(null));
        assertFalse(service.isBagLocked("  \t"));
        verifyNoInteractions(details, orders);
    }

    @Test
    void bagWithoutPendingDispatchDetailIsUnlocked() {
        when(details.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        assertFalse(service.isBagLocked("SJB-L-2609-00001-4"));

        verifyNoInteractions(orders);
    }

    @Test
    void pendingDetailLocksBagOnlyWhileItsAllocationIsPendingOrInTransit() {
        AllocationDetail detail = new AllocationDetail();
        detail.setBagCode("SJB-L-2609-00001-4");
        detail.setAllocationId(19L);
        when(details.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(detail));
        AllocationOrder active = new AllocationOrder();
        active.setId(19L);
        when(orders.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(active));

        assertTrue(service.isBagLocked("SJB-L-2609-00001-4"));

        when(orders.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        assertFalse(service.isBagLocked("SJB-L-2609-00001-4"));
    }

    @Test
    void allocationDetailRejectsUnrelatedStationAdminAfterLoadingResource() {
        LoginUser operator = new LoginUser();
        operator.setRole("STATION_ADMIN");
        operator.setStationId(3L);
        AuthContext.set(operator);
        AllocationOrder order = new AllocationOrder();
        order.setId(7L);
        order.setFromStationId(1L);
        order.setToStationId(2L);
        when(orders.selectById(7L)).thenReturn(order);

        BizException ex = assertThrows(BizException.class, () -> service.detail(7L));

        assertSame(ErrorCode.NO_DATA_PERMISSION, ex.getErrorCode());
        verify(orders).selectById(7L);
        verifyNoInteractions(details, stations);
        AuthContext.clear();
    }

    @Test
    void creatingTransferBetweenSameStationIsRejectedAndIdempotencyPlaceholderCleared() {
        when(idempotency.findCompleted("POST /allocations", "123e4567-e89b-12d3-a456-426614174000"))
                .thenReturn(null);
        CreateAllocationDto dto = new CreateAllocationDto();
        dto.setFromStationId(5L);
        dto.setToStationId(5L);

        BizException ex = assertThrows(BizException.class,
                () -> service.create(dto, "123e4567-e89b-12d3-a456-426614174000"));

        assertSame(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        verify(idempotency).markProcessing("POST /allocations", "123e4567-e89b-12d3-a456-426614174000");
        verify(idempotency).clear("POST /allocations", "123e4567-e89b-12d3-a456-426614174000");
        verifyNoInteractions(stations, bags, details);
    }

    @Test
    void cancellationRejectsInTransitOrderAndClearsRetryPlaceholder() {
        when(idempotency.findCompleted(anyString(), anyString())).thenReturn(null);
        AllocationOrder order = new AllocationOrder();
        order.setId(8L);
        order.setStatus("IN_TRANSIT");
        when(orders.selectById(8L)).thenReturn(order);

        BizException ex = assertThrows(BizException.class,
                () -> service.cancelOrTerminate(8L, new AllocationCancelDto(),
                        "123e4567-e89b-12d3-a456-426614174000"));

        assertSame(ErrorCode.ORDER_STATE_NOT_ALLOWED, ex.getErrorCode());
        verify(idempotency).clear("DELETE /allocations/{id}", "123e4567-e89b-12d3-a456-426614174000");
        verifyNoInteractions(details);
    }

    @Test
    void pendingAllocationCancellationUnlocksDetailsAndRecordsCompletion() {
        String requestId = "123e4567-e89b-12d3-a456-426614174006";
        when(idempotency.findCompleted("DELETE /allocations/{id}", requestId)).thenReturn(null);
        AllocationOrder order = allocationOrder(14L, "PENDING_DISPATCH", 1L, 2L);
        when(orders.selectById(14L)).thenReturn(order);
        when(orders.update(nullable(AllocationOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(1);
        when(details.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(stations.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        AllocationCancelDto dto = new AllocationCancelDto();
        dto.setReason("  重复建单 ");

        var result = service.cancelOrTerminate(14L, dto, requestId);

        assertEquals(14L, result.getId());
        verify(details).update(isNull(AllocationDetail.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        var wrapperCaptor = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(orders).update(isNull(AllocationOrder.class), wrapperCaptor.capture());
        assertTrue(((com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?>) wrapperCaptor.getValue())
                .getParamNameValuePairs().containsValue("CANCELLED"));
        verify(idempotency).markCompleted(eq("DELETE /allocations/{id}"), eq(requestId), anyString());
    }

    @Test
    void abnormalAllocationTerminationWritesExplicitAuditRecord() {
        setUser("PLATFORM_ADMIN", null, 74L);
        String requestId = "123e4567-e89b-12d3-a456-426614174007";
        when(idempotency.findCompleted("DELETE /allocations/{id}", requestId)).thenReturn(null);
        AllocationOrder order = allocationOrder(15L, "ABNORMAL", 1L, 2L);
        when(orders.selectById(15L)).thenReturn(order);
        when(orders.update(nullable(AllocationOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(1);
        when(details.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(stations.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var result = service.cancelOrTerminate(15L, new AllocationCancelDto(), requestId);

        assertEquals(15L, result.getId());
        var auditCaptor = org.mockito.ArgumentCaptor.forClass(com.sems.module.audit.entity.AuditLogEntity.class);
        verify(audit).record(auditCaptor.capture());
        assertEquals("终止调拨单", auditCaptor.getValue().getAction());
        assertEquals(74L, auditCaptor.getValue().getOperatorId());
        assertEquals("AL15", order.getAllocationNo());
        AuthContext.clear();
    }

    @Test
    void detailBuildsProgressCountsFromBagLevelRecordsForAuthorizedPlatformAdmin() {
        LoginUser operator = new LoginUser();
        operator.setRole("PLATFORM_ADMIN");
        AuthContext.set(operator);
        AllocationOrder order = new AllocationOrder();
        order.setId(9L);
        order.setAllocationNo("AL-9");
        order.setFromStationId(1L);
        order.setToStationId(2L);
        order.setStatus("IN_TRANSIT");
        when(orders.selectById(9L)).thenReturn(order);
        AllocationDetail pending = new AllocationDetail();
        pending.setId(1L);
        pending.setAllocationId(9L);
        pending.setStatus("PENDING_DISPATCH");
        AllocationDetail dispatched = new AllocationDetail();
        dispatched.setId(2L);
        dispatched.setAllocationId(9L);
        dispatched.setStatus("DISPATCHED");
        AllocationDetail received = new AllocationDetail();
        received.setId(3L);
        received.setAllocationId(9L);
        received.setStatus("RECEIVED");
        when(details.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pending, dispatched, received));
        Station source = new Station();
        source.setId(1L);
        source.setName("源站");
        Station target = new Station();
        target.setId(2L);
        target.setName("目标站");
        when(stations.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(source, target));

        var result = service.detail(9L);

        assertEquals("AL-9", result.getAllocationNo());
        assertEquals("源站", result.getFromStationName());
        assertEquals("目标站", result.getToStationName());
        assertEquals(3, result.getTotalCount());
        assertEquals(1, result.getPendingCount());
        assertEquals(1, result.getDispatchedCount());
        assertEquals(1, result.getReceivedCount());
        assertEquals(3, result.getDetails().size());
        AuthContext.clear();
    }

    @Test
    void createStoresOrderAndDeduplicatedIdleBagDetails() {
        setUser("PLATFORM_ADMIN", null, 70L);
        String requestId = "123e4567-e89b-12d3-a456-426614174001";
        when(idempotency.findCompleted("POST /allocations", requestId)).thenReturn(null);
        Station from = station(1L, "源站");
        Station to = station(2L, "目标站");
        when(stations.selectById(1L)).thenReturn(from);
        when(stations.selectById(2L)).thenReturn(to);
        String code = "SJB-L-2609-00001-4";
        Bag bag = new Bag();
        bag.setBagCode(code);
        bag.setStatus(BagStatus.IDLE.code());
        bag.setStationId(1L);
        when(bags.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(bag));
        AllocationDetail insertedDetail = new AllocationDetail();
        insertedDetail.setId(1L);
        insertedDetail.setAllocationId(90L);
        insertedDetail.setBagCode(code);
        insertedDetail.setStatus("PENDING_DISPATCH");
        when(details.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(), List.of(insertedDetail));
        when(orders.insert(any(AllocationOrder.class))).thenAnswer(invocation -> {
            ((AllocationOrder) invocation.getArgument(0)).setId(90L);
            return 1;
        });
        when(stations.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(from, to));
        CreateAllocationDto dto = new CreateAllocationDto();
        dto.setFromStationId(1L);
        dto.setToStationId(2L);
        dto.setBagCodes(List.of(code, code));

        var result = service.create(dto, requestId);

        assertEquals(90L, result.getId());
        assertEquals(1, result.getTotalCount());
        assertEquals(1, result.getPendingCount());
        verify(orders).insert(any(AllocationOrder.class));
        var detailCaptor = org.mockito.ArgumentCaptor.forClass(AllocationDetail.class);
        verify(details).insert(detailCaptor.capture());
        assertEquals("PENDING_DISPATCH", detailCaptor.getValue().getStatus());
        assertEquals(code, detailCaptor.getValue().getBagCode());
        verify(idempotency).markCompleted(eq("POST /allocations"), eq(requestId), anyString());
        AuthContext.clear();
    }

    @Test
    void dispatchMovesPendingDetailForSourceStationAdmin() {
        setUser("STATION_ADMIN", 1L, 71L);
        String requestId = "123e4567-e89b-12d3-a456-426614174002";
        when(idempotency.findCompleted("POST /allocations/{id}/dispatch", requestId)).thenReturn(null);
        when(orders.selectById(10L)).thenReturn(allocationOrder(10L, "PENDING_DISPATCH", 1L, 2L));
        String code = "SJB-L-2609-00011-5";
        AllocationDetail detail = new AllocationDetail();
        detail.setId(22L);
        detail.setAllocationId(10L);
        detail.setBagCode(code);
        detail.setStatus("PENDING_DISPATCH");
        when(details.selectOne(any(LambdaQueryWrapper.class))).thenReturn(detail);
        when(transitions.fire(any())).thenReturn(BagTransitionResult.builder()
                .toStatus(BagStatus.TRANSFERRING).build());
        AllocationDispatchDto.BagItem item = new AllocationDispatchDto.BagItem();
        item.setBagCode(code);
        item.setRemark("逐袋备注");
        AllocationDispatchDto dto = new AllocationDispatchDto();
        dto.setBags(List.of(item));
        dto.setRemark("整批备注");

        var result = service.dispatch(10L, dto, requestId);

        assertEquals(1, result.size());
        assertTrue(result.get(0).success());
        assertEquals(code, result.get(0).bagCode());
        var commandCaptor = org.mockito.ArgumentCaptor.forClass(com.sems.module.statemachine.TransitionCommand.class);
        verify(transitions).fire(commandCaptor.capture());
        assertEquals(BagEvent.ALLOCATE_OUT, commandCaptor.getValue().getEvent());
        assertEquals(1L, commandCaptor.getValue().getStationId());
        verify(details).update(isNull(AllocationDetail.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(orders).update(isNull(AllocationOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(idempotency).markCompleted(eq("POST /allocations/{id}/dispatch"), eq(requestId), anyString());
        AuthContext.clear();
    }

    @Test
    void createRejectsIdleBagOwnedByDifferentSourceStation() {
        setUser("PLATFORM_ADMIN", null, 75L);
        String requestId = "123e4567-e89b-12d3-a456-426614174009";
        when(idempotency.findCompleted("POST /allocations", requestId)).thenReturn(null);
        when(stations.selectById(1L)).thenReturn(station(1L, "源站"));
        when(stations.selectById(2L)).thenReturn(station(2L, "目标站"));
        String code = "SJB-L-2609-00016-A";
        Bag bag = new Bag();
        bag.setBagCode(code);
        bag.setStatus(BagStatus.IDLE.code());
        bag.setStationId(99L);
        when(bags.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(bag));
        when(details.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        CreateAllocationDto dto = new CreateAllocationDto();
        dto.setFromStationId(1L);
        dto.setToStationId(2L);
        dto.setBagCodes(List.of(code));

        BizException ex = assertThrows(BizException.class, () -> service.create(dto, requestId));

        assertSame(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("源站"));
        verify(orders, never()).insert(any(AllocationOrder.class));
        verify(idempotency).clear("POST /allocations", requestId);
        AuthContext.clear();
    }

    @Test
    void receiveCompletesOrderWhenAllActiveDetailsAreReceived() {
        setUser("STATION_ADMIN", 2L, 72L);
        String requestId = "123e4567-e89b-12d3-a456-426614174003";
        when(idempotency.findCompleted("POST /allocations/{id}/receive", requestId)).thenReturn(null);
        when(orders.selectById(11L)).thenReturn(allocationOrder(11L, "IN_TRANSIT", 1L, 2L));
        String code = "SJB-L-2609-00012-6";
        AllocationDetail detail = new AllocationDetail();
        detail.setId(23L);
        detail.setAllocationId(11L);
        detail.setBagCode(code);
        detail.setStatus("DISPATCHED");
        when(details.selectOne(any(LambdaQueryWrapper.class))).thenReturn(detail);
        when(details.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L, 1L);
        when(transitions.fire(any())).thenReturn(BagTransitionResult.builder().toStatus(BagStatus.IDLE).build());
        AllocationReceiveDto.BagItem item = new AllocationReceiveDto.BagItem();
        item.setBagCode(code);
        AllocationReceiveDto dto = new AllocationReceiveDto();
        dto.setBags(List.of(item));

        var result = service.receive(11L, dto, requestId);

        assertTrue(result.get(0).success());
        verify(details).update(isNull(AllocationDetail.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(details, times(2)).selectCount(any(LambdaQueryWrapper.class));
        verify(orders).update(isNull(AllocationOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        var commandCaptor = org.mockito.ArgumentCaptor.forClass(com.sems.module.statemachine.TransitionCommand.class);
        verify(transitions).fire(commandCaptor.capture());
        assertEquals(BagEvent.ALLOCATE_IN, commandCaptor.getValue().getEvent());
        assertEquals(2L, commandCaptor.getValue().getTargetStationId());
        AuthContext.clear();
    }

    @Test
    void omittedLostBagListResolvesInTransitDetailsAndRecordsSourceLiability() {
        setUser("PLATFORM_ADMIN", null, 73L);
        String requestId = "123e4567-e89b-12d3-a456-426614174004";
        when(idempotency.findCompleted("POST /allocations/{id}/lost-confirm", requestId)).thenReturn(null);
        AllocationOrder order = allocationOrder(12L, "IN_TRANSIT", 1L, 2L);
        order.setAllocationNo("AL12");
        when(orders.selectById(12L)).thenReturn(order);
        String code = "SJB-L-2609-00013-7";
        AllocationDetail detail = new AllocationDetail();
        detail.setId(24L);
        detail.setAllocationId(12L);
        detail.setBagCode(code);
        detail.setStatus("DISPATCHED");
        when(details.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(detail));
        when(details.selectOne(any(LambdaQueryWrapper.class))).thenReturn(detail);
        when(transitions.fire(any())).thenReturn(BagTransitionResult.builder().toStatus(BagStatus.LOST).build());

        var result = service.lostConfirm(12L, new AllocationLostDto(), requestId);

        assertEquals(1, result.size());
        assertTrue(result.get(0).success());
        verify(orders).update(isNull(AllocationOrder.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        var liabilityCaptor = org.mockito.ArgumentCaptor.forClass(LiabilityRecord.class);
        verify(liabilities).insert(liabilityCaptor.capture());
        assertEquals("ALLOCATION", liabilityCaptor.getValue().getBizType());
        assertEquals(1L, liabilityCaptor.getValue().getResponsibleStationId());
        assertTrue(liabilityCaptor.getValue().getNote().contains(code));
        AuthContext.clear();
    }

    @Test
    void removeDetailRejectsBagThatIsNoLongerIdleAndClearsPlaceholder() {
        String requestId = "123e4567-e89b-12d3-a456-426614174005";
        when(idempotency.findCompleted("POST /allocations/{id}/remove-detail", requestId)).thenReturn(null);
        when(orders.selectById(13L)).thenReturn(allocationOrder(13L, "PENDING_DISPATCH", 1L, 2L));
        AllocationDetail detail = new AllocationDetail();
        detail.setId(25L);
        detail.setStatus("PENDING_DISPATCH");
        when(details.selectOne(any(LambdaQueryWrapper.class))).thenReturn(detail);
        Bag bag = new Bag();
        bag.setStatus(BagStatus.REPAIRING.code());
        when(bags.selectOne(any(LambdaQueryWrapper.class))).thenReturn(bag);
        AllocationRemoveDto dto = new AllocationRemoveDto();
        dto.setBagCode("SJB-L-2609-00014-8");

        BizException ex = assertThrows(BizException.class, () -> service.removeDetail(13L, dto, requestId));

        assertSame(ErrorCode.ORDER_STATE_NOT_ALLOWED, ex.getErrorCode());
        verify(idempotency).clear("POST /allocations/{id}/remove-detail", requestId);
        verify(details, never()).update(isNull(AllocationDetail.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void removeDetailUnlocksPendingIdleBagAndReturnsUpdatedProgress() {
        String requestId = "123e4567-e89b-12d3-a456-426614174008";
        when(idempotency.findCompleted("POST /allocations/{id}/remove-detail", requestId)).thenReturn(null);
        when(orders.selectById(16L)).thenReturn(allocationOrder(16L, "PENDING_DISPATCH", 1L, 2L));
        AllocationDetail detail = new AllocationDetail();
        detail.setId(26L);
        detail.setStatus("PENDING_DISPATCH");
        when(details.selectOne(any(LambdaQueryWrapper.class))).thenReturn(detail);
        when(details.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        Bag bag = new Bag();
        bag.setStatus(BagStatus.IDLE.code());
        when(bags.selectOne(any(LambdaQueryWrapper.class))).thenReturn(bag);
        when(stations.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        AllocationRemoveDto dto = new AllocationRemoveDto();
        dto.setBagCode("SJB-L-2609-00015-9");
        dto.setRemark("标签损坏");

        var result = service.removeDetail(16L, dto, requestId);

        assertEquals(16L, result.getId());
        assertEquals(0, result.getTotalCount());
        verify(details).update(isNull(AllocationDetail.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(idempotency).markCompleted(eq("POST /allocations/{id}/remove-detail"), eq(requestId), anyString());
    }

    private static void setUser(String role, Long stationId, long userId) {
        LoginUser operator = new LoginUser();
        operator.setRole(role);
        operator.setStationId(stationId);
        operator.setUserId(userId);
        AuthContext.set(operator);
    }

    private static Station station(long id, String name) {
        Station station = new Station();
        station.setId(id);
        station.setName(name);
        return station;
    }

    private static AllocationOrder allocationOrder(long id, String status, long from, long to) {
        AllocationOrder order = new AllocationOrder();
        order.setId(id);
        order.setStatus(status);
        order.setFromStationId(from);
        order.setToStationId(to);
        order.setAllocationNo("AL" + id);
        order.setVersion(0);
        return order;
    }
}
