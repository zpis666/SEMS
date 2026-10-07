package com.sems.unit.bag;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.common.Constants;
import com.sems.infra.IdempotentService;
import com.sems.module.bag.dto.CreateInventoryCheckDto;
import com.sems.module.bag.dto.InventoryCheckQuery;
import com.sems.module.bag.dto.VoidInventoryCheckDto;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.bag.service.InventoryCheckService;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.station.entity.InventoryCheck;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.InventoryCheckMapper;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.station.service.StockQueryService;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InventoryCheckServiceWorkflowTest {
    private InventoryCheckMapper checks;
    private BagMapper bags;
    private StationMapper stations;
    private StockQueryService stockQuery;
    private IdempotentService idempotency;
    private InventoryCheckService service;

    @BeforeEach
    void setup() {
        initTable(InventoryCheck.class);
        initTable(Bag.class);
        checks = mock(InventoryCheckMapper.class);
        bags = mock(BagMapper.class);
        stations = mock(StationMapper.class);
        stockQuery = mock(StockQueryService.class);
        idempotency = mock(IdempotentService.class);
        service = new InventoryCheckService(checks, bags, stations, stockQuery, idempotency);
        when(idempotency.findCompleted(anyString(), anyString())).thenReturn(null);
        Station station = new Station();
        station.setId(7L);
        station.setName("越秀站");
        when(stations.selectById(7L)).thenReturn(station);
        AuthContext.clear();
    }

    @AfterEach
    void clearAuth() { AuthContext.clear(); }

    @Test
    void balancedPhysicalCountClosesCheckWithoutBagCandidates() {
        AuthContext.set(platform());
        when(stockQuery.countByStatus(7L)).thenReturn(zeroCounts());
        CreateInventoryCheckDto dto = new CreateInventoryCheckDto();
        dto.setStationId(7L);
        dto.setActualJson(Map.of(BagStatus.IDLE.code(), 0));
        dto.setRemark("全面盘点");

        var result = service.submit(dto, requestId());

        assertEquals("CLOSED", result.getStatus());
        assertEquals(0, result.getDiffCount());
        assertEquals(BagStatus.values().length, result.getActualJson().size());
        verify(checks).insert(argThat((InventoryCheck check) -> "CLOSED".equals(check.getStatus())
                && check.getClosedAt() != null && check.getDiffCount() == 0));
        verifyNoInteractions(bags);
        verify(idempotency).markCompleted(anyString(), eq(requestId()), anyString());
    }

    @Test
    void unbalancedCountProducesBagLevelGainCandidatesAndPendingStatus() {
        AuthContext.set(platform());
        when(stockQuery.countByStatus(7L)).thenReturn(zeroCounts());
        Bag candidate = new Bag();
        candidate.setBagCode("SJB-L-2610-00002-5");
        when(bags.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<Bag> page = invocation.getArgument(0);
            page.setRecords(List.of(candidate));
            return page;
        });
        CreateInventoryCheckDto dto = new CreateInventoryCheckDto();
        dto.setStationId(7L);
        dto.setActualJson(Map.of(BagStatus.IDLE.code(), 1));

        var result = service.submit(dto, requestId());

        assertEquals("PENDING_FIX", result.getStatus());
        assertEquals(1, result.getDiffCount());
        assertEquals("盘盈", result.getDiffBags().get(0).getDiffType());
        assertEquals("SJB-L-2610-00002-5", result.getDiffBags().get(0).getBagCode());
        verify(bags).selectPage(any(Page.class), any());
        verify(checks).insert(argThat((InventoryCheck check) -> "PENDING_FIX".equals(check.getStatus())
                && check.getClosedAt() == null));
    }

    @Test
    void voidPendingCheckPersistsReasonOperatorAndVoidTimestamp() {
        AuthContext.set(platform());
        InventoryCheck check = new InventoryCheck();
        check.setId(91L);
        check.setCheckNo("IC-91");
        check.setStationId(7L);
        check.setStatus("PENDING_FIX");
        check.setDiffCount(2);
        check.setDiffBagsJson("[]");
        when(checks.selectById(91L)).thenReturn(check);
        VoidInventoryCheckDto dto = new VoidInventoryCheckDto();
        dto.setVoidReason("重新盘点后替换本单");

        var result = service.voidCheck(91L, dto, requestId());

        assertEquals("VOID", result.getStatus());
        assertEquals("重新盘点后替换本单", result.getVoidReason());
        assertEquals(91L, result.getId());
        verify(checks).updateById(argThat((InventoryCheck update) -> "VOID".equals(update.getStatus())
                && "重新盘点后替换本单".equals(update.getVoidReason())
                && Long.valueOf(20L).equals(update.getVoidedBy()) && update.getVoidedAt() != null));
        verify(idempotency).markCompleted(anyString(), eq(requestId()), anyString());
    }

    @Test
    void pageEnforcesStatusWhitelistAndStationScope() {
        LoginUser stationAdmin = new LoginUser();
        stationAdmin.setUserId(20L);
        stationAdmin.setRole(Constants.ROLE_STATION_ADMIN);
        stationAdmin.setStationId(7L);
        AuthContext.set(stationAdmin);
        InventoryCheckQuery invalidStatus = new InventoryCheckQuery();
        invalidStatus.setStatus("BAD");
        assertThrows(RuntimeException.class, () -> service.page(invalidStatus));
        InventoryCheckQuery foreignStation = new InventoryCheckQuery();
        foreignStation.setStationId(8L);
        assertThrows(RuntimeException.class, () -> service.page(foreignStation));
        verifyNoInteractions(checks);
    }

    @Test
    void platformPageReturnsParsedSnapshotAndDiffCandidate() {
        AuthContext.set(platform());
        InventoryCheck check = new InventoryCheck();
        check.setId(92L);
        check.setCheckNo("IC-92");
        check.setStationId(7L);
        check.setStatus("PENDING_FIX");
        check.setDiffCount(1);
        check.setExpectJson("{\"IDLE\":0}");
        check.setActualJson("{\"IDLE\":1}");
        check.setDiffBagsJson("[{\"bagCode\":\"B-1\",\"diffType\":\"盘盈\"}]");
        when(checks.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<InventoryCheck> page = invocation.getArgument(0);
            page.setRecords(List.of(check));
            page.setTotal(1);
            return page;
        });
        InventoryCheckQuery query = new InventoryCheckQuery();
        query.setStatus("PENDING_FIX");

        var result = service.page(query);

        assertEquals(1, result.getTotal());
        assertEquals("IC-92", result.getRecords().get(0).getCheckNo());
        assertEquals(1, result.getRecords().get(0).getActualJson().get("IDLE"));
        assertEquals("盘盈", result.getRecords().get(0).getDiffBags().get(0).getDiffType());
    }

    private static Map<String, Long> zeroCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (BagStatus status : BagStatus.values()) counts.put(status.code(), 0L);
        return counts;
    }

    private static LoginUser platform() {
        LoginUser user = new LoginUser();
        user.setUserId(20L);
        user.setRole(Constants.ROLE_PLATFORM_ADMIN);
        return user;
    }

    private static String requestId() { return "01234567-89ab-cdef-0123-456789abcdef"; }

    private static void initTable(Class<?> type) {
        if (TableInfoHelper.getTableInfo(type) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
    }
}
