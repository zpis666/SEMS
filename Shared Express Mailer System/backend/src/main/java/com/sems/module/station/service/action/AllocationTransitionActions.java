package com.sems.module.station.service.action;

import com.sems.infra.StockService;
import com.sems.module.statemachine.BagEvent;
import com.sems.module.statemachine.BagTransitionContext;
import com.sems.module.statemachine.TransitionAction;
import com.sems.push.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调拨类状态流转伴随动作：ALLOCATE_OUT（#16 调拨出库）、ALLOCATE_IN（#17 调拨入库）、
 * LOST_IN_TRANSIT（#19 在途遗失确认）
 * （见《系统设计说明书》3.2 状态转移表「伴随动作」列、3.4.3 调拨流程、3.5 计数口径表；
 * 事件归属见《后端实现契约（业务模块版）》第 2 节）。
 *
 * <p><b>为什么每事件一个静态内部类</b>：状态机按 {@code TransitionAction.event()} 把动作注册进
 * 事件表，同一事件只允许一个实现 Bean（重复注册启动即失败），单个类的一个 {@code event()} 方法
 * 无法覆盖三个事件。外层类只作模块归属标注；真正注册进状态机的是三个静态内部类 Bean
 * （组件扫描认可静态嵌套类，非静态内部类不会被扫描）。
 *
 * <p><b>职责边界</b>（契约第 2 节）：状态机已完成「袋状态条件 UPDATE + 写流转日志」，
 * 本类只做「Redis 库存增减 + 推送」——禁止在本类改袋状态、禁止新开事务；
 * 推送一律经 {@link PushService#afterCommit}（事务回滚则消息作废，见 7.3）。
 * 注意：#16/#17 的可用库存推送由状态机按前后态统一登记（空闲→调拨在途 / 调拨在途→空闲），
 * 本类<b>不得</b>再推一次，否则同一笔增减会向站点连发两条方向相同的消息（7.3 推送时机）。
 *
 * <p><b>库存口径</b>（设计书 3.5 计数口径表）：
 * <ul>
 *   <li>调拨出库：源站可用库存 −1；</li>
 *   <li>调拨入库：目标站可用库存 +1；</li>
 *   <li>在途遗失：两站库存均不变（袋在途本就未计入任何站点可用库存，遗失后仍不可用），
 *       仅推送两站库存视图，供站点/看板刷新。</li>
 * </ul>
 */
@Component
public class AllocationTransitionActions {

    /** 流转命令扩展参数键：调拨单ID（AllocationService 写入、本包动作读取）。 */
    public static final String ATTR_ALLOCATION_ID = "allocationId";

    /** 流转命令扩展参数键：调拨单号（推送载荷携带，便于前端定位单据）。 */
    public static final String ATTR_ALLOCATION_NO = "allocationNo";

    /** 流转命令扩展参数键：源站点ID（在途遗失需推送两站，仅凭命令站号不足以表达）。 */
    public static final String ATTR_FROM_STATION_ID = "fromStationId";

    /** 流转命令扩展参数键：目标站点ID。 */
    public static final String ATTR_TO_STATION_ID = "toStationId";

    private static final Logger log = LoggerFactory.getLogger(AllocationTransitionActions.class);

    /**
     * #16 调拨出库：源站可用库存 −1（空闲→调拨在途）。
     *
     * <p>命令站号（{@code stationId}）即源站——出库发生在源站；站号缺失属调用方数据异常，
     * 跳过库存增减但不抛异常（袋状态已由状态机落库，库存口径由对账纠正）。
     *
     * <p>可用库存推送由状态机按前后态统一登记（空闲→调拨在途 ⇒ 源站 −1），本类不再推送：
     * 状态机与伴随动作各推一次会让站点对同一笔增减连收两条消息（7.3 推送时机）。
     */
    @Component
    public static class AllocateOutAction implements TransitionAction {

        private final StockService stockService;

        public AllocateOutAction(StockService stockService) {
            this.stockService = stockService;
        }

        @Override
        public BagEvent event() {
            return BagEvent.ALLOCATE_OUT;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            Long fromStationId = ctx.getCmd().getStationId();
            if (fromStationId == null) {
                log.warn("调拨出库缺少源站信息，跳过库存增减：bagCode={}", ctx.getCmd().getBagCode());
                return;
            }
            // #16：袋离开源站可用库存（离库时点扣减；核收失败由 R4 对账与异常处置兜底）
            stockService.increaseAvailable(fromStationId, -1);
        }
    }

    /**
     * #17 调拨入库：目标站可用库存 +1（袋归属改目标站由状态机完成；调拨在途→空闲）。
     *
     * <p>可用库存推送由状态机按前后态统一登记（调拨在途→空闲 ⇒ 目标站 +1），本类不再推送（同 #16）。
     */
    @Component
    public static class AllocateInAction implements TransitionAction {

        private final StockService stockService;

        public AllocateInAction(StockService stockService) {
            this.stockService = stockService;
        }

        @Override
        public BagEvent event() {
            return BagEvent.ALLOCATE_IN;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            Long toStationId = resolveTargetStationId(ctx);
            if (toStationId == null) {
                log.warn("调拨入库缺少目标站信息，跳过库存增减：bagCode={}", ctx.getCmd().getBagCode());
                return;
            }
            // #17：袋进入目标站可用库存
            stockService.increaseAvailable(toStationId, 1);
        }
    }

    /**
     * #19 在途遗失确认：库存不变，仅推送源站与目标站库存视图。
     *
     * <p>为什么库存不变：在途袋在出库时已从源站扣减、尚未进入目标站，本就不计入任一站点可用库存，
     * 置为已遗失后同样不计入（设计书 3.5 计数口径表）。
     */
    @Component
    public static class LostInTransitAction implements TransitionAction {

        private final PushService pushService;

        public LostInTransitAction(PushService pushService) {
            this.pushService = pushService;
        }

        @Override
        public BagEvent event() {
            return BagEvent.LOST_IN_TRANSIT;
        }

        @Override
        public void apply(BagTransitionContext ctx) {
            Long fromStationId = attributeLong(ctx, ATTR_FROM_STATION_ID);
            if (fromStationId == null) {
                // 命令站号按约定取源站（责任主体），作为缺省兜底
                fromStationId = ctx.getCmd().getStationId();
            }
            Long toStationId = attributeLong(ctx, ATTR_TO_STATION_ID);

            List<Runnable> pushes = new ArrayList<>(2);
            if (fromStationId != null) {
                Long sourceId = fromStationId;
                Map<String, Object> data = stockData(ctx, sourceId, 0);
                pushes.add(() -> pushService.pushStationStock(sourceId, data));
            }
            if (toStationId != null && !toStationId.equals(fromStationId)) {
                Long targetId = toStationId;
                Map<String, Object> data = stockData(ctx, targetId, 0);
                pushes.add(() -> pushService.pushStationStock(targetId, data));
            }
            if (!pushes.isEmpty()) {
                pushService.afterCommit(() -> pushes.forEach(Runnable::run));
            }
        }
    }

    // ==================== 私有工具（三个动作共享） ====================

    /**
     * 解析「袋实际归属站点」：与状态机对站点变更事件的口径保持一致
     * （{@code targetStationId} 优先、缺省 {@code stationId}，最后回退命令扩展参数），
     * 避免动作侧与状态机落库的站号不一致。
     */
    private static Long resolveTargetStationId(BagTransitionContext ctx) {
        if (ctx.getCmd().getTargetStationId() != null) {
            return ctx.getCmd().getTargetStationId();
        }
        if (ctx.getCmd().getStationId() != null) {
            return ctx.getCmd().getStationId();
        }
        return attributeLong(ctx, ATTR_TO_STATION_ID);
    }

    /** 组装库存推送载荷（携带调拨单上下文，前端可据此刷新调拨进度）。 */
    private static Map<String, Object> stockData(BagTransitionContext ctx, Long stationId, int delta) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bagCode", ctx.getCmd().getBagCode());
        data.put("event", ctx.getCmd().getEvent().code());
        data.put("stationId", stationId);
        data.put("stockDelta", delta);
        data.put("allocationId", attributeLong(ctx, ATTR_ALLOCATION_ID));
        data.put("allocationNo", attributeString(ctx, ATTR_ALLOCATION_NO));
        return data;
    }

    /** 取命令扩展参数中的 Long（兼容 JSON/Map 反序列化后的 Number 形态）。 */
    private static Long attributeLong(BagTransitionContext ctx, String key) {
        Object value = attribute(ctx, key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    /** 取命令扩展参数中的字符串。 */
    private static String attributeString(BagTransitionContext ctx, String key) {
        Object value = attribute(ctx, key);
        return value == null ? null : String.valueOf(value);
    }

    /** 取命令扩展参数（attributes 可空）。 */
    private static Object attribute(BagTransitionContext ctx, String key) {
        Map<String, Object> attributes = ctx.getCmd().getAttributes();
        return attributes == null ? null : attributes.get(key);
    }
}
