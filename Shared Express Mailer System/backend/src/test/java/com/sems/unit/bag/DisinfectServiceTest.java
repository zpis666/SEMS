package com.sems.unit.bag;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sems.common.Constants;
import com.sems.config.SemsProperties;
import com.sems.infra.IdempotentService;
import com.sems.module.bag.dto.CreateDisinfectDto;
import com.sems.module.bag.entity.DisinfectDetail;
import com.sems.module.bag.entity.DisinfectRecord;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.bag.mapper.DisinfectDetailMapper;
import com.sems.module.bag.mapper.DisinfectRecordMapper;
import com.sems.module.bag.service.DisinfectService;
import com.sems.module.statemachine.BagTransitionService;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.StationMapper;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DisinfectServiceTest {
    @AfterEach
    void clearAuth() { AuthContext.clear(); }

    @Test
    void invalidDetailFailsIndividuallyAndBatchSummaryUsesActualWrittenRows() {
        if (TableInfoHelper.getTableInfo(DisinfectDetail.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DisinfectDetail.class);
        }
        DisinfectRecordMapper records = mock(DisinfectRecordMapper.class);
        DisinfectDetailMapper details = mock(DisinfectDetailMapper.class);
        BagMapper bags = mock(BagMapper.class);
        StationMapper stations = mock(StationMapper.class);
        BagTransitionService transitions = mock(BagTransitionService.class);
        IdempotentService idempotency = mock(IdempotentService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(idempotency.findCompleted(anyString(), anyString())).thenReturn(null);
        Station station = new Station();
        station.setId(4L);
        when(stations.selectById(4L)).thenReturn(station);

        SemsProperties properties = new SemsProperties();
        properties.getBatch().setDisinfectMax(200);
        DisinfectService service = new DisinfectService(records, details, bags, stations, transitions,
                idempotency, properties, redis, mock(PlatformTransactionManager.class));
        LoginUser platform = new LoginUser();
        platform.setUserId(10L);
        platform.setRole(Constants.ROLE_PLATFORM_ADMIN);
        AuthContext.set(platform);
        CreateDisinfectDto.BagItem invalid = new CreateDisinfectDto.BagItem();
        invalid.setBagCode("SJB-L-2609-00001-4");
        invalid.setResult("未判定");
        CreateDisinfectDto dto = new CreateDisinfectDto();
        dto.setStationId(4L);
        dto.setMethod(" 紫外线 ");
        dto.setStartTime(LocalDateTime.parse("2026-10-07T10:00:00"));
        dto.setEndTime(LocalDateTime.parse("2026-10-07T10:30:00"));
        dto.setBags(List.of(invalid));

        var result = service.register(dto, "01234567-89ab-cdef-0123-456789abcdef");

        assertEquals(1, result.getItems().size());
        assertFalse(result.getItems().get(0).getSuccess());
        assertEquals(1, result.getTotal());
        assertEquals(0, result.getSuccess());
        assertEquals(1, result.getFailed());
        verify(records).insert(any(com.sems.module.bag.entity.DisinfectRecord.class));
        verify(records).updateById(any(com.sems.module.bag.entity.DisinfectRecord.class));
        verifyNoInteractions(bags, transitions);
        verify(details, never()).insert(any(DisinfectDetail.class));
        verify(idempotency).markCompleted(anyString(), anyString(), anyString());
    }

    @Test
    void passingBagIsWrittenAndTransitionedInsideOnePerBagTransaction() {
        initTable(DisinfectDetail.class);
        if (TableInfoHelper.getTableInfo(DisinfectRecord.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DisinfectRecord.class);
        }
        DisinfectRecordMapper records = mock(DisinfectRecordMapper.class);
        DisinfectDetailMapper details = mock(DisinfectDetailMapper.class);
        BagMapper bags = mock(BagMapper.class);
        StationMapper stations = mock(StationMapper.class);
        BagTransitionService transitions = mock(BagTransitionService.class);
        IdempotentService idempotency = mock(IdempotentService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(idempotency.findCompleted(anyString(), anyString())).thenReturn(null);
        when(records.insert(any(DisinfectRecord.class))).thenAnswer(invocation -> {
            ((DisinfectRecord) invocation.getArgument(0)).setId(55L);
            return 1;
        });
        Station station = new Station();
        station.setId(4L);
        when(stations.selectById(4L)).thenReturn(station);
        Bag bag = new Bag();
        bag.setBagCode("SJB-L-2609-00001-4");
        bag.setStationId(4L);
        bag.setStatus(com.sems.module.statemachine.BagStatus.TO_DISINFECT.code());
        when(bags.selectOne(any())).thenReturn(bag);
        DisinfectDetail persisted = new DisinfectDetail();
        persisted.setResult("合格");
        when(details.selectList(any())).thenReturn(List.of(persisted));
        when(transitions.fire(any(com.sems.module.statemachine.TransitionCommand.class)))
                .thenReturn(com.sems.module.statemachine.BagTransitionResult.builder()
                        .bagCode(bag.getBagCode()).fromStatus(com.sems.module.statemachine.BagStatus.TO_DISINFECT)
                        .toStatus(com.sems.module.statemachine.BagStatus.IDLE).build());

        SemsProperties properties = new SemsProperties();
        properties.getBatch().setDisinfectMax(200);
        DisinfectService service = new DisinfectService(records, details, bags, stations, transitions,
                idempotency, properties, redis, tx);
        LoginUser platform = new LoginUser();
        platform.setUserId(10L);
        platform.setRole(Constants.ROLE_PLATFORM_ADMIN);
        AuthContext.set(platform);
        CreateDisinfectDto.BagItem item = new CreateDisinfectDto.BagItem();
        item.setBagCode(bag.getBagCode());
        item.setResult("合格");
        CreateDisinfectDto dto = new CreateDisinfectDto();
        dto.setStationId(4L);
        dto.setMethod("紫外线");
        dto.setStartTime(LocalDateTime.parse("2026-10-07T10:00:00"));
        dto.setEndTime(LocalDateTime.parse("2026-10-07T10:30:00"));
        dto.setBags(List.of(item));

        var result = service.register(dto, "11234567-89ab-cdef-0123-456789abcdef");

        assertEquals(1, result.getSuccess());
        assertEquals(0, result.getFailed());
        assertEquals("合格", result.getResult());
        verify(details).insert(argThat((DisinfectDetail detail) -> Long.valueOf(55L).equals(detail.getDisinfectId())
                && "合格".equals(detail.getResult()) && detail.getDefectDesc() == null));
        var command = org.mockito.ArgumentCaptor.forClass(com.sems.module.statemachine.TransitionCommand.class);
        verify(transitions).fire(command.capture());
        assertEquals(com.sems.module.statemachine.BagEvent.DISINFECT_PASS, command.getValue().getEvent());
        verify(tx).commit(any());
    }

    private static void initTable(Class<?> type) {
        if (TableInfoHelper.getTableInfo(type) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
    }
}
