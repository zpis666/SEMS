package com.sems.unit.bag;

import com.sems.config.SemsProperties;
import com.sems.infra.IdempotentService;
import com.sems.module.bag.dto.CreateDisinfectDto;
import com.sems.module.bag.dto.CreateInventoryCheckDto;
import com.sems.module.bag.mapper.*;
import com.sems.module.bag.service.DisinfectService;
import com.sems.module.bag.service.InventoryCheckService;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.station.mapper.InventoryCheckMapper;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.station.service.StockQueryService;
import com.sems.module.statemachine.BagTransitionService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class BagOperationIdempotencyTest {
    private static final String REQUEST_ID = "01234567-89ab-cdef-0123-456789abcdef";
    @Test
    void inventoryMutationsRequireIdempotencyKeyBeforeDatabaseCalls() {
        InventoryCheckMapper inventory = mock(InventoryCheckMapper.class);
        BagMapper bags = mock(BagMapper.class);
        StationMapper stations = mock(StationMapper.class);
        StockQueryService stock = mock(StockQueryService.class);
        IdempotentService idempotency = mock(IdempotentService.class);
        InventoryCheckService service = new InventoryCheckService(inventory, bags, stations, stock, idempotency);

        assertThrows(RuntimeException.class, () -> service.submit(new CreateInventoryCheckDto(), null));
        assertThrows(RuntimeException.class, () -> service.voidCheck(1L, null, " "));
        verifyNoInteractions(inventory, bags, stations, stock, idempotency);
    }

    @Test
    void inventoryRejectsUnknownBagStatusKeyAndReleasesReservation() {
        InventoryCheckMapper inventory = mock(InventoryCheckMapper.class);
        IdempotentService idempotency = mock(IdempotentService.class);
        when(idempotency.findCompleted(anyString(), anyString())).thenReturn(null);
        InventoryCheckService service = new InventoryCheckService(inventory, mock(BagMapper.class),
                mock(StationMapper.class), mock(StockQueryService.class), idempotency);
        CreateInventoryCheckDto dto = new CreateInventoryCheckDto();
        dto.setActualJson(Map.of("INVALID", 1));

        assertThrows(RuntimeException.class, () -> service.submit(dto, REQUEST_ID));

        verify(idempotency).markProcessing(anyString(), eq(REQUEST_ID));
        verify(idempotency).clear(anyString(), eq(REQUEST_ID));
        verifyNoInteractions(inventory);
    }

    @Test
    void disinfectRegistrationRequiresIdempotencyKeyBeforeRedisOrDatabaseCalls() {
        DisinfectRecordMapper records = mock(DisinfectRecordMapper.class);
        DisinfectDetailMapper details = mock(DisinfectDetailMapper.class);
        BagMapper bags = mock(BagMapper.class);
        StationMapper stations = mock(StationMapper.class);
        BagTransitionService transitions = mock(BagTransitionService.class);
        IdempotentService idempotency = mock(IdempotentService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DisinfectService service = new DisinfectService(records, details, bags, stations, transitions,
                idempotency, new SemsProperties(), redis, mock(PlatformTransactionManager.class));

        assertThrows(RuntimeException.class, () -> service.register(new CreateDisinfectDto(), null));
        verifyNoInteractions(records, details, bags, stations, transitions, idempotency, redis);
    }

    @Test
    void disinfectRejectsUnsupportedMethodAndReleasesReservation() {
        DisinfectRecordMapper records = mock(DisinfectRecordMapper.class);
        IdempotentService idempotency = mock(IdempotentService.class);
        when(idempotency.findCompleted(anyString(), anyString())).thenReturn(null);
        DisinfectService service = new DisinfectService(records, mock(DisinfectDetailMapper.class),
                mock(BagMapper.class), mock(StationMapper.class), mock(BagTransitionService.class),
                idempotency, new SemsProperties(), mock(StringRedisTemplate.class),
                mock(PlatformTransactionManager.class));
        CreateDisinfectDto dto = new CreateDisinfectDto();
        dto.setMethod("蒸汽");

        assertThrows(RuntimeException.class, () -> service.register(dto, REQUEST_ID));

        verify(idempotency).markProcessing(anyString(), eq(REQUEST_ID));
        verify(idempotency).clear(anyString(), eq(REQUEST_ID));
        verifyNoInteractions(records);
    }
}
