package com.sems.unit.bag;

import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.config.SemsProperties;
import com.sems.infra.*;
import com.sems.module.audit.service.AuditLogService;
import com.sems.module.bag.dto.BagQuery;
import com.sems.module.bag.dto.ActivateDto;
import com.sems.module.bag.dto.StockInDto;
import com.sems.module.bag.dto.RepairDto;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.*;
import com.sems.module.bag.service.*;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.station.mapper.*;
import com.sems.module.statemachine.BagTransitionService;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.statemachine.BagTransitionResult;
import com.sems.module.statemachine.TransitionCommand;
import com.sems.module.station.entity.Station;
import com.sems.module.user.mapper.SysUserMapper;
import com.sems.push.PushService;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class BagServiceAccessTest {
    private BagService service;
    private BagMapper bags;
    private AllocationDetailMapper allocationDetails;
    private AllocationOrderMapper allocationOrders;
    private BagTransitionService transitions;
    private IdempotentService idempotency;
    private StationMapper stations;

    @BeforeEach
    void setup() {
        bags = mock(BagMapper.class);
        allocationDetails = mock(AllocationDetailMapper.class);
        allocationOrders = mock(AllocationOrderMapper.class);
        transitions = mock(BagTransitionService.class);
        idempotency = mock(IdempotentService.class);
        stations = mock(StationMapper.class);
        service = new BagService(bags, mock(BagTransferLogMapper.class),
                mock(StockRecordMapper.class), mock(BagOrderMapper.class), stations,
                allocationDetails, allocationOrders, mock(SysUserMapper.class),
                transitions, idempotency, mock(RateLimitService.class),
                mock(AuditLogService.class), new SemsProperties(), mock(BagCodeGenerator.class),
                mock(QrCodeService.class), mock(StockService.class), mock(PushService.class));
        AuthContext.clear();
    }

    @AfterEach
    void clearAuth() { AuthContext.clear(); }

    @Test
    void pageRequiresAuthenticatedIdentity() {
        assertThrows(BizException.class, () -> service.page(new BagQuery()));
    }

    @Test
    void stationAdministratorWithoutStationCannotListLedger() {
        LoginUser user = new LoginUser();
        user.setUserId(5L);
        user.setRole(Constants.ROLE_STATION_ADMIN);
        AuthContext.set(user);
        assertThrows(BizException.class, () -> service.page(new BagQuery()));
    }

    @Test
    void stationAdministratorCannotRequestAnotherStationsLedger() {
        LoginUser user = new LoginUser();
        user.setUserId(5L);
        user.setRole(Constants.ROLE_STATION_ADMIN);
        user.setStationId(11L);
        AuthContext.set(user);
        BagQuery query = new BagQuery();
        query.setStationId(12L);
        assertThrows(BizException.class, () -> service.page(query));
    }

    @Test
    void deprecatedActivationAndManualStockInAreRejected() {
        assertThrows(BizException.class, () -> service.activate("B-1", new ActivateDto(), "request-1"));
        assertThrows(BizException.class, () -> service.stockIn(new StockInDto(), "request-2"));
    }

    @Test
    void repairChecksAllocationLockAndFiresRepairTransitionForUnlockedBag() {
        LoginUser platform = new LoginUser();
        platform.setUserId(1L);
        platform.setRole(Constants.ROLE_PLATFORM_ADMIN);
        AuthContext.set(platform);
        Bag bag = new Bag();
        bag.setId(3L);
        bag.setBagCode("B-1");
        bag.setStatus(BagStatus.IDLE.code());
        bag.setStationId(4L);
        bag.setUseCount(1);
        bag.setMaxUseCount(50);
        bag.setVersion(0);
        when(bags.selectOne(any())).thenReturn(bag);
        when(allocationDetails.selectList(any())).thenReturn(java.util.List.of());
        when(transitions.fire(any(TransitionCommand.class))).thenReturn(BagTransitionResult.builder()
                .bagCode("B-1").fromStatus(BagStatus.IDLE).toStatus(BagStatus.REPAIRING).build());
        RepairDto dto = new RepairDto();
        dto.setDescription("拉链损坏");

        var response = service.repair("B-1", dto, java.util.UUID.randomUUID().toString());

        assertEquals("B-1", response.getBagCode());
        var captor = org.mockito.ArgumentCaptor.forClass(TransitionCommand.class);
        verify(transitions).fire(captor.capture());
        assertEquals(BagEvent.REPAIR_START, captor.getValue().getEvent());
        assertEquals("拉链损坏", captor.getValue().getRemark());
        verify(allocationDetails).selectList(any());
        verify(idempotency).markCompleted(anyString(), anyString(), anyString());
    }
}
