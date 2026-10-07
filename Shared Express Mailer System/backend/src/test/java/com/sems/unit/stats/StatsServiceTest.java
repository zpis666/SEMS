package com.sems.unit.stats;

import com.sems.common.BizException;
import com.sems.infra.CounterService;
import com.sems.infra.StockService;
import com.sems.module.audit.mapper.SysMetricMapper;
import com.sems.module.audit.service.AuditLogService;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.stats.dto.OrderTrendVo;
import com.sems.module.station.entity.Station;
import com.sems.common.Constants;
import com.sems.module.stats.mapper.StatsQueryMapper;
import com.sems.module.stats.service.StatsService;
import com.sems.module.station.mapper.StationMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatsServiceTest {
    private final StatsQueryMapper queries = mock(StatsQueryMapper.class);
    private final StationMapper stations = mock(StationMapper.class);
    private final BagOrderMapper orders = mock(BagOrderMapper.class);
    private final CounterService counters = mock(CounterService.class);
    private final StockService stock = mock(StockService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final SysMetricMapper metrics = mock(SysMetricMapper.class);
    private final StatsService service = new StatsService(queries, stations, orders, counters, stock, audit, metrics);

    @Test
    void orderTrendFillsMissingDaysWithZeroAndSumsDuplicateAggregateRows() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        when(orders.countBorrowByDay(any(), any())).thenReturn(List.of(
                Map.of("statDate", yesterday.toString(), "cnt", "2"),
                Map.of("statDate", yesterday.toString(), "cnt", 3),
                Map.of("statDate", "not-a-date", "cnt", 90)));
        when(orders.countReturnByDay(any(), any())).thenReturn(List.of(
                Map.of("statDate", today, "cnt", 4L),
                Map.of("statDate", "bad-date", "cnt", 100)));

        List<OrderTrendVo> trend = service.orderTrend(3);

        assertEquals(3, trend.size());
        assertEquals(today.minusDays(2), trend.get(0).getDate());
        assertEquals(0L, trend.get(0).getBorrowCount());
        assertEquals(5L, trend.get(1).getBorrowCount());
        assertEquals(0L, trend.get(1).getReturnCount());
        assertEquals(4L, trend.get(2).getReturnCount());
    }

    @Test
    void orderTrendUsesSevenDayDefaultAndRejectsOutOfRangePeriod() {
        when(orders.countBorrowByDay(any(), any())).thenReturn(List.of());
        when(orders.countReturnByDay(any(), any())).thenReturn(List.of());

        assertEquals(7, service.orderTrend(null).size());
        BizException ex = assertThrows(BizException.class, () -> service.orderTrend(91));
        assertEquals(3001, ex.getErrorCode().getCode());
        verify(orders, times(1)).countBorrowByDay(any(), any());
    }

    @Test
    void redisReturnCounterIsBestEffortAndSkipsNullOrDegradedStation() {
        service.incrementTodayReturn(null);
        when(stock.isDegraded()).thenReturn(true);
        service.incrementTodayReturn(4L);
        verifyNoInteractions(counters);

        when(stock.isDegraded()).thenReturn(false);
        doThrow(new IllegalStateException("redis down")).when(counters).statsTodayIncrement(4L, "return");
        assertDoesNotThrow(() -> service.incrementTodayReturn(4L));
        verify(counters).statsTodayIncrement(4L, "return");
    }

    @Test
    void stockDistributionContainsEveryStatusAndKeepsUnexpectedDatabaseCodesVisible() {
        when(queries.countBagByStatus()).thenReturn(List.of(
                Map.of("status", "IDLE", "cnt", 8),
                Map.of("status", "UNEXPECTED", "cnt", "2"),
                Map.of("status", "IN_USE", "cnt", "bad")));
        when(queries.countInCirculationByStation()).thenReturn(List.of(
                Map.of("stationId", 9, "cnt", 6L)));
        Station station = new Station();
        station.setId(9L);
        station.setName("白云站");
        when(stations.selectList(any())).thenReturn(List.of(station));

        var result = service.stockDistribution();

        assertEquals(8L, result.getStatusDistribution().get("IDLE"));
        assertEquals(0L, result.getStatusDistribution().get("IN_USE"));
        assertEquals(2L, result.getStatusDistribution().get("UNEXPECTED"));
        assertEquals(1, result.getByStation().size());
        assertEquals(6L, result.getByStation().get(0).getCount());
    }

    @Test
    void turnoverUsesZeroForEmptyCirculationAndRoundsNonzeroRate() {
        when(orders.countFinishedByStation(any(), any())).thenReturn(List.of(
                Map.of("stationId", 1, "cnt", 3), Map.of("stationId", 2, "cnt", 4)));
        when(queries.countInCirculationByStation()).thenReturn(List.of(
                Map.of("stationId", 1, "cnt", 0), Map.of("stationId", 2, "cnt", 6)));
        Station empty = new Station();
        empty.setId(1L);
        Station populated = new Station();
        populated.setId(2L);
        when(stations.selectList(any())).thenReturn(List.of(empty, populated));

        var result = service.bagTurnover(30);

        assertEquals(2, result.size());
        assertEquals(0.0, result.get(0).getTurnoverRate());
        assertEquals(0.6667, result.get(1).getTurnoverRate());
    }

    @Test
    void dashboardDerivesLifecycleTotalsAndFallsBackToStationDailyCountersAndDatabaseStock() {
        when(queries.countBagByStatus()).thenReturn(List.of(
                Map.of("status", "IDLE", "cnt", 10),
                Map.of("status", "ALLOCATED", "cnt", 2),
                Map.of("status", "IN_USE", "cnt", 3),
                Map.of("status", "TO_DISINFECT", "cnt", 1),
                Map.of("status", "LOST", "cnt", 2),
                Map.of("status", "SCRAPPED", "cnt", 1)));
        when(counters.getStatsToday(0L)).thenReturn(Map.of("borrow", 99L));
        when(counters.getStatsToday(4L)).thenReturn(Map.of("borrow", 2L, "return", 3L));
        when(queries.countIdleByStation()).thenReturn(List.of(Map.of("stationId", 4, "cnt", 2)));
        Station active = new Station();
        active.setId(4L);
        active.setName("海珠站");
        active.setStatus(Constants.STATUS_ENABLED);
        active.setWarnThreshold(3);
        when(stations.selectList(any())).thenReturn(List.of(active));
        when(stock.getAvailable(4L)).thenReturn(null);
        when(audit.getAuditDropCount()).thenReturn(5L);
        when(metrics.selectOne(any())).thenReturn(null);

        var result = service.dashboard();

        assertEquals(6L, result.getInUseCount());
        assertEquals(16L, result.getInCirculationCount());
        assertEquals(99L, result.getTodayBorrow());
        assertEquals(3L, result.getTodayReturn());
        assertEquals(1, result.getWarningStationCount());
        assertEquals(5L, result.getAuditDropCount());
        assertEquals(0L, result.getReconcileDiffCount());
    }
}
