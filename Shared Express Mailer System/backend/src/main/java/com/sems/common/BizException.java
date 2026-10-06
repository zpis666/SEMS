package com.sems.common;

/**
 * 业务异常：携带 {@link ErrorCode}，由 {@link GlobalExceptionHandler} 统一转换。
 *
 * <p>使用约定（见《系统设计说明书》6.6）：
 * <ul>
 *   <li>袋状态机拒绝的未注册跳变一律 {@link ErrorCode#BAG_ILLEGAL_TRANSITION}（4102）；</li>
 *   <li>订单/单据类状态不允许用 {@link ErrorCode#ORDER_STATE_NOT_ALLOWED}（4105）；</li>
 *   <li>越权（角色不匹配）用 2001、数据归属不符用 2002，且先判资源存在性（4106）再判归属。</li>
 * </ul>
 *
 * <p>本类继承 {@link RuntimeException}（非受检），以免污染各层方法签名；
 * 业务异常不打印堆栈（无诊断价值），由全局处理器按 WARN/INFO 记录。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** 业务异常不需要堆栈，省去填充开销。 */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
