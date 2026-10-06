package com.sems.common;

import java.util.HashMap;
import java.util.Map;

/**
 * 全站错误码。
 *
 * <p>唯一权威定义见《系统设计说明书》6.4「错误码表（核心）」；
 * 本枚举额外承载 6.6 的「错误码 → HTTP 状态唯一映射」与「是否可重试」两个属性，
 * 使映射表在代码中有单一出处（对照测试见 {@code ErrorCodeMappingTest}）。
 *
 * <p>约定：业务错误码一律以 HTTP 200 返回、错误码放在响应体 {@code code} 字段；
 * 只有 1001/2001/2002/3001/5102/5103/5000 使用非 200 的 HTTP 状态。
 * 改动本枚举必须同步修改设计书 6.4/6.6。
 */
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, "success", 200, false),

    /** 未认证：token 无效、过期或已吊销（jti 黑名单、token_version 不匹配） */
    UNAUTHENTICATED(1001, "未认证", 401, false),
    /** 账号已锁定（登录连续失败） */
    ACCOUNT_LOCKED(1002, "账号已锁定", 200, false),

    /** 无权限：角色不匹配（垂直越权拦截） */
    FORBIDDEN(2001, "无权限", 403, false),
    /** 无数据权限：操作非本人/非本站资源（水平越权拦截） */
    NO_DATA_PERMISSION(2002, "无数据权限", 403, false),

    /** 参数校验失败：缺字段、格式错误 */
    PARAM_INVALID(3001, "参数校验失败", 400, false),

    /** 站点库存不足（并发取袋余量耗尽） */
    STOCK_INSUFFICIENT(4101, "站点库存不足", 200, false),
    /** 快递袋状态非法流转：状态机拒绝的未注册跳变一律此码 */
    BAG_ILLEGAL_TRANSITION(4102, "快递袋状态非法流转", 200, false),
    /** 重复申领（并发竞争，同一只袋已被领取） */
    DUPLICATE_BORROW(4103, "重复申领", 200, false),
    /** 在持袋数超限：用户绑定的袋处于 {已分配, 使用中, 回收在途} 的订单数 &gt; 3 */
    HOLDING_LIMIT(4104, "在持袋数超限", 200, false),
    /** 订单/单据状态不允许该操作（非状态机管辖；袋状态机拒绝归 4102） */
    ORDER_STATE_NOT_ALLOWED(4105, "订单/单据状态不允许该操作", 200, false),
    /** 袋码不存在（扫码识别失败） */
    BAG_NOT_FOUND(4106, "袋码不存在", 200, false),
    /** 袋未激活或当前不可用（扫码命中未激活/报废/遗失袋） */
    BAG_UNAVAILABLE(4107, "袋未激活或当前不可用", 200, false),
    /** 复用次数超限，须转维修/报废 */
    USE_COUNT_EXCEEDED(4108, "复用次数超限", 200, false),
    /** 取袋频次超限（当日申领 &gt; 10 次） */
    BORROW_DAILY_LIMIT(4109, "取袋频次超限", 200, false),
    /** 袋已被未出库调拨单锁定（申领/转维修/报废/状态修正命中） */
    BAG_LOCKED_BY_ALLOCATION(4110, "袋已被调拨单锁定", 200, false),

    /** 限流触发（敏感接口超频） */
    RATE_LIMITED(5101, "限流触发", 200, false),
    /** 库存计数未就绪，可重试（站点计数初始化中） */
    STOCK_NOT_READY(5102, "库存计数未就绪，请重试", 503, true),
    /** 库存对账中，可重试（对账互斥标记持有期内，指数退避重试耗尽后返回） */
    STOCK_RECONCILING(5103, "库存对账中，请重试", 503, true),

    /** 系统内部错误（兜底，不向前端暴露堆栈） */
    SYSTEM_ERROR(5000, "系统内部错误", 500, false);

    private static final Map<Integer, ErrorCode> INDEX = new HashMap<>();

    static {
        for (ErrorCode e : values()) {
            INDEX.put(e.code, e);
        }
    }

    /** 错误码（响应体 code 字段） */
    private final int code;
    /** 默认提示文案（可被业务异常覆写） */
    private final String message;
    /** 对应的 HTTP 状态码（见设计书 6.6 映射表） */
    private final int httpStatus;
    /** 是否建议前端提供「重试」（为 true 时响应带 Retry-After 头） */
    private final boolean retryable;

    ErrorCode(int code, String message, int httpStatus, boolean retryable) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** 按错误码查枚举；未登记的码返回 {@link #SYSTEM_ERROR}。 */
    public static ErrorCode of(int code) {
        ErrorCode e = INDEX.get(code);
        return e == null ? SYSTEM_ERROR : e;
    }
}
