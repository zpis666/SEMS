package com.sems.unit.controller;

import com.sems.common.BatchResult;
import com.sems.common.PageResult;
import com.sems.common.Result;
import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.module.bag.controller.DisinfectController;
import com.sems.module.bag.controller.InventoryCheckController;
import com.sems.module.bag.dto.CreateDisinfectDto;
import com.sems.module.bag.dto.CreateInventoryCheckDto;
import com.sems.module.bag.dto.DisinfectCreateVo;
import com.sems.module.bag.dto.DisinfectQuery;
import com.sems.module.bag.dto.DisinfectVo;
import com.sems.module.bag.dto.InventoryCheckQuery;
import com.sems.module.bag.dto.InventoryCheckVo;
import com.sems.module.bag.dto.VoidInventoryCheckDto;
import com.sems.module.bag.service.DisinfectService;
import com.sems.module.bag.service.InventoryCheckService;
import com.sems.module.station.controller.AllocationController;
import com.sems.module.station.controller.StationController;
import com.sems.module.station.dto.AllocationCancelDto;
import com.sems.module.station.dto.AllocationDetailVo;
import com.sems.module.station.dto.AllocationDispatchDto;
import com.sems.module.station.dto.AllocationLostDto;
import com.sems.module.station.dto.AllocationQuery;
import com.sems.module.station.dto.AllocationReceiveDto;
import com.sems.module.station.dto.AllocationRemoveDto;
import com.sems.module.station.dto.AllocationVo;
import com.sems.module.station.dto.CreateAllocationDto;
import com.sems.module.station.dto.CreateStationDto;
import com.sems.module.station.dto.NearbyStationVo;
import com.sems.module.station.dto.RecycleBinVo;
import com.sems.module.station.dto.RecycleConfirmDto;
import com.sems.module.station.dto.RecycleConfirmVo;
import com.sems.module.station.dto.StationQuery;
import com.sems.module.station.dto.StationStatusDto;
import com.sems.module.station.dto.StationStockVo;
import com.sems.module.station.dto.StationVo;
import com.sems.module.station.dto.UpdateStationDto;
import com.sems.module.station.service.AllocationService;
import com.sems.module.station.service.RecycleService;
import com.sems.module.station.service.StationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationsControllerTest {

    private static final String REQUEST_ID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    @DisplayName("盘点与消毒控制器传递筛选、实体和幂等头")
    void inventoryAndDisinfectionDelegation() {
        InventoryCheckService checks = mock(InventoryCheckService.class);
        DisinfectService disinfection = mock(DisinfectService.class);
        InventoryCheckController checkController = new InventoryCheckController(checks);
        DisinfectController disinfectController = new DisinfectController(disinfection);
        InventoryCheckQuery checkQuery = new InventoryCheckQuery();
        CreateInventoryCheckDto createCheck = new CreateInventoryCheckDto();
        VoidInventoryCheckDto voidDto = new VoidInventoryCheckDto();
        InventoryCheckVo checkVo = new InventoryCheckVo();
        PageResult<InventoryCheckVo> checkPage = PageResult.of(List.of(checkVo), 1, 10);
        when(checks.page(checkQuery)).thenReturn(checkPage);
        when(checks.submit(createCheck, REQUEST_ID)).thenReturn(checkVo);
        when(checks.voidCheck(7L, voidDto, REQUEST_ID)).thenReturn(checkVo);

        assertSame(checkPage, checkController.page(checkQuery).getData());
        assertSame(checkVo, checkController.submit(createCheck, REQUEST_ID).getData());
        assertSame(checkVo, checkController.voidCheck(7L, voidDto, REQUEST_ID).getData());
        verify(checks).voidCheck(7L, voidDto, REQUEST_ID);

        DisinfectQuery disinfectQuery = new DisinfectQuery();
        CreateDisinfectDto createDisinfect = new CreateDisinfectDto();
        DisinfectVo disinfectVo = new DisinfectVo();
        DisinfectCreateVo createVo = new DisinfectCreateVo();
        PageResult<DisinfectVo> disinfectPage = PageResult.of(List.of(disinfectVo), 1, 10);
        when(disinfection.page(disinfectQuery)).thenReturn(disinfectPage);
        when(disinfection.register(createDisinfect, REQUEST_ID)).thenReturn(createVo);
        assertSame(disinfectPage, disinfectController.page(disinfectQuery).getData());
        assertSame(createVo, disinfectController.create(createDisinfect, REQUEST_ID).getData());
        verify(disinfection).register(createDisinfect, REQUEST_ID);
    }

    @Test
    @DisplayName("站点读写和回收端点保留参数映射；写接口统一校验幂等 UUID")
    void stationControllerDelegationAndIdempotency() {
        StationService stations = mock(StationService.class);
        RecycleService recycling = mock(RecycleService.class);
        StationController controller = new StationController(stations, recycling);
        StationQuery query = new StationQuery();
        StationVo station = new StationVo();
        PageResult<StationVo> page = PageResult.of(List.of(station), 1, 10);
        CreateStationDto createDto = new CreateStationDto();
        UpdateStationDto updateDto = new UpdateStationDto();
        StationStatusDto statusDto = new StationStatusDto();
        RecycleConfirmDto confirmDto = new RecycleConfirmDto();
        StationStockVo stock = new StationStockVo();
        RecycleConfirmVo confirmed = new RecycleConfirmVo();
        when(stations.page(query)).thenReturn(page);
        when(stations.create(createDto, REQUEST_ID)).thenReturn(station);
        when(stations.update(5L, updateDto, REQUEST_ID)).thenReturn(station);
        when(stations.updateStatus(5L, statusDto, REQUEST_ID)).thenReturn(station);
        when(stations.stock(5L)).thenReturn(stock);
        when(stations.stockWarning()).thenReturn(List.of(stock));
        when(stations.nearby()).thenReturn(List.of(new NearbyStationVo()));
        when(recycling.recycleBin(5L)).thenReturn(List.of(new RecycleBinVo()));
        when(recycling.recycleConfirm(5L, confirmDto, REQUEST_ID)).thenReturn(confirmed);

        assertSame(page, controller.page(query).getData());
        assertSame(station, controller.create(createDto, REQUEST_ID).getData());
        assertSame(station, controller.update(5L, updateDto, REQUEST_ID).getData());
        assertSame(station, controller.updateStatus(5L, statusDto, REQUEST_ID).getData());
        assertSame(stock, controller.stock(5L).getData());
        assertSame(stations.stockWarning(), controller.stockWarning().getData());
        assertEquals(1, controller.recycleBin(5L).getData().size());
        assertSame(confirmed, controller.recycleConfirm(5L, confirmDto, REQUEST_ID).getData());
        assertEquals(1, controller.nearby().getData().size());
        verify(recycling).recycleConfirm(5L, confirmDto, REQUEST_ID);

        BizException invalid = assertThrows(BizException.class, () -> controller.create(createDto, "bad"));
        assertEquals(ErrorCode.PARAM_INVALID, invalid.getErrorCode());
        verify(stations, never()).create(createDto, "bad");
    }

    @Test
    @DisplayName("调拨端点映射编号/DTO/幂等头，批量响应按逐袋成功位汇总")
    void allocationControllerDelegationAndBatchEnvelope() {
        AllocationService service = mock(AllocationService.class);
        AllocationController controller = new AllocationController(service);
        AllocationQuery query = new AllocationQuery();
        AllocationDetailVo detail = new AllocationDetailVo();
        AllocationVo row = new AllocationVo();
        PageResult<AllocationVo> page = PageResult.of(List.of(row), 1, 10);
        CreateAllocationDto createDto = new CreateAllocationDto();
        AllocationCancelDto cancelDto = new AllocationCancelDto();
        AllocationDispatchDto dispatchDto = new AllocationDispatchDto();
        AllocationReceiveDto receiveDto = new AllocationReceiveDto();
        AllocationLostDto lostDto = new AllocationLostDto();
        AllocationRemoveDto removeDto = new AllocationRemoveDto();
        List<AllocationService.BagResult> results = List.of(
                AllocationService.BagResult.ok("SJB-L-2609-00001-4"),
                AllocationService.BagResult.fail("SJB-M-2609-00002-1", ErrorCode.BAG_ILLEGAL_TRANSITION, "状态非法"));
        when(service.page(query)).thenReturn(page);
        when(service.detail(3L)).thenReturn(detail);
        when(service.create(createDto, REQUEST_ID)).thenReturn(detail);
        when(service.cancelOrTerminate(3L, cancelDto, REQUEST_ID)).thenReturn(detail);
        when(service.dispatch(3L, dispatchDto, REQUEST_ID)).thenReturn(results);
        when(service.receive(3L, receiveDto, REQUEST_ID)).thenReturn(results);
        when(service.lostConfirm(3L, lostDto, REQUEST_ID)).thenReturn(results);
        when(service.removeDetail(3L, removeDto, REQUEST_ID)).thenReturn(detail);

        assertSame(page, controller.page(query).getData());
        assertSame(detail, controller.detail(3L).getData());
        assertSame(detail, controller.create(createDto, REQUEST_ID).getData());
        assertSame(detail, controller.cancelOrTerminate(3L, cancelDto, REQUEST_ID).getData());
        assertBatch(controller.dispatch(3L, dispatchDto, REQUEST_ID).getData());
        assertBatch(controller.receive(3L, receiveDto, REQUEST_ID).getData());
        assertBatch(controller.lostConfirm(3L, lostDto, REQUEST_ID).getData());
        assertSame(detail, controller.removeDetail(3L, removeDto, REQUEST_ID).getData());
        verify(service).dispatch(3L, dispatchDto, REQUEST_ID);
        verify(service).receive(3L, receiveDto, REQUEST_ID);
        verify(service).lostConfirm(3L, lostDto, REQUEST_ID);
    }

    private static void assertBatch(BatchResult<AllocationService.BagResult> batch) {
        assertEquals(2, batch.getTotal());
        assertEquals(1, batch.getSuccess());
        assertEquals(1, batch.getFailed());
        assertEquals(4102, batch.getItems().get(1).code());
    }
}
