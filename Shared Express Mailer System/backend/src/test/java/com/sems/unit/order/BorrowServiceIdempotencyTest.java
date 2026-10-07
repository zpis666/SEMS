package com.sems.unit.order;

import com.sems.config.SemsProperties;
import com.sems.infra.*;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.mapper.*;
import com.sems.module.order.service.BorrowService;
import com.sems.module.station.mapper.*;
import com.sems.module.statemachine.BagTransitionService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class BorrowServiceIdempotencyTest {
    @Test
    void allWriteOperationsRejectMissingIdempotencyKeyBeforeSideEffects() {
        BagMapper bags = mock(BagMapper.class);
        StationMapper stations = mock(StationMapper.class);
        AllocationDetailMapper allocationDetails = mock(AllocationDetailMapper.class);
        AllocationOrderMapper allocationOrders = mock(AllocationOrderMapper.class);
        BagOrderMapper orders = mock(BagOrderMapper.class);
        StockCompensationLogMapper compensationLogs = mock(StockCompensationLogMapper.class);
        StockService stock = mock(StockService.class);
        IdempotentService idempotency = mock(IdempotentService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        BagTransitionService transitions = mock(BagTransitionService.class);
        BorrowService service = new BorrowService(bags, stations, allocationDetails, allocationOrders, orders,
                compensationLogs, stock, idempotency, rateLimit, transitions, mock(com.sems.module.order.service.OrderService.class),
                new SemsProperties(), mock(PlatformTransactionManager.class));

        assertThrows(RuntimeException.class, () -> service.borrow(null, null));
        assertThrows(RuntimeException.class, () -> service.shipping("ORD-1", null, " "));
        assertThrows(RuntimeException.class, () -> service.withdraw("ORD-1", null));
        verifyNoInteractions(bags, stations, allocationDetails, allocationOrders, orders,
                compensationLogs, stock, idempotency, rateLimit, transitions);
    }
}
