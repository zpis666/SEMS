package com.sems.unit.bag;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.config.SemsProperties;
import com.sems.infra.*;
import com.sems.module.audit.service.AuditLogService;
import com.sems.module.bag.dto.BatchGenerateDto;
import com.sems.module.bag.dto.CreateBagDto;
import com.sems.module.bag.dto.BagQuery;
import com.sems.module.bag.dto.BagInfoVo;
import com.sems.module.bag.dto.RepairCompleteDto;
import com.sems.module.bag.dto.StatusCorrectDto;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.entity.BagTransferLog;
import com.sems.module.bag.entity.StockRecord;
import com.sems.module.bag.mapper.*;
import com.sems.module.bag.service.BagCodeGenerator;
import com.sems.module.bag.service.BagService;
import com.sems.module.bag.service.QrCodeService;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.*;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagTransitionService;
import com.sems.module.statemachine.BagTransitionResult;
import com.sems.module.statemachine.TransitionCommand;
import com.sems.module.user.mapper.SysUserMapper;
import com.sems.push.PushService;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import com.sems.common.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Collection;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BagServiceCreationTest {
    private BagMapper bags;
    private BagTransferLogMapper transferLogs;
    private StockRecordMapper stockRecords;
    private StationMapper stations;
    private IdempotentService idempotency;
    private BagCodeGenerator codes;
    private QrCodeService qr;
    private PushService push;
    private BagService service;
    private Station station;
    private AllocationDetailMapper allocationDetails;
    private BagTransitionService transitions;
    private BagOrderMapper orderMapper;
    private SysUserMapper users;

    @BeforeEach
    void setup() {
        initTable(Bag.class);
        initTable(BagTransferLog.class);
        initTable(BagOrder.class);
        initTable(com.sems.module.station.entity.AllocationDetail.class);
        bags = mock(BagMapper.class);
        transferLogs = mock(BagTransferLogMapper.class);
        stockRecords = mock(StockRecordMapper.class);
        stations = mock(StationMapper.class);
        idempotency = mock(IdempotentService.class);
        codes = mock(BagCodeGenerator.class);
        qr = mock(QrCodeService.class);
        allocationDetails = mock(AllocationDetailMapper.class);
        transitions = mock(BagTransitionService.class);
        orderMapper = mock(BagOrderMapper.class);
        users = mock(SysUserMapper.class);
        push = mock(PushService.class);
        SemsProperties properties = new SemsProperties();
        properties.getBatch().setMaxSize(3);
        service = new BagService(bags, transferLogs, stockRecords, orderMapper, stations,
                allocationDetails, mock(AllocationOrderMapper.class), users,
                transitions, idempotency, mock(RateLimitService.class),
                mock(AuditLogService.class), properties, codes, qr, mock(StockService.class), push);
        station = new Station();
        station.setId(7L);
        station.setName("越秀站");
        station.setStatus(Constants.STATUS_ENABLED);
        when(stations.selectById(7L)).thenReturn(station);
        when(idempotency.findCompleted(anyString(), anyString())).thenReturn(null);
        LoginUser operator = new LoginUser();
        operator.setUserId(20L);
        operator.setRole(Constants.ROLE_PLATFORM_ADMIN);
        AuthContext.set(operator);
    }

    @AfterEach
    void clearAuth() { AuthContext.clear(); }

    @Test
    void createStartsIdleWithReuseLimitAndWritesBothLedgerRecords() {
        when(codes.next("L")).thenReturn("SJB-L-2610-00001-4");
        doAnswer(invocation -> {
            ((Bag) invocation.getArgument(0)).setId(31L);
            return 1;
        }).when(bags).insert(any(Bag.class));
        Bag saved = bag("SJB-L-2610-00001-4");
        saved.setId(31L);
        when(bags.selectById(31L)).thenReturn(saved);
        CreateBagDto dto = new CreateBagDto();
        dto.setSpec("L");
        dto.setColor("黄色");
        dto.setCapacity("33×25×5cm");
        dto.setStationId(7L);

        var result = service.create(dto, "01234567-89ab-cdef-0123-456789abcdef");

        assertEquals(BagStatus.IDLE.code(), result.getStatus());
        assertEquals(50, result.getMaxUseCount());
        assertEquals("越秀站", result.getStationName());
        verify(stockRecords).insert(argThat((StockRecord record) -> "IN".equals(record.getType())
                && "建档入库".equals(record.getRemark()) && "SJB-L-2610-00001-4".equals(record.getBagCode())));
        verify(transferLogs).insert(argThat((BagTransferLog log) -> "CREATE".equals(log.getEvent())
                && BagStatus.IDLE.code().equals(log.getFromStatus())
                && BagStatus.IDLE.code().equals(log.getToStatus())));
        verify(push).afterCommit(any(Runnable.class));
        verify(idempotency).markCompleted(anyString(), anyString(), anyString());
    }

    @Test
    void batchGenerateReturnsZipManifestAndOneQrPerNewBag() throws Exception {
        when(codes.nextBatch("M", 2)).thenReturn(List.of("BAG-1", "BAG-2"));
        when(qr.generatePng(anyString())).thenReturn(new byte[]{1, 2, 3});
        BatchGenerateDto dto = new BatchGenerateDto();
        dto.setCount(2);
        dto.setSpec("M");
        dto.setColor("蓝色");
        dto.setCapacity("30×22×4cm");
        dto.setStationId(7L);

        byte[] zip = service.batchGenerate(dto, "01234567-89ab-cdef-0123-456789abcdef");

        assertTrue(zip.length > 0);
        StringBuilder manifest = new StringBuilder();
        int pngEntries = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.getName().equals("manifest.txt")) {
                    manifest.append(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                } else if (entry.getName().endsWith(".png")) {
                    pngEntries++;
                    assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
                }
            }
        }
        assertTrue(manifest.toString().contains("BAG-1"));
        assertTrue(manifest.toString().contains("BAG-2"));
        assertEquals(2, pngEntries);
        verify(bags, times(2)).insert(any(Bag.class));
        verify(stockRecords, times(2)).insert(any(StockRecord.class));
        verify(transferLogs, times(2)).insert(any(BagTransferLog.class));
        verify(qr, times(2)).generatePng(anyString());
        verify(push).afterCommit(any(Runnable.class));
        verify(idempotency).markCompleted(anyString(), anyString(), anyString());
    }

    @Test
    void statusCorrectionSendsTargetAndReasonWithoutManualCheckNumber() {
        when(bags.selectOne(any())).thenReturn(bag("SJB-L-2610-00003-6"));
        when(allocationDetails.selectList(any())).thenReturn(List.of());
        when(transitions.fire(any(TransitionCommand.class))).thenReturn(BagTransitionResult.builder()
                .bagCode("SJB-L-2610-00003-6").fromStatus(BagStatus.IDLE).toStatus(BagStatus.REPAIRING).build());
        StatusCorrectDto dto = new StatusCorrectDto();
        dto.setTargetStatus(BagStatus.REPAIRING.code());
        dto.setRemark("现场确认拉链损坏");

        var result = service.statusCorrect("SJB-L-2610-00003-6", dto, "01234567-89ab-cdef-0123-456789abcdef");

        assertEquals(BagStatus.IDLE.code(), result.getStatus());
        var command = org.mockito.ArgumentCaptor.forClass(TransitionCommand.class);
        verify(transitions).fire(command.capture());
        assertEquals(BagEvent.STATUS_CORRECT, command.getValue().getEvent());
        assertEquals(BagStatus.REPAIRING, command.getValue().getTargetStatus());
        assertEquals("现场确认拉链损坏", command.getValue().getRemark());
        verify(idempotency).markCompleted(anyString(), anyString(), anyString());
    }

    @Test
    void successfulRepairCompletionReturnsToDisinfectionQueue() {
        Bag bag = bag("SJB-L-2610-00004-7");
        bag.setStatus(BagStatus.REPAIRING.code());
        when(bags.selectOne(any())).thenReturn(bag);
        when(transitions.fire(any(TransitionCommand.class))).thenReturn(BagTransitionResult.builder()
                .bagCode(bag.getBagCode()).fromStatus(BagStatus.REPAIRING).toStatus(BagStatus.TO_DISINFECT).build());
        RepairCompleteDto dto = new RepairCompleteDto();
        dto.setResult("修复");
        dto.setRemark("更换拉链头");

        service.repairComplete(bag.getBagCode(), dto, "01234567-89ab-cdef-0123-456789abcdef");

        var command = org.mockito.ArgumentCaptor.forClass(TransitionCommand.class);
        verify(transitions).fire(command.capture());
        assertEquals(BagEvent.REPAIR_DONE, command.getValue().getEvent());
        assertEquals("修复", command.getValue().getAttributes().get("repairResult"));
        verify(idempotency).markCompleted(anyString(), anyString(), anyString());
    }

    @Test
    void repairedBagAtReuseLimitIsScrappedAndAudited() {
        Bag bag = bag("SJB-L-2610-00005-8");
        bag.setStatus(BagStatus.REPAIRING.code());
        bag.setUseCount(50);
        bag.setMaxUseCount(50);
        when(bags.selectOne(any())).thenReturn(bag);
        when(transitions.fire(any(TransitionCommand.class))).thenReturn(BagTransitionResult.builder()
                .bagCode(bag.getBagCode()).fromStatus(BagStatus.REPAIRING).toStatus(BagStatus.SCRAPPED).build());
        RepairCompleteDto dto = new RepairCompleteDto();
        dto.setResult("修复");

        service.repairComplete(bag.getBagCode(), dto, "01234567-89ab-cdef-0123-456789abcdef");

        var command = org.mockito.ArgumentCaptor.forClass(TransitionCommand.class);
        verify(transitions).fire(command.capture());
        assertEquals(BagEvent.SCRAP, command.getValue().getEvent());
        assertTrue(command.getValue().getRemark().contains("复用次数已达上限"));
    }

    @Test
    void platformLedgerPageMapsStationNamesAndBagFields() {
        AuthContext.set(operator());
        Bag listed = bag("SJB-L-2610-00006-9");
        listed.setCreatedAt(java.time.LocalDateTime.now());
        when(bags.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<Bag> page = invocation.getArgument(0);
            page.setRecords(List.of(listed));
            page.setTotal(1);
            return page;
        });
        when(stations.selectByIds(anyCollection())).thenReturn(List.of(station));

        var result = service.page(new BagQuery());

        assertEquals(1, result.getTotal());
        assertEquals("SJB-L-2610-00006-9", result.getRecords().get(0).getBagCode());
        assertEquals("越秀站", result.getRecords().get(0).getStationName());
        assertEquals(BagStatus.IDLE.code(), result.getRecords().get(0).getStatus());
    }

    @Test
    void scanInfoShowsMinimalFieldsToUnrelatedUserAndFullViewToStationAdmin() {
        Bag bag = bag("SJB-L-2610-00007-0");
        when(bags.selectOne(any())).thenReturn(bag);
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(allocationDetails.selectList(any())).thenReturn(List.of());
        when(stations.selectById(7L)).thenReturn(station);
        LoginUser visitor = new LoginUser();
        visitor.setUserId(88L);
        visitor.setRole(Constants.ROLE_USER);
        AuthContext.set(visitor);

        BagInfoVo minimal = service.info(bag.getBagCode());

        assertTrue(minimal.isAvailable());
        assertEquals(bag.getBagCode(), minimal.getBagCode());
        assertNull(minimal.getStatus());
        assertNull(minimal.getStationId());

        LoginUser stationAdmin = new LoginUser();
        stationAdmin.setUserId(90L);
        stationAdmin.setRole(Constants.ROLE_STATION_ADMIN);
        stationAdmin.setStationId(7L);
        AuthContext.set(stationAdmin);
        BagInfoVo full = service.info(bag.getBagCode());
        assertEquals(BagStatus.IDLE.code(), full.getStatus());
        assertEquals(7L, full.getStationId());
        assertEquals(0, full.getUseCount());
    }

    @Test
    void traceConvertsPersistedTransferRowsToLifecycleView() {
        AuthContext.set(operator());
        Bag bag = bag("SJB-L-2610-00008-1");
        when(bags.selectOne(any())).thenReturn(bag);
        when(transferLogs.selectList(any())).thenReturn(List.of());

        var trace = service.trace(bag.getBagCode());

        assertTrue(trace.isEmpty());
        verify(transferLogs).selectList(any());
    }

    private static Bag bag(String code) {
        Bag bag = new Bag();
        bag.setBagCode(code);
        bag.setSpec("L");
        bag.setColor("黄色");
        bag.setCapacity("33×25×5cm");
        bag.setStationId(7L);
        bag.setStatus(BagStatus.IDLE.code());
        bag.setUseCount(0);
        bag.setMaxUseCount(50);
        bag.setVersion(0);
        return bag;
    }

    private static LoginUser operator() {
        LoginUser user = new LoginUser();
        user.setUserId(20L);
        user.setRole(Constants.ROLE_PLATFORM_ADMIN);
        return user;
    }

    private static void initTable(Class<?> type) {
        if (TableInfoHelper.getTableInfo(type) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
    }
}
