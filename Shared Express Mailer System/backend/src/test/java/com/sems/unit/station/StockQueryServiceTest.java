package com.sems.unit.station;

import com.sems.infra.StockService;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.station.mapper.BagStockMapper;
import com.sems.module.station.service.StockQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockQueryServiceTest {
    private final BagStockMapper mapper = mock(BagStockMapper.class);
    private final StockService stock = mock(StockService.class);
    private final StockQueryService service = new StockQueryService(mapper, stock);

    @Test
    void initializesEveryKnownBagStatusAndIgnoresUnknownOrMalformedRows() {
        when(mapper.selectStatusCounts(5L)).thenReturn(List.of(
                Map.of("STATUS", BagStatus.IDLE.code(), "CNT", "7"),
                Map.of("status", "UNREGISTERED", "cnt", 100),
                Map.of("status", BagStatus.IN_USE.code(), "cnt", "not-a-number"),
                Map.of("cnt", 9)));

        Map<String, Long> result = service.countByStatus(5L);

        assertEquals(BagStatus.values().length, result.size());
        assertEquals(7L, result.get(BagStatus.IDLE.code()));
        assertEquals(0L, result.get(BagStatus.IN_USE.code()));
        assertTrue(result.values().stream().allMatch(value -> value >= 0));
    }

    @Test
    void unavailableRedisTriggersOneRebuildAndReread() {
        when(stock.getAvailable(3L)).thenReturn(null, 12);
        when(stock.isDegraded()).thenReturn(false);

        assertEquals(12, service.readAvailableWithRebuild(3L));

        verify(stock).rebuildStationStock(3L);
        verify(stock, times(2)).getAvailable(3L);
    }

    @Test
    void degradedRedisSkipsRebuildAndUsesDatabaseForDisplayFallback() {
        when(stock.getAvailable(3L)).thenReturn(null);
        when(stock.isDegraded()).thenReturn(true);
        when(mapper.countWithStatus(3L, BagStatus.IDLE.code())).thenReturn(4L);

        assertNull(service.readAvailableWithRebuild(3L));
        assertEquals(4, service.getAvailableStock(3L));

        verify(stock, never()).rebuildStationStock(any());
        verify(mapper).countWithStatus(3L, BagStatus.IDLE.code());
    }

    @Test
    void redisValueTakesPrecedenceOverDatabaseCount() {
        when(stock.getAvailable(8L)).thenReturn(9);

        assertEquals(9, service.getAvailableStock(8L));

        verifyNoInteractions(mapper);
        verify(stock, never()).rebuildStationStock(any());
    }

    @Test
    void nonTerminalCountExcludesOnlyScrappedStatus() {
        when(mapper.countNonTerminal(6L, BagStatus.SCRAPPED.code())).thenReturn(13L);

        assertEquals(13L, service.countNonTerminal(6L));

        verify(mapper).countNonTerminal(6L, BagStatus.SCRAPPED.code());
    }
}
