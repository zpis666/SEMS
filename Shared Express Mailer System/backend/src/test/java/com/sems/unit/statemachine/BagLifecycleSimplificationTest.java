package com.sems.unit.statemachine;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.infra.IdempotentService;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.bag.mapper.BagTransferLogMapper;
import com.sems.module.statemachine.*;
import com.sems.push.PushService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 四态模型的业务回归：清洗前不可领用、超限不可入库、归还标记不改变袋状态。 */
class BagLifecycleSimplificationTest {
    @Test
    void normalCycleAndRepairRequireDisinfection() {
        assertSame(BagStatus.ALLOCATED, TransitionRegistry.resolve(BagStatus.IDLE, BagEvent.BORROW));
        assertSame(BagStatus.IN_USE, TransitionRegistry.resolve(BagStatus.ALLOCATED, BagEvent.SHIP));
        assertSame(BagStatus.IN_USE, TransitionRegistry.resolve(BagStatus.IN_USE, BagEvent.RETURN_MARK));
        assertSame(BagStatus.TO_DISINFECT, TransitionRegistry.resolve(BagStatus.IN_USE, BagEvent.RECOVER_CONFIRM));
        assertSame(BagStatus.TO_DISINFECT, TransitionRegistry.resolve(BagStatus.IN_USE, BagEvent.RECOVER_DIRECT));
        assertSame(BagStatus.IDLE, TransitionRegistry.resolve(BagStatus.TO_DISINFECT, BagEvent.DISINFECT_PASS));
        assertSame(BagStatus.TO_DISINFECT, TransitionRegistry.resolve(BagStatus.REPAIRING, BagEvent.REPAIR_DONE));
        assertNull(TransitionRegistry.resolve(BagStatus.TO_DISINFECT, BagEvent.BORROW));
        assertNull(TransitionRegistry.resolve(BagStatus.IDLE, BagEvent.DISINFECT_PASS));
        assertNull(TransitionRegistry.resolve(BagStatus.IDLE, BagEvent.ACTIVATE));
        assertNull(TransitionRegistry.resolve(BagStatus.IDLE, BagEvent.SHELVE));
        for (String obsolete : List.of("INACTIVE", "REUSABLE", "RETURNING")) {
            assertThrows(IllegalArgumentException.class, () -> BagStatus.of(obsolete));
        }
    }

    @Test
    void exhaustedBagCannotBeReleasedAfterDisinfection() {
        BagMapper mapper = mock(BagMapper.class);
        BagTransferLogMapper logs = mock(BagTransferLogMapper.class);
        Bag bag = new Bag();
        bag.setBagCode("SJB-L-2610-00001-0");
        bag.setStatus("TO_DISINFECT");
        bag.setUseCount(50);
        bag.setMaxUseCount(50);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(bag);
        BagTransitionService service = new BagTransitionService(mapper, logs,
                mock(IdempotentService.class), mock(PushService.class), List.of());
        BizException failure = assertThrows(BizException.class, () -> service.fire(
                TransitionCommand.builder().bagCode(bag.getBagCode()).event(BagEvent.DISINFECT_PASS).build()));
        assertSame(ErrorCode.USE_COUNT_EXCEEDED, failure.getErrorCode());
        verify(mapper, never()).update(any(Bag.class), any(Wrapper.class));
        verifyNoInteractions(logs);
    }
}
