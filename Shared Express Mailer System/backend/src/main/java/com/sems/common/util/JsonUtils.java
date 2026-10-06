package com.sems.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * JSON 工具（Redis 幂等结果、库存预扣凭据、推送消息等的统一序列化口径，见设计书 7.2 / 9.6）。
 *
 * <p>为什么持有独立的静态 {@link ObjectMapper}：本工具会在静态上下文（工具方法、非托管对象）
 * 中被调用，拿不到 Spring 容器里配置的 ObjectMapper。这里显式对齐 Spring MVC 的默认行为——
 * 注册 JavaTimeModule（{@code LocalDateTime} 序列化为 ISO-8601 字符串而非时间戳数组）、
 * 关闭未知字段报错（新旧版本间缓存可互读；与 Boot 的默认配置一致）。
 *
 * <p>异常处理：序列化/反序列化失败属数据契约或程序错误，一律包装为
 * {@link BizException}（{@link ErrorCode#SYSTEM_ERROR}），<b>对外只暴露默认文案</b>；
 * 详细原因记 ERROR 日志，避免把内部结构信息回给前端（9.1 信息泄露对策）。
 *
 * <p>本类无状态，线程安全（{@code ObjectMapper} 配置完成后只读，可安全共享）。
 */
public final class JsonUtils {

    private static final Logger log = LoggerFactory.getLogger(JsonUtils.class);

    /** 统一 JSON 编解码器：注册 JSR-310 时间模块，其余保持 Boot 默认口径。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private JsonUtils() {
    }

    /**
     * 序列化为 JSON 字符串。
     *
     * <p>{@code null} 序列化为字面量 {@code "null"}（与 Jackson 原生行为一致）：
     * 幂等缓存据此区分"未执行"（键不存在返回 null）与"已执行但无数据"。
     */
    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("JSON 序列化失败：{}", e.getOriginalMessage(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
    }

    /**
     * 反序列化为指定类型；null/空串返回 null（键不存在或空值时调用方可直接走未命中分支）。
     */
    public static <T> T parse(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.error("JSON 反序列化失败：{}", e.getOriginalMessage(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
    }

    /**
     * 反序列化为 {@code List<T>}，供逐袋明细、袋码清单等数组/集合字段使用；null/空串返回 null。
     */
    public static <T> List<T> parseList(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(List.class, type));
        } catch (JsonProcessingException e) {
            log.error("JSON 反序列化列表失败：{}", e.getOriginalMessage(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
    }
}
