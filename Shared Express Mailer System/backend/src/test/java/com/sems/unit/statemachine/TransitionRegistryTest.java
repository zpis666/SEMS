package com.sems.unit.statemachine;

import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.statemachine.OrderStatus;
import com.sems.module.statemachine.TransitionRegistry;
import com.sems.module.statemachine.TransitionRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机转移注册表单元测试（对应《系统设计说明书》12.2 单元测试行的
 * 「状态机全部 21 条转移 + 非法转移拒绝」，并要求状态机分支覆盖 100%）。
 *
 * <p>本测试把设计书 3.2「状态转移表」的 21 条编号规则**逐条固化**为断言：
 * 编号规则中 #12/#15/#18 各覆盖 当前有效前态，展开后共 21 条记录。
 * 一旦有人改坏注册表（漏注册、注册错方向、把 STATUS_CORRECT 当普通规则注册），本测试立即失败。
 *
 * <p>这里刻意不启动 Spring 容器：注册表是启动即冻结的纯数据、查询是无状态纯函数，
 * 单元测试直接调用静态方法即可，跑得快且不依赖 MySQL/Redis。
 */
class TransitionRegistryTest {

    /** 设计书 3.2 的全部 21 条展开转移：(前态, 事件) → 目标态 */
    private static final Map<String, BagStatus> EXPECTED = Map.ofEntries(
            // #1 建档即空闲，不注册激活规则

            // #2 空闲 --扫码申领--> 已分配
            Map.entry("IDLE#BORROW", BagStatus.ALLOCATED),
            // #3 已分配 --提交寄件--> 使用中
            Map.entry("ALLOCATED#SHIP", BagStatus.IN_USE),
            // #4 已分配 --未使用回收--> 空闲
            Map.entry("ALLOCATED#RECOVER_UNUSED", BagStatus.IDLE),
            // #5 已分配 --回收确认--> 待消毒
            Map.entry("ALLOCATED#RECOVER_CONFIRM", BagStatus.TO_DISINFECT),
            // #6 使用中 --标记归还--> 使用中
            Map.entry("IN_USE#RETURN_MARK", BagStatus.IN_USE),
            // #7 使用中 --回收确认（直投）--> 待消毒
            Map.entry("IN_USE#RECOVER_DIRECT", BagStatus.TO_DISINFECT),
            // #8 使用中（已标记归还） --回收确认--> 待消毒
            Map.entry("IN_USE#RECOVER_CONFIRM", BagStatus.TO_DISINFECT),
            // #9 待消毒 --消毒完成（合格）--> 空闲
            Map.entry("TO_DISINFECT#DISINFECT_PASS", BagStatus.IDLE),
            // #10 待消毒 --消毒完成（不合格）--> 维修中
            Map.entry("TO_DISINFECT#DISINFECT_FAIL", BagStatus.REPAIRING),
            // #11 消毒合格自动入库，不注册单独上架规则

            // #12 空闲/待消毒 --转维修--> 维修中（当前有效前态）
            Map.entry("IDLE#REPAIR_START", BagStatus.REPAIRING),
            Map.entry("TO_DISINFECT#REPAIR_START", BagStatus.REPAIRING),

            // #13 维修中 --修复入库--> 待消毒
            Map.entry("REPAIRING#REPAIR_DONE", BagStatus.TO_DISINFECT),
            // #14 维修中 --报废--> 报废
            Map.entry("REPAIRING#SCRAP", BagStatus.SCRAPPED),
            // #15 空闲/待消毒 --直接报废--> 报废（当前有效前态）
            Map.entry("IDLE#SCRAP_DIRECT", BagStatus.SCRAPPED),
            Map.entry("TO_DISINFECT#SCRAP_DIRECT", BagStatus.SCRAPPED),

            // #16 空闲 --调拨出库--> 调拨在途
            Map.entry("IDLE#ALLOCATE_OUT", BagStatus.TRANSFERRING),
            // #17 调拨在途 --调拨入库--> 空闲
            Map.entry("TRANSFERRING#ALLOCATE_IN", BagStatus.IDLE),
            // #18 使用中/已分配 --确认遗失--> 已遗失（当前有效前态）
            Map.entry("IN_USE#LOST_CONFIRM", BagStatus.LOST),

            Map.entry("ALLOCATED#LOST_CONFIRM", BagStatus.LOST),
            // #19 调拨在途 --在途遗失确认--> 已遗失
            Map.entry("TRANSFERRING#LOST_IN_TRANSIT", BagStatus.LOST),
            // #20 已遗失 --找回--> 待消毒
            Map.entry("LOST#FOUND", BagStatus.TO_DISINFECT)
            // #21 状态修正不在此表：目标态由命令指定，见下一个测试方法
    );

