package com.sems.unit.stats;

import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.config.SemsProperties;
import com.sems.module.audit.mapper.SysMetricMapper;
import com.sems.module.audit.service.AuditLogService;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.stats.dto.AlertQuery;
import com.sems.module.stats.mapper.StatsQueryMapper;
import com.sems.module.stats.service.AlertService;
import com.sems.module.station.mapper.AllocationOrderMapper;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.station.entity.Station;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlertServiceTest {
    private final StatsQueryMapper stats = mock(StatsQueryMapper.class);
    private final BagMapper bags = mock(BagMapper.class);
    private final BagOrderMapper orders = mock(BagOrderMapper.class);
    private final AllocationOrderMapper allocations = mock(AllocationOrderMapper.class);
    private final StationMapper stations = mock(StationMapper.class);
    private final SysMetricMapper metrics = mock(SysMetricMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SemsProperties properties = new SemsProperties();
    private final AlertService service = new AlertService(stats, bags, orders, allocations,
            stations, metrics, audit, redis, properties);

    @Test
    void auditDropAlertIsProducedOnlyForPositiveDropCount() {
        when(stations.selectList(any())).thenReturn(List.of());
        when(audit.getAuditDropCount()).thenReturn(3L);
        AlertQuery query = new AlertQuery();
        query.setType(AlertService.TYPE_AUDIT_DROP);

        var result = service.pageAlerts(query);

        assertEquals(1L, result.getTotal());
        assertEquals(AlertService.TYPE_AUDIT_DROP, result.getRecords().get(0).getType());
        assertEquals("HIGH", result.getRecords().get(0).getLevel());
        assertTrue(result.getRecords().get(0).getTitle().contains("3 条事件"));
        verify(audit).getAuditDropCount();
        verifyNoInteractions(stats, bags, orders, allocations, metrics, redis);
    }

    @Test
    void invalidTypeFailsClosedBeforeScanningSources() {
        AlertQuery query = new AlertQuery();
        query.setType("ALL; DROP TABLE audit_log");

        BizException ex = assertThrows(BizException.class, () -> service.pageAlerts(query));

        assertSame(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        verifyNoInteractions(stations, stats, bags, orders, allocations, metrics, audit, redis);
    }

    @Test
    void highFrequencyConfirmationAlertResolvesStationAndOperatorFromRedisKey() {
        Station station = new Station();
        station.setId(4L);
        station.setName("番禺站");
        when(stations.selectList(any())).thenReturn(List.of(station));
        when(redis.keys("risk:confirm:*" )).thenReturn(Set.of("risk:confirm:4:12"));
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("risk:confirm:4:12")).thenReturn("24");
        AlertQuery query = new AlertQuery();
        query.setType(AlertService.TYPE_RISK_CONFIRM_FREQUENCY);

        var result = service.pageAlerts(query);

        assertEquals(1, result.getTotal());
        var alert = result.getRecords().get(0);
        assertEquals(AlertService.TYPE_RISK_CONFIRM_FREQUENCY, alert.getType());
        assertEquals(4L, alert.getStationId());
        assertEquals("番禺站", alert.getStationName());
        assertEquals("12", alert.getTargetNo());
        assertTrue(alert.getTitle().contains("24 次"));
        assertEquals("/platform/audit-logs?operatorId=12", alert.getActionUrl());
    }
}
