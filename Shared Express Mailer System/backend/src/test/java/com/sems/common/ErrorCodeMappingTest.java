package com.sems.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误码与「错误码 → HTTP 状态唯一映射」的对照测试。
 *
 * <p>本测试把《系统设计说明书》6.4（错误码表）与 6.6（映射表）固化下来：
 * <b>改代码必须同步改文档，改文档也必须同步改这里</b>——两处不一致时本测试会失败，
 * 这正是接口测试断言的前置保证。
 *
 * <p>不依赖 Spring 上下文，无需容器即可执行。
 */
class ErrorCodeMappingTest {

    @Test
    @DisplayName("6.6 映射表：只有 1001/2001/2002/3001/5102/5103/5000 使用非 200 的 HTTP 状态")
    void httpStatusMappingMatchesSpec() {
        assertEquals(401, ErrorCode.UNAUTHENTICATED.getHttpStatus(), "1001 未认证 → 401");
        assertEquals(403, ErrorCode.FORBIDDEN.getHttpStatus(), "2001 无权限 → 403");
        assertEquals(403, ErrorCode.NO_DATA_PERMISSION.getHttpStatus(), "2002 无数据权限 → 403");
        assertEquals(400, ErrorCode.PARAM_INVALID.getHttpStatus(), "3001 参数校验失败 → 400");
        assertEquals(503, ErrorCode.STOCK_NOT_READY.getHttpStatus(), "5102 计数未就绪 → 503");
        assertEquals(503, ErrorCode.STOCK_RECONCILING.getHttpStatus(), "5103 对账中 → 503");
        assertEquals(500, ErrorCode.SYSTEM_ERROR.getHttpStatus(), "5000 系统错误 → 500");

        // 其余业务码一律 200，错误码放在响应体 code 字段
        ErrorCode[] businessCodes = {
                ErrorCode.ACCOUNT_LOCKED,
                ErrorCode.STOCK_INSUFFICIENT, ErrorCode.BAG_ILLEGAL_TRANSITION,
                ErrorCode.DUPLICATE_BORROW, ErrorCode.HOLDING_LIMIT,
                ErrorCode.ORDER_STATE_NOT_ALLOWED, ErrorCode.BAG_NOT_FOUND,
                ErrorCode.BAG_UNAVAILABLE, ErrorCode.USE_COUNT_EXCEEDED,
                ErrorCode.BORROW_DAILY_LIMIT, ErrorCode.BAG_LOCKED_BY_ALLOCATION,
                ErrorCode.RATE_LIMITED
        };
        for (ErrorCode code : businessCodes) {
            assertEquals(200, code.getHttpStatus(),
                    code + "(" + code.getCode() + ") 应走 HTTP 200 + 业务错误码");
        }
    }

    @Test
    @DisplayName("可重试语义只属于 5102/5103（响应带 Retry-After）")
    void retryableOnlyForStockNotReadyAndReconciling() {
        assertTrue(ErrorCode.STOCK_NOT_READY.isRetryable());
        assertTrue(ErrorCode.STOCK_RECONCILING.isRetryable());
        for (ErrorCode code : ErrorCode.values()) {
            if (code == ErrorCode.STOCK_NOT_READY || code == ErrorCode.STOCK_RECONCILING) {
                continue;
            }
            assertFalse(code.isRetryable(), code + " 不应标记为可重试");
        }
    }

    @Test
    @DisplayName("错误码取值唯一，且 6.4 的核心码一个不少")
    void codesAreUniqueAndComplete() {
        Set<Integer> seen = new HashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(seen.add(code.getCode()), "错误码重复：" + code.getCode());
        }
        int[] required = {0, 1001, 1002, 2001, 2002, 3001,
                4101, 4102, 4103, 4104, 4105, 4106, 4107, 4108, 4109, 4110,
                5101, 5102, 5103, 5000};
        for (int c : required) {
            assertTrue(seen.contains(c), "6.4 定义的错误码缺失：" + c);
        }
    }

    @Test
    @DisplayName("未登记的码按系统错误兜底，不抛异常")
    void unknownCodeFallsBackToSystemError() {
        assertSame(ErrorCode.SYSTEM_ERROR, ErrorCode.of(9999));
        assertSame(ErrorCode.BAG_NOT_FOUND, ErrorCode.of(4106));
    }
}