    @Test
    @DisplayName("设计书 3.2 的 21 条规则（展开 21 条）全部注册且目标态一一对应")
    void allTwentySixExpandedTransitionsAreRegistered() {
        assertEquals(21, EXPECTED.size(), "期望集本身应为 21 条（#12/#15/#18 各展开 当前有效前态）");
        assertEquals(21, TransitionRegistry.all().size(),
                "注册表条数与设计书 3.2 展开后条数不一致——漏注册或多注册");

        EXPECTED.forEach((key, expectedTo) -> {
            String[] parts = key.split("#");
            BagStatus from = BagStatus.valueOf(parts[0]);
            BagEvent event = BagEvent.valueOf(parts[1]);
            TransitionRule rule = TransitionRegistry.lookup(from, event);
            assertNotNull(rule, "未注册的合法转移：" + from + " + " + event);
            assertSame(expectedTo, rule.to(), "目标态不符：" + key);
            assertNotNull(rule.description(), "规则缺少说明：" + key);
            assertFalse(rule.description().isBlank(), "规则说明为空：" + key);
        });
    }

    @Test
    @DisplayName("状态修正（#21）不注册规则：目标态由命令指定，resolve 返回 null 而非误判非法跳变")
    void statusCorrectIsResolvedByCommandNotByRegistry() {
        for (BagStatus from : BagStatus.values()) {
            assertNull(TransitionRegistry.lookup(from, BagEvent.STATUS_CORRECT),
                    "状态修正不应在注册表里：" + from);
        }
    }

    @Test
    @DisplayName("未注册的 (状态, 事件) 组合一律返回 null：状态机据此拒绝并抛 4102")
    void unregisteredCombinationsReturnNull() {
        // 任意状态 + 与它无关的事件，除去上表 21 条后全部应为 null
        int legal = 0;
        int illegal = 0;
        for (BagStatus from : BagStatus.values()) {
            for (BagEvent event : BagEvent.values()) {
                BagStatus resolved = TransitionRegistry.resolve(from, event);
                String key = from.name() + "#" + event.name();
                if (EXPECTED.containsKey(key)) {
                    assertSame(EXPECTED.get(key), resolved, "合法转移解析错误：" + key);
                    legal++;
                } else {
                    assertNull(resolved, "非法组合不应有目标态：" + key);
                    illegal++;
                }
            }
        }
        assertEquals(21, legal);
        // 8 态 × 21 事件 = 168 种组合，其中 21 条合法
        assertEquals(BagStatus.values().length * BagEvent.values().length - 21, illegal);
    }

    @Test
    @DisplayName("终态与业务约束：报废无出边；已遗失可找回故非终态；仅空闲计入可用库存")
    void terminalStateAndAvailabilitySemantics() {
        assertTrue(BagStatus.SCRAPPED.isTerminal(), "报废是唯一终态");
        assertFalse(BagStatus.LOST.isTerminal(), "已遗失可经找回回到循环，不是终态");

        for (BagEvent event : BagEvent.values()) {
            assertNull(TransitionRegistry.resolve(BagStatus.SCRAPPED, event),
                    "报废状态不应有任何出边，命中的事件：" + event);
        }

        for (BagStatus status : BagStatus.values()) {
            assertEquals(status == BagStatus.IDLE, status.countsAsAvailable(),
                    "站点可用库存的权威口径是「该站点 IDLE 袋数」，只有空闲计入：" + status);
        }
    }

