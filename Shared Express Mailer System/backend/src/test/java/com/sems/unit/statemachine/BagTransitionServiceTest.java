package com.sems.unit.statemachine;

import com.sems.common.BizException;
import com.sems.infra.IdempotentService;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.entity.BagTransferLog;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.bag.mapper.BagTransferLogMapper;
import com.sems.module.statemachine.*;
import com.sems.push.PushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BagTransitionServiceTest {
    private BagMapper bags;
    private BagTransferLogMapper logs;
    private IdempotentService idempotency;
    private PushService push;
    private BagTransitionService service;

    @BeforeEach
    void setUp() {
        bags = mock(BagMapper.class);
        logs = mock(BagTransferLogMapper.class);
        idempotency = mock(IdempotentService.class);
        push = mock(PushService.class);
        service = new BagTransitionService(bags, logs, idempotency, push, List.of());
    }

    @Test
    void rejectsMissingCommandAndMissingFieldsBeforeDatabaseAccess() {
        assertThrows(BizException.class, () -> service.fire(null));
        assertThrows(BizException.class, () -> service.fire(TransitionCommand.builder().build()));
        assertThrows(BizException.class, () -> service.fire(TransitionCommand.builder().bagCode("B-1").build()));
        verifyNoInteractions(bags, logs, push, idempotency);
    }

    @Test
    void rejectsWhitespaceBagCodeBeforeAnyStorageOperation() {
        assertThrows(BizException.class, () -> service.fire(TransitionCommand.builder()
                .bagCode("  ").event(BagEvent.BORROW).build()));
        verifyNoInteractions(bags, logs, push, idempotency);
    }

    @Test
    void rejectsUnknownBagAndIllegalStateTransition() {
        when(bags.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> service.fire(command(BagEvent.BORROW)));
        Bag bag = bag(BagStatus.REPAIRING, 0, 10, 1L);
        when(bags.selectOne(any())).thenReturn(bag);
        assertThrows(BizException.class, () -> service.fire(command(BagEvent.BORROW)));
        verify(bags, never()).update(any(), any());
        verifyNoInteractions(logs);
    }

    @Test
    void refusesIdleTransitionAfterReuseLimitWithoutWriting() {
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.TO_DISINFECT, 5, 5, 3L));
        assertThrows(BizException.class, () -> service.fire(command(BagEvent.DISINFECT_PASS)));
        verify(bags, never()).update(any(), any());
        verifyNoInteractions(logs);
    }

    @Test
    void successfulBorrowPerformsConditionalUpdateAndWritesOneTransferLog() {
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.IDLE, 0, 10, 7L));
        when(bags.update(isNull(), any())).thenReturn(1);

        BagTransitionResult result = service.fire(command(BagEvent.BORROW));

        assertEquals(BagStatus.IDLE, result.getFromStatus());
        assertEquals(BagStatus.ALLOCATED, result.getToStatus());
        assertFalse(result.isReplayed());
        assertEquals(7L, result.getStationId());
        verify(bags).update(isNull(), any());
        verify(logs).insert(any(BagTransferLog.class));
        verify(push).afterCommit(any(Runnable.class));
    }

    @Test
    void firstRequestIdAcquisitionRunsTheTransitionAndPersistsProxyFlag() {
        String requestId = "01234567-89ab-cdef-0123-456789abcdef";
        when(idempotency.tryAcquireBag(requestId, "B-1")).thenReturn(true);
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.IDLE, 0, 10, 7L));
        when(bags.update(isNull(), any())).thenReturn(1);
        TransitionCommand cmd = TransitionCommand.builder().bagCode("B-1").event(BagEvent.BORROW)
                .clientRequestId(requestId).proxy(true).operatorId(9L).stationId(7L).build();

        service.fire(cmd);

        verify(idempotency).tryAcquireBag(requestId, "B-1");
        verify(logs).insert(argThat((BagTransferLog log) -> Integer.valueOf(1).equals(log.getProxyFlag())));
    }

    @Test
    void optimisticConflictStopsBeforeLogAndAction() {
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.IDLE, 0, 10, 7L));
        when(bags.update(isNull(), any())).thenReturn(0);
        assertThrows(BizException.class, () -> service.fire(command(BagEvent.BORROW)));
        verifyNoInteractions(logs);
        verifyNoInteractions(push);
    }

    @Test
    void pendingAllocationLockBlocksBorrowRepairScrapAndStatusCorrection() {
        when(bags.countPendingAllocationForUpdate("B-1")).thenReturn(1L);
        when(bags.selectOne(any())).thenAnswer(invocation -> bag(BagStatus.IDLE, 0, 50, 7L));
        List<TransitionCommand> lockedEvents = List.of(
                command(BagEvent.BORROW), command(BagEvent.REPAIR_START), command(BagEvent.SCRAP_DIRECT),
                statusCorrection(BagStatus.REPAIRING, "盘点核实"));

        for (TransitionCommand command : lockedEvents) {
            assertThrows(BizException.class, () -> service.fire(command), command.getEvent().name());
        }

        verify(bags, times(4)).countPendingAllocationForUpdate("B-1");
        verify(bags, never()).update(any(), any());
        verifyNoInteractions(logs, push);
    }

    @Test
    void unrelatedEventsDoNotPayTheAllocationLockCheck() {
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.TO_DISINFECT, 0, 50, 7L));
        when(bags.update(isNull(), any())).thenReturn(1);

        BagTransitionResult result = service.fire(command(BagEvent.DISINFECT_PASS));

        assertEquals(BagStatus.IDLE, result.getToStatus());
        verify(bags, never()).countPendingAllocationForUpdate(anyString());
    }

    @Test
    void idempotentReplayDoesNotWriteLogOrPushAgain() {
        when(idempotency.tryAcquireBag("req-1", "B-1")).thenReturn(false);
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.ALLOCATED, 0, 10, 7L));
        TransitionCommand cmd = TransitionCommand.builder().bagCode("B-1").event(BagEvent.BORROW)
                .operatorId(9L).stationId(7L).clientRequestId("req-1").build();

        BagTransitionResult result = service.fire(cmd);

        assertTrue(result.isReplayed());
        assertNull(result.getFromStatus());
        assertEquals(BagStatus.ALLOCATED, result.getToStatus());
        verify(bags, never()).update(any(), any());
        verifyNoInteractions(logs, push);
    }

    @Test
    void occupiedIdempotencyKeyWhoseFirstAttemptDidNotLandFallsThroughToDatabaseGuard() {
        when(idempotency.tryAcquireBag("req-1", "B-1")).thenReturn(false);
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.IDLE, 0, 10, 7L));
        when(bags.update(isNull(), any())).thenReturn(1);
        BagTransitionResult result = service.fire(TransitionCommand.builder().bagCode("B-1")
                .event(BagEvent.BORROW).operatorId(9L).stationId(7L).clientRequestId("req-1").build());
        assertFalse(result.isReplayed());
        assertEquals(BagStatus.ALLOCATED, result.getToStatus());
        verify(bags, times(2)).selectOne(any());
        verify(logs).insert(any(BagTransferLog.class));
    }

    @Test
    void idempotentLookupForUnregisteredEventFallsThroughThenRejectsIllegalTransition() {
        String requestId = "01234567-89ab-cdef-0123-456789abcdef";
        when(idempotency.tryAcquireBag(requestId, "B-1")).thenReturn(false);
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.IDLE, 0, 50, 7L));
        TransitionCommand cmd = TransitionCommand.builder().bagCode("B-1").event(BagEvent.CREATE)
                .clientRequestId(requestId).build();
        assertThrows(BizException.class, () -> service.fire(cmd));
        verify(bags, times(2)).selectOne(any());
        verifyNoInteractions(logs, push);
    }

    @Test
    void statusCorrectionIsNeverReportedAsAReplayFromMatchingStateAlone() {
        String requestId = "01234567-89ab-cdef-0123-456789abcdef";
        when(idempotency.tryAcquireBag(requestId, "B-1")).thenReturn(false);
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.IDLE, 0, 50, 7L));
        TransitionCommand cmd = TransitionCommand.builder().bagCode("B-1").event(BagEvent.STATUS_CORRECT)
                .clientRequestId(requestId).targetStatus(BagStatus.IDLE).remark("盘点后未发生变化").build();

        assertThrows(BizException.class, () -> service.fire(cmd));

        verify(bags, times(2)).selectOne(any());
        verify(bags, never()).update(any(), any());
        verifyNoInteractions(logs, push);
    }

    @Test
    void duplicateActionRegistrationIsRejectedAtConstruction() {
        TransitionAction one = action(BagEvent.BORROW);
        TransitionAction two = action(BagEvent.BORROW);
        assertThrows(IllegalStateException.class,
                () -> new BagTransitionService(bags, logs, idempotency, push, List.of(one, two)));
        TransitionAction noEvent = new TransitionAction() {
            public BagEvent event() { return null; }
            public void apply(BagTransitionContext ignored) { }
        };
        assertThrows(IllegalStateException.class,
                () -> new BagTransitionService(bags, logs, idempotency, push, List.of(noEvent)));
    }

    @Test
    void actionFailureAfterLogPreventsPostCommitPushRegistration() {
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.IDLE, 0, 10, 7L));
        when(bags.update(isNull(), any())).thenReturn(1);
        TransitionAction throwingAction = new TransitionAction() {
            public BagEvent event() { return BagEvent.BORROW; }
            public void apply(BagTransitionContext ignored) { throw new IllegalStateException("write failed"); }
        };
        BagTransitionService withAction = new BagTransitionService(bags, logs, idempotency, push,
                List.of(throwingAction));
        assertThrows(IllegalStateException.class, () -> withAction.fire(command(BagEvent.BORROW)));
        verify(logs).insert(any(BagTransferLog.class));
        verifyNoInteractions(push);
    }

    @Test
    void idleTransitionWithMissingStationSkipsPushWithoutFailingTheFlow() {
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.IDLE, 0, 10, null));
        when(bags.update(isNull(), any())).thenReturn(1);
        BagTransitionResult result = service.fire(TransitionCommand.builder().bagCode("B-1")
                .event(BagEvent.BORROW).operatorId(9L).build());
        assertEquals(BagStatus.ALLOCATED, result.getToStatus());
        verifyNoInteractions(push);
    }

    @Test
    void everyRegisteredTransitionCanPassTheAtomicDatabasePath() {
        AtomicReference<Bag> current = new AtomicReference<>();
        when(bags.selectOne(any())).thenAnswer(ignored -> current.get());
        when(bags.update(isNull(), any())).thenReturn(1);
        int executed = 0;
        for (TransitionRule rule : TransitionRegistry.all()) {
            String code = "B-" + executed;
            Bag bag = bag(rule.from(), 0, 50, 10L);
            bag.setBagCode(code);
            current.set(bag);
            TransitionCommand command = TransitionCommand.builder().bagCode(code).event(rule.event())
                    .stationId(10L).operatorId(9L).build();
            BagTransitionResult result = service.fire(command);
            assertEquals(rule.from(), result.getFromStatus(), rule.toString());
            assertEquals(rule.to(), result.getToStatus(), rule.toString());
            assertFalse(result.isReplayed());
            executed++;
        }
        assertEquals(21, executed);
        verify(bags, times(21)).update(isNull(), any());
        verify(logs, times(21)).insert(any(BagTransferLog.class));
    }

    @Test
    void statusCorrectionAcceptsOnlyTheFourDocumentedTargetsAndMovesStation() {
        when(bags.update(isNull(), any())).thenReturn(1);
        for (BagStatus target : List.of(BagStatus.IDLE, BagStatus.REPAIRING, BagStatus.LOST, BagStatus.SCRAPPED)) {
            when(bags.selectOne(any())).thenReturn(bag(BagStatus.IN_USE, 0, 50, 10L));
            TransitionCommand command = TransitionCommand.builder().bagCode("B-1").event(BagEvent.STATUS_CORRECT)
                    .stationId(10L).targetStationId(11L).targetStatus(target).remark("实物盘点核实").build();
            BagTransitionResult result = service.fire(command);
            assertEquals(target, result.getToStatus());
            assertEquals(11L, result.getStationId());
        }
        verify(logs, times(4)).insert(any(BagTransferLog.class));
        verify(push).afterCommit(any(Runnable.class));
    }

    @Test
    void statusCorrectionRejectsTerminalSourceMissingTargetNonAllowlistedTargetAndBlankReason() {
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.SCRAPPED, 0, 50, 10L));
        assertThrows(BizException.class, () -> service.fire(statusCorrection(BagStatus.IDLE, "原因")));
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.IDLE, 0, 50, 10L));
        assertThrows(BizException.class, () -> service.fire(statusCorrection(null, "原因")));
        assertThrows(BizException.class, () -> service.fire(statusCorrection(BagStatus.ALLOCATED, "原因")));
        assertThrows(BizException.class, () -> service.fire(statusCorrection(BagStatus.IDLE, " ")));
        assertThrows(BizException.class, () -> service.fire(statusCorrection(BagStatus.IDLE, null)));
        verifyNoInteractions(logs, push);
    }

    @Test
    void statusCorrectionRejectsNoopButAllowsSameStatusWhenStationActuallyChanges() {
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.IDLE, 0, 50, 10L));
        assertThrows(BizException.class, () -> service.fire(TransitionCommand.builder().bagCode("B-1")
                .event(BagEvent.STATUS_CORRECT).targetStatus(BagStatus.IDLE).targetStationId(10L)
                .remark("盘点核实").build()));
        verify(bags, never()).update(any(), any());
        verifyNoInteractions(logs, push);

        when(bags.update(isNull(), any())).thenReturn(1);
        BagTransitionResult moved = service.fire(TransitionCommand.builder().bagCode("B-1")
                .event(BagEvent.STATUS_CORRECT).stationId(10L).targetStatus(BagStatus.IDLE)
                .targetStationId(11L).remark("实物跨站").build());
        assertEquals(11L, moved.getStationId());
        verify(bags).update(isNull(), any());
        verify(logs).insert(any(BagTransferLog.class));
        verify(push).afterCommit(any(Runnable.class));
    }

    @Test
    void statusCorrectionWithoutTargetStationAlsoRejectsSameState() {
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.IDLE, 0, 50, 10L));
        TransitionCommand noOp = TransitionCommand.builder().bagCode("B-1").event(BagEvent.STATUS_CORRECT)
                .targetStatus(BagStatus.IDLE).remark("核对一致").build();
        assertThrows(BizException.class, () -> service.fire(noOp));
        verify(bags, never()).update(any(), any());
    }

    @Test
    void blankClientRequestIdSkipsBagLevelIdempotency() {
        when(bags.selectOne(any())).thenReturn(bag(BagStatus.IDLE, 0, 50, 7L));
        when(bags.update(isNull(), any())).thenReturn(1);
        TransitionCommand cmd = TransitionCommand.builder().bagCode("B-1").event(BagEvent.BORROW)
                .clientRequestId("  ").operatorId(4L).stationId(7L).build();

        service.fire(cmd);

        verifyNoInteractions(idempotency);
        verify(logs).insert(any(BagTransferLog.class));
    }

    @Test
    void unchangedAvailableStateDoesNotRegisterStockPush() throws Exception {
        var method = BagTransitionService.class.getDeclaredMethod("registerStockPush", TransitionCommand.class,
                Bag.class, BagStatus.class, BagStatus.class, Long.class);
        method.setAccessible(true);
        Bag bag = bag(BagStatus.IDLE, 0, 50, 7L);
        TransitionCommand cmd = TransitionCommand.builder().bagCode("B-1").event(BagEvent.STATUS_CORRECT).build();

        method.invoke(service, cmd, bag, BagStatus.IDLE, BagStatus.IDLE, 7L);

        verifyNoInteractions(push);
    }

    private static TransitionCommand statusCorrection(BagStatus target, String reason) {
        return TransitionCommand.builder().bagCode("B-1").event(BagEvent.STATUS_CORRECT)
                .targetStatus(target).remark(reason).build();
    }

    private static TransitionCommand command(BagEvent event) {
        return TransitionCommand.builder().bagCode("B-1").event(event).operatorId(9L).stationId(7L).build();
    }

    private static Bag bag(BagStatus status, int useCount, int maxUseCount, Long stationId) {
        Bag bag = new Bag();
        bag.setBagCode("B-1");
        bag.setStatus(status.code());
        bag.setUseCount(useCount);
        bag.setMaxUseCount(maxUseCount);
        bag.setVersion(4);
        bag.setStationId(stationId);
        return bag;
    }

    private static TransitionAction action(BagEvent event) {
        return new TransitionAction() {
            public BagEvent event() { return event; }
            public void apply(BagTransitionContext ignored) { }
        };
    }
}
