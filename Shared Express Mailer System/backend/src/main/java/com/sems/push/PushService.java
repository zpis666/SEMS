package com.sems.push;

import com.sems.common.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 业务推送出口（见《系统设计说明书》7.3）。
 *
 * <p>只暴露四个主题的推送方法，主题名与授权规则严格对应 7.3 的主题表：
 * <ul>
 *   <li>{@code station/{id}/stock}：站点可用库存增减；</li>
 *   <li>{@code station/{id}/recycle}：站点回收待办（用户标记归还提醒）；</li>
 *   <li>{@code user/{uid}/order}：本人订单状态变更与超时提醒；</li>
 *   <li>{@code platform/dashboard}：平台看板全局异动。</li>
 * </ul>
 *
 * <p>两条纪律：
 * <ul>
 *   <li><b>推送失败不影响主业务</b>：本类所有方法吞掉异常并记 WARN；</li>
 *   <li><b>事务提交后才推送</b>：写事务内的推送一律用 {@link #afterCommit(Runnable)} 包装，
 *       避免「事务回滚但消息已发」（7.3）。</li>
 * </ul>
 *
 * <p><b>平台看板联动</b>：三个业务推送方法在广播后同时向 {@code platform/dashboard} 附带一条
 * 看板异动消息——7.3 该主题的推送时机是「全局库存/订单异动、预警触发」，站点库存、回收待办、
 * 用户订单三类消息正好覆盖库存与订单异动的全部来源，因此不必在各业务写入点逐一接线（也就不会
 * 让模块间出现新的依赖）。前端看板对命中消息合并去抖（800ms），批量操作不会造成刷新风暴。
 */
@Component
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final WsSessionManager sessionManager;

    public PushService(WsSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /** 站点库存变更推送（topic station/{id}/stock）。 */
    public void pushStationStock(Long stationId, Object data) {
        push("station/" + stationId + "/stock", WsMessage.PUSH_STOCK, data);
        // 全局库存异动：平台看板同步刷新（7.3 platform/dashboard 推送时机）
        pushPlatformDashboard(data);
    }

    /** 站点回收待办推送（topic station/{id}/recycle；用户标记归还时提醒站点确认）。 */
    public void pushStationRecycle(Long stationId, Object data) {
        push("station/" + stationId + "/recycle", WsMessage.PUSH_RECYCLE, data);
        // 回收待办会改变归还口径的看板指标：平台看板同步刷新
        pushPlatformDashboard(data);
    }

    /** 用户订单变更推送（topic user/{uid}/order；仅 uid 本人可订阅）。 */
    public void pushUserOrder(Long userId, Object data) {
        push("user/" + userId + "/order", WsMessage.PUSH_ORDER, data);
        // 全局订单异动：平台看板同步刷新（7.3 platform/dashboard 推送时机）
        pushPlatformDashboard(data);
    }

    /**
     * 平台看板异动推送（topic platform/dashboard；仅平台管理员可订阅）。
     *
     * <p>由三个业务推送方法在各自广播后附带调用（见类注释），是 7.3 该主题「全局库存/订单异动」
     * 推送时机的落点；订阅方（前端看板）命中即静默重拉，消息体内容不参与判定。
     */
    public void pushPlatformDashboard(Object data) {
        push(WsSessionManager.TOPIC_PLATFORM_DASHBOARD, WsMessage.PUSH_DASHBOARD, data);
    }

    /**
     * 事务提交后回调（7.3）：当前线程存在真实事务时注册 afterCommit 回调，
     * 否则直接执行——保证推送时序在数据可见之后。
     *
     * <p>回调内异常只记 ERROR，不允许影响已提交事务的后续流程。
     */
    public void afterCommit(Runnable action) {
        if (action == null) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runQuietly(action);
                }
            });
        } else {
            runQuietly(action);
        }
    }

    private void push(String topic, String type, Object data) {
        try {
            sessionManager.broadcast(topic, JsonUtils.toJson(WsMessage.of(topic, type, data)));
        } catch (Exception e) {
            // 推送是旁路能力：失败只记日志，不拖垮业务主链路（7.3）
            log.warn("WebSocket 推送失败：topic={}, reason={}", topic, e.getMessage());
        }
    }

    private void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("事务提交后回调执行失败", e);
        }
    }
}
