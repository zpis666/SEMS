package com.sems.unit.common;

import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.common.util.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilsTest {

    @Test
    @DisplayName("JSON 时间使用 ISO 字符串且未知属性兼容新旧缓存结构")
    void serializesJavaTimeAndIgnoresUnknownProperties() {
        TimedValue value = new TimedValue();
        value.createdAt = LocalDateTime.of(2026, 10, 7, 12, 34, 56);
        String json = JsonUtils.toJson(value);
        assertTrue(json.contains("\"createdAt\":\"2026-10-07T12:34:56\""));

        TimedValue parsed = JsonUtils.parse(
                "{\"createdAt\":\"2026-10-07T12:34:56\",\"futureField\":true}", TimedValue.class);
        assertEquals(value.createdAt, parsed.createdAt);
    }

    @Test
    @DisplayName("null 与空串为未命中；数组缓存可以反序列化为具体元素类型")
    void handlesEmptyAndTypedList() {
        assertNull(JsonUtils.parse(null, String.class));
        assertNull(JsonUtils.parse("", String.class));
        assertNull(JsonUtils.parseList(null, Item.class));
        assertEquals(List.of(new Item("A"), new Item("B")),
                JsonUtils.parseList("[{\"code\":\"A\"},{\"code\":\"B\"}]", Item.class));
        assertEquals("null", JsonUtils.toJson(null));
    }

    @Test
    @DisplayName("畸形 JSON 统一转为系统错误且不暴露解析细节")
    void malformedJsonIsWrappedAsSafeSystemError() {
        BizException single = assertThrows(BizException.class,
                () -> JsonUtils.parse("{broken", Item.class));
        assertEquals(ErrorCode.SYSTEM_ERROR, single.getErrorCode());
        assertEquals(ErrorCode.SYSTEM_ERROR.getMessage(), single.getMessage());

        BizException list = assertThrows(BizException.class,
                () -> JsonUtils.parseList("[", Item.class));
        assertEquals(ErrorCode.SYSTEM_ERROR, list.getErrorCode());
        assertEquals(ErrorCode.SYSTEM_ERROR.getMessage(), list.getMessage());
    }

    public static class TimedValue {
        public LocalDateTime createdAt;
    }

    public record Item(String code) {
    }
}