    @Test
    @DisplayName("空入参不抛异常（避免调用方在边界上 500）")
    void nullArgumentsAreTolerated() {
        assertNull(TransitionRegistry.lookup(null, BagEvent.BORROW));
        assertNull(TransitionRegistry.lookup(BagStatus.IDLE, null));
        assertNull(TransitionRegistry.resolve(null, null));
    }

    @Test
    @DisplayName("枚举码值与中文名：与 DDL 注释、设计书 3.1/3.6 逐项对齐，且 of() 可往返")
    void enumCodesRoundTripWithDisplayNames() {
        for (BagStatus status : BagStatus.values()) {
            assertEquals(status, BagStatus.of(status.code()), "BagStatus 往返失败：" + status);
            assertFalse(status.displayName().isBlank(), "缺少中文名：" + status);
        }
        for (BagEvent event : BagEvent.values()) {
            assertEquals(event, BagEvent.of(event.code()), "BagEvent 往返失败：" + event);
            assertFalse(event.displayName().isBlank(), "缺少中文名：" + event);
        }
        for (OrderStatus status : OrderStatus.values()) {
            assertEquals(status, OrderStatus.of(status.code()), "OrderStatus 往返失败：" + status);
            assertFalse(status.displayName().isBlank(), "缺少中文名：" + status);
        }

        // 设计书 3.1：袋 8 态；3.2：21 个事件（含建档与历史事件）；3.6：订单 6 态
        assertEquals(8, BagStatus.values().length, "袋状态应为 8 态");
        assertEquals(21, BagEvent.values().length, "触发事件应为 20 个");
        assertEquals(6, OrderStatus.values().length, "订单状态应为 6 态");

        // 未登记的码值一律抛异常而不是静默返回 null：
        // 未知状态码意味着数据损坏或口径漂移，必须立刻暴露，不能把故障带到更远的分支上
        assertThrows(IllegalArgumentException.class, () -> BagStatus.of("NO_SUCH_STATUS"));
        assertThrows(IllegalArgumentException.class, () -> BagEvent.of("NO_SUCH_EVENT"));
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.of("NO_SUCH_STATUS"));
        // 空值同样视为调用方错误（Controller 层的参数校验应更早拦住）
        assertThrows(IllegalArgumentException.class, () -> BagStatus.of(null));
        assertThrows(IllegalArgumentException.class, () -> BagStatus.of(" "));
        assertThrows(IllegalArgumentException.class, () -> BagEvent.of(null));
        assertThrows(IllegalArgumentException.class, () -> BagEvent.of("  "));
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.of(null));
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.of(" "));
    }

    @Test
    @DisplayName("all() 返回顺序即设计书 3.2 表序，供接口文档与前端时间线复用")
    void allPreservesDesignDocumentOrder() {
        List<TransitionRule> rules = TransitionRegistry.all();
        assertSame(BagStatus.IDLE, rules.get(0).from(), "第一条应为空闲申领");
        assertSame(BagEvent.BORROW, rules.get(0).event());
        TransitionRule last = rules.get(rules.size() - 1);
        assertSame(BagStatus.LOST, last.from(), "最后一条应为 #20 已遗失找回");
        assertSame(BagEvent.FOUND, last.event());
    }

    @Test
    @DisplayName("注册表拒绝重复的状态与事件组合")
    void duplicateRegistrationFailsWithoutReplacingOriginalRule() throws Exception {
        Method register = TransitionRegistry.class.getDeclaredMethod("register", BagStatus.class,
                BagEvent.class, BagStatus.class, String.class);
        register.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> register.invoke(null, BagStatus.IDLE, BagEvent.BORROW, BagStatus.IDLE, "duplicate"));
        assertTrue(thrown.getCause() instanceof IllegalStateException);
        assertSame(BagStatus.ALLOCATED, TransitionRegistry.resolve(BagStatus.IDLE, BagEvent.BORROW));
    }
}
