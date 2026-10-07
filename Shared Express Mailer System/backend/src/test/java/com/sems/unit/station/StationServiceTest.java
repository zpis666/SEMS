package com.sems.unit.station;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.module.station.dto.NearbyStationVo;
import com.sems.module.station.dto.CreateStationDto;
import com.sems.module.station.dto.StationQuery;
import com.sems.module.station.dto.StationStatusDto;
import com.sems.module.station.dto.UpdateStationDto;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.station.service.StationService;
import com.sems.module.station.service.StockQueryService;
import com.sems.infra.IdempotentService;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StationServiceTest {
    private final StationMapper mapper = mock(StationMapper.class);
    private final StockQueryService stock = mock(StockQueryService.class);
    private final IdempotentService idempotency = mock(IdempotentService.class);
    private final StationService service = new StationService(mapper, stock, idempotency);

    @AfterEach
    void clear() { AuthContext.clear(); }

    @Test
    void stationAdminCanAccessOnlyBoundStationAndPlatformAdminCanAccessAll() {
        LoginUser operator = new LoginUser();
        operator.setRole(Constants.ROLE_STATION_ADMIN);
        operator.setStationId(12L);
        AuthContext.set(operator);
        service.assertStationAccess(12L);
        BizException denied = assertThrows(BizException.class, () -> service.assertStationAccess(13L));
        assertSame(ErrorCode.NO_DATA_PERMISSION, denied.getErrorCode());

        operator.setRole(Constants.ROLE_PLATFORM_ADMIN);
        operator.setStationId(null);
        assertDoesNotThrow(() -> service.assertStationAccess(999L));
    }

    @Test
    void nullAndMissingStationAreReportedAsInvalidResource() {
        when(mapper.selectById(8L)).thenReturn(null);
        assertSame(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.requireStation(8L)).getErrorCode());
        assertSame(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.requireStation(null)).getErrorCode());
        verify(mapper, times(1)).selectById(8L);
    }

    @Test
    void nearbyOnlyReturnsEnabledRowsAndAddsAuthoritativeAvailableStock() {
        Station station = new Station();
        station.setId(4L);
        station.setName("天河站");
        station.setAddress("广州");
        station.setStatus(Constants.STATUS_ENABLED);
        station.setWarnThreshold(2);
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(station));
        when(stock.getAvailableStock(4L)).thenReturn(6);

        List<NearbyStationVo> nearby = service.nearby();

        assertEquals(1, nearby.size());
        assertEquals("天河站", nearby.get(0).getName());
        assertEquals(6, nearby.get(0).getAvailableStock());
        verify(stock).getAvailableStock(4L);
    }

    @Test
    void pageRejectsUnsafeStatusAndSortFieldsBeforeQuerying() {
        StationQuery badStatus = new StationQuery();
        badStatus.setStatus("ENABLED' OR 1=1");
        assertSame(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.page(badStatus)).getErrorCode());

        StationQuery badSort = new StationQuery();
        badSort.setOrderBy("name; DROP TABLE station");
        assertSame(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.page(badSort)).getErrorCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void emptyPageUsesSafeDefaultsAndReturnsEmptyRecords() {
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(invocation -> new Page<Station>(1, 10));

        var result = service.page(null);

        assertTrue(result.getRecords().isEmpty());
        assertEquals(0L, result.getTotal());
        assertEquals(0L, result.getPages());
        verify(mapper).selectPage(argThat(page -> page.getCurrent() == 1 && page.getSize() == 10),
                any(LambdaQueryWrapper.class));
    }

    @Test
    void enablingStationIsIdempotentAndUpdatesStatusWithoutDisablePreconditions() {
        LoginUser operator = new LoginUser();
        operator.setUserId(2L);
        operator.setRole(Constants.ROLE_PLATFORM_ADMIN);
        AuthContext.set(operator);
        Station station = new Station();
        station.setId(6L);
        station.setName("越秀站");
        station.setAddress("广州");
        station.setStatus(Constants.STATUS_DISABLED);
        when(idempotency.findCompleted("PATCH /stations/{id}/status", "req")).thenReturn(null);
        when(mapper.selectById(6L)).thenReturn(station);
        when(mapper.updateById(station)).thenReturn(1);
        StationStatusDto dto = new StationStatusDto();
        dto.setStatus(" enabled ");

        var result = service.updateStatus(6L, dto, "req");

        assertEquals(Constants.STATUS_ENABLED, result.getStatus());
        assertEquals("启用", result.getStatusName());
        assertEquals(Constants.STATUS_ENABLED, station.getStatus());
        verify(mapper).updateById(station);
        verify(idempotency).markProcessing("PATCH /stations/{id}/status", "req");
        verify(idempotency).markCompleted(eq("PATCH /stations/{id}/status"), eq("req"), anyString());
        verify(idempotency, never()).clear(anyString(), anyString());
    }

    @Test
    void createTrimsFieldsAndConvertsBlankOpenTimeToNull() {
        LoginUser operator = new LoginUser();
        operator.setUserId(2L);
        operator.setRole(Constants.ROLE_PLATFORM_ADMIN);
        AuthContext.set(operator);
        when(idempotency.findCompleted("POST /stations", "create-1")).thenReturn(null);
        when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(mapper.insert(any(Station.class))).thenAnswer(invocation -> {
            ((Station) invocation.getArgument(0)).setId(55L);
            return 1;
        });
        CreateStationDto dto = new CreateStationDto();
        dto.setName("  海珠站 ");
        dto.setAddress(" 广州大道 ");
        dto.setOpenTime("   ");

        var result = service.create(dto, "create-1");

        assertEquals(55L, result.getId());
        assertEquals("海珠站", result.getName());
        assertEquals("广州大道", result.getAddress());
        assertNull(result.getOpenTime());
        assertEquals(Constants.STATUS_ENABLED, result.getStatus());
        verify(idempotency).markCompleted(eq("POST /stations"), eq("create-1"), anyString());
    }

    @Test
    void updateFailureClearsRequestPlaceholderSoClientCanRetry() {
        when(idempotency.findCompleted("PUT /stations/{id}", "update-1")).thenReturn(null);
        Station station = new Station();
        station.setId(5L);
        station.setName("旧站点");
        station.setAddress("旧地址");
        when(mapper.selectById(5L)).thenReturn(station);
        when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        UpdateStationDto dto = new UpdateStationDto();
        dto.setName(" 重名 ");
        dto.setAddress("新地址");

        assertSame(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.update(5L, dto, "update-1")).getErrorCode());

        verify(idempotency).markProcessing("PUT /stations/{id}", "update-1");
        verify(idempotency).clear("PUT /stations/{id}", "update-1");
        verify(mapper, never()).updateById(any(Station.class));
    }

    @Test
    void cannotDisableStationWithNonTerminalBagsWithoutHandover() {
        LoginUser operator = new LoginUser();
        operator.setUserId(2L);
        operator.setRole(Constants.ROLE_PLATFORM_ADMIN);
        AuthContext.set(operator);
        when(idempotency.findCompleted("PATCH /stations/{id}/status", "disable-1")).thenReturn(null);
        Station station = new Station();
        station.setId(6L);
        station.setName("番禺站");
        station.setAddress("广州");
        station.setStatus(Constants.STATUS_ENABLED);
        when(mapper.selectById(6L)).thenReturn(station);
        when(stock.getAvailableStock(6L)).thenReturn(0);
        when(stock.countNonTerminal(6L)).thenReturn(2L);
        StationStatusDto dto = new StationStatusDto();
        dto.setStatus(Constants.STATUS_DISABLED);

        assertSame(ErrorCode.ORDER_STATE_NOT_ALLOWED,
                assertThrows(BizException.class,
                        () -> service.updateStatus(6L, dto, "disable-1")).getErrorCode());

        verify(idempotency).clear("PATCH /stations/{id}/status", "disable-1");
        verify(mapper, never()).updateById(any(Station.class));
    }
}
