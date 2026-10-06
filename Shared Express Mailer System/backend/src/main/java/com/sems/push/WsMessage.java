package com.sems.push;

/**
 * WebSocket 消息体，统一格式为 {@code {"topic":"...","type":"...","data":{...},"ts":...}}
 * （见《系统设计说明书》7.3）。
 *
 * <p>{@code ts} 为服务端毫秒时间戳，供前端做乱序与心跳判定；
 * {@code data} 允许为任意可 JSON 序列化的业务对象，控制类帧（ping/auth_ok 等）为 null。
 *
 * <p>type 取值约定（本类常量，前后端共用）：
 * <ul>
 *   <li>客户端发往服务端：{@code auth}（首帧鉴权）、{@code subscribe}/{@code unsubscribe}、{@code ping}/{@code pong}；</li>
 *   <li>服务端发往客户端：{@code auth_ok}/{@code auth_fail}、{@code subscribed}/{@code unsubscribed}、
 *       {@code error}、心跳 {@code ping}/{@code pong}；</li>
 *   <li>业务推送 type 取主题末段：{@code stock}/{@code recycle}/{@code order}/{@code dashboard}，
 *       前端以 topic 分发、type 辅助判别。</li>
 * </ul>
 *
 * <p>本类为消息模型（非实体/DTO），按规约手写 getter/setter。
 */
public class WsMessage {

    /** 客户端首帧鉴权（设计书 7.3 固定帧：{"type":"auth","token":"<JWT>"}） */
    public static final String TYPE_AUTH = "auth";
    /** 订阅主题 */
    public static final String TYPE_SUBSCRIBE = "subscribe";
    /** 取消订阅 */
    public static final String TYPE_UNSUBSCRIBE = "unsubscribe";
    /** 鉴权通过 */
    public static final String TYPE_AUTH_OK = "auth_ok";
    /** 鉴权失败（随后断开） */
    public static final String TYPE_AUTH_FAIL = "auth_fail";
    /** 订阅成功 */
    public static final String TYPE_SUBSCRIBED = "subscribed";
    /** 取消订阅成功 */
    public static final String TYPE_UNSUBSCRIBED = "unsubscribed";
    /** 协议错误提示（不触发断开） */
    public static final String TYPE_ERROR = "error";
    /** 心跳 ping */
    public static final String TYPE_PING = "ping";
    /** 心跳 pong */
    public static final String TYPE_PONG = "pong";

    /** 推送类型：站点库存变更（topic 末段 stock） */
    public static final String PUSH_STOCK = "stock";
    /** 推送类型：站点回收待办（topic 末段 recycle） */
    public static final String PUSH_RECYCLE = "recycle";
    /** 推送类型：用户订单变更（topic 末段 order） */
    public static final String PUSH_ORDER = "order";
    /** 推送类型：平台看板异动（topic 末段 dashboard） */
    public static final String PUSH_DASHBOARD = "dashboard";

    /** 主题/订阅对象（控制类帧可为 null） */
    private String topic;

    /** 消息类型（见本类常量） */
    private String type;

    /** 业务数据 */
    private Object data;

    /** 服务端时间戳（毫秒） */
    private long ts;

    public WsMessage() {
    }

    public WsMessage(String topic, String type, Object data) {
        this(topic, type, data, System.currentTimeMillis());
    }

    public WsMessage(String topic, String type, Object data, long ts) {
        this.topic = topic;
        this.type = type;
        this.data = data;
        this.ts = ts;
    }

    /** 构造消息并自动打上当前时间戳。 */
    public static WsMessage of(String topic, String type, Object data) {
        return new WsMessage(topic, type, data);
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public long getTs() {
        return ts;
    }

    public void setTs(long ts) {
        this.ts = ts;
    }
}
