package com.sems.module.bag.service.action;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.sems.infra.CounterService;
import com.sems.infra.StockService;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.entity.StockRecord;
import com.sems.module.bag.mapper.*;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.order.mapper.LiabilityRecordMapper;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.station.mapper.AllocationDetailMapper;
import com.sems.module.station.mapper.AllocationOrderMapper;
import com.sems.module.statemachine.*;
import com.sems.push.PushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BagTransitionActionsTest {
    private StockService stock;
    private CounterService counters;
    private StockRecordMapper stockRecords;
    private RepairRecordMapper repairs;
    private ScrapRecordMapper scraps;
    private DisinfectDetailMapper disinfectDetails;
    private BagOrderMapper orders;
    private LiabilityRecordMapper liabilities;
    private BagTransitionActions actions;
    private PushService push;

    @BeforeEach
    void setup() {
        if (TableInfoHelper.getTableInfo(BagOrder.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), BagOrder.class);
        }
        stock = mock(StockService.class);
        counters = mock(CounterService.class);
        stockRecords = mock(StockRecordMapper.class);
        repairs = mock(RepairRecordMapper.class);
        scraps = mock(ScrapRecordMapper.class);
        disinfectDetails = mock(DisinfectDetailMapper.class);
        orders = mock(BagOrderMapper.class);
        liabilities = mock(LiabilityRecordMapper.class);
        push = mock(PushService.class);
        actions = new BagTransitionActions(stock, counters, stockRecords, repairs, scraps, disinfectDetails,
                orders, liabilities, mock(AllocationDetailMapper.class),
                mock(AllocationOrderMapper.class), push);
    }

    @Test
    void activateAddsStockRecordAndMakesBagAvailable() {
        actions.dispatch(BagEvent.ACTIVATE, context(BagStatus.IDLE, BagEvent.ACTIVATE, null));
        verify(stockRecords).insert(argThat((StockRecord record) -> "IN".equals(record.getType())
                && "激活上架".equals(record.getRemark()) && Long.valueOf(3).equals(record.getStationId())));
        verify(stock).increaseAvailable(3L, 1);
    }

    @Test
    void disinfectPassWritesStockReceiptAndDefersRedisIncrement() {
        actions.dispatch(BagEvent.DISINFECT_PASS,
                context(BagStatus.TO_DISINFECT, BagEvent.DISINFECT_PASS, null));
        verify(stockRecords).insert(argThat((StockRecord record) -> "IN".equals(record.getType())
                && "消毒合格自动入库".equals(record.getRemark())));
        verify(push).afterCommit(any(Runnable.class));
        verifyNoInteractions(stock);
    }

    @Test
    void repairStartCreatesDescriptionAndDeductsOnlyIdleInventory() {
        actions.dispatch(BagEvent.REPAIR_START,
                context(BagStatus.IDLE, BagEvent.REPAIR_START, "拉链损坏"));
        verify(repairs).insert(argThat((com.sems.module.bag.entity.RepairRecord record) -> "拉链损坏".equals(record.getDescription())
                && Long.valueOf(12).equals(record.getReporterId())));
        verify(stock).increaseAvailable(3L, -1);

        clearInvocations(stock, repairs);
        actions.dispatch(BagEvent.REPAIR_START,
                context(BagStatus.TO_DISINFECT, BagEvent.REPAIR_START, "消毒缺陷"));
        verify(repairs).insert(any(com.sems.module.bag.entity.RepairRecord.class));
        verifyNoInteractions(stock);
    }

    @Test
    void disinfectFailureUsesLatestDefectDescriptionWhenAvailable() {
        var detail = new com.sems.module.bag.entity.DisinfectDetail();
        detail.setDefectDesc("消毒袋面有污渍");
        when(disinfectDetails.selectList(any())).thenReturn(java.util.List.of(detail));
        actions.dispatch(BagEvent.DISINFECT_FAIL,
                context(BagStatus.TO_DISINFECT, BagEvent.DISINFECT_FAIL, "调用方说明"));
        verify(repairs).insert(argThat((com.sems.module.bag.entity.RepairRecord record) -> "消毒袋面有污渍".equals(record.getDescription())));
        verifyNoInteractions(stock);
    }

    @Test
    void unknownEventIsRejectedAsConfigurationError() {
        assertThrows(IllegalStateException.class,
                () -> actions.dispatch(BagEvent.BORROW, context(BagStatus.IDLE, BagEvent.BORROW, null)));
    }

    private static BagTransitionContext context(BagStatus from, BagEvent event, String remark) {
        Bag bag = new Bag();
        bag.setBagCode("B-3");
        bag.setStatus(from.code());
        bag.setStationId(3L);
        TransitionCommand cmd = TransitionCommand.builder().bagCode("B-3").event(event)
                .stationId(3L).operatorId(12L).remark(remark).build();
        return BagTransitionContext.builder().cmd(cmd).bag(bag).operatorId(12L).build();
    }

    @Test
    void correctingAnInUseBagWithoutBindingOrderFailsClosed() {
        when(orders.selectList(any())).thenReturn(java.util.List.of());
        assertThrows(com.sems.common.BizException.class,
                () -> actions.dispatch(BagEvent.STATUS_CORRECT,
                        correctionContext(BagStatus.IN_USE, BagStatus.LOST)));
        verifyNoInteractions(counters, liabilities);
    }

    @Test
    void correctingAnInUseBagClosesOrderAndDefersOpenCountRelease() {
        BagOrder order = new BagOrder();
        order.setId(41L);
        order.setOrderNo("ORD-41");
        order.setBagCode("B-3");
        order.setUserId(12L);
        order.setStatus(com.sems.module.statemachine.OrderStatus.IN_USE.code());
        order.setVersion(2);
        when(orders.selectList(any())).thenReturn(java.util.List.of(order));
        when(orders.updateById(any(BagOrder.class))).thenReturn(1);

        actions.dispatch(BagEvent.STATUS_CORRECT, correctionContext(BagStatus.IN_USE, BagStatus.LOST));

        verify(orders).updateById(argThat((BagOrder update) -> OrderStatus.LOST.code().equals(update.getStatus())
                && update.getId().equals(41L)));
        verify(push, atLeastOnce()).afterCommit(any(Runnable.class));
        verify(liabilities).insert(any(com.sems.module.order.entity.LiabilityRecord.class));
        verifyNoInteractions(counters);
    }

    private static BagTransitionContext correctionContext(BagStatus from, BagStatus target) {
        Bag bag = new Bag();
        bag.setBagCode("B-3");
        bag.setStatus(from.code());
        bag.setStationId(3L);
        TransitionCommand cmd = TransitionCommand.builder().bagCode("B-3").event(BagEvent.STATUS_CORRECT)
                .stationId(3L).targetStatus(target).remark("盘点确认责任归属").operatorId(12L).build();
        return BagTransitionContext.builder().cmd(cmd).bag(bag).operatorId(12L).build();
    }
}
