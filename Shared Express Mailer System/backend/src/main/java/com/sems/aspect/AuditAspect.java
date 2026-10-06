package com.sems.aspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.common.util.MaskUtils;
import com.sems.module.audit.entity.AuditLogEntity;
import com.sems.module.audit.service.AuditLogService;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 审计切面：环绕被 {@link AuditLog} 标注的方法，采集审计要素后交
 * {@link AuditLogService} 异步落库（见《系统设计说明书》7.6）。
 *
 * <p>两条不可动摇的纪律：
 * <ul>
 *   <li><b>异常原样抛出</b>：审计只观测、不干预业务语义，失败的业务分支仍按原错误码上抛；</li>
 *   <li><b>审计不得影响业务</b>：采集或提交阶段任何异常都被吞掉并记 ERROR，
 *       不允许因为审计故障让正常请求失败（高峰期尤甚，见 7.6 队列饱和策略）。</li>
 * </ul>
 *
 * <p>入参脱敏：契约要求 password / phone / recipientPhone 替换为 ***；
 * 为覆盖 oldPassword、newPassword、senderPhone 等命名变体，字段名包含
 * password/phone 片段的一并脱敏（只多脱不漏脱，理由见 9.1 敏感信息处置）。
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    /** 契约明确要求脱敏的字段名（7.6） */
    private static final Set<String> MASKED_FIELDS = Set.of("password", "phone", "recipientPhone");

    /** 命名变体加固：字段名（忽略大小写）含以下片段一律脱敏 */
    private static final List<String> MASKED_FIELD_FRAGMENTS = List.of("password", "phone");

    /** 脱敏替换值 */
    private static final String MASK_VALUE = "***";

    /** 与 audit_log 各列宽度一致的上限，超长截断，避免写入失败丢审计 */
    private static final int IP_MAX = 45;
    private static final int UA_MAX = 255;
    private static final int ROLE_MAX = 20;
    private static final int MODULE_MAX = 50;
    private static final int ACTION_MAX = 50;
    private static final int URI_MAX = 255;
    private static final int PARAMS_MAX = 4096;

    /**
     * 脱敏专用序列化器：入参可能含 LocalDateTime（JSR-310），必须注册模块才能序列化；
     * 同时关闭「空 Bean 报错」，让偶发不可序列化对象降级为占位符而不是抛异常。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final AuditLogService auditLogService;

    public AuditAspect(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 环绕通知：成功记 result=1、errorCode=0；业务异常取错误码、未知异常归 5000；
     * 无论成败最后都在 finally 中采集成一条审计。
     */
    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        long startNanos = System.nanoTime();
        int result = 1;
        int errorCode = ErrorCode.SUCCESS.getCode();
        try {
            return pjp.proceed();
        } catch (BizException e) {
            result = 0;
            errorCode = e.getErrorCode().getCode();
            throw e;
        } catch (Throwable t) {
            result = 0;
            errorCode = ErrorCode.SYSTEM_ERROR.getCode();
            throw t;
        } finally {
            try {
                int costMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000L);
                auditLogService.record(buildEntity(pjp, auditLog, result, errorCode, costMs));
            } catch (Exception e) {
                // 审计故障不得影响业务返回（7.6）
                log.error("审计采集失败，已忽略：module={}, action={}", auditLog.module(), auditLog.action(), e);
            }
        }
    }

    /** 组装一条审计记录：操作人取自认证上下文，环境信息取自当前 HTTP 请求。 */
    private AuditLogEntity buildEntity(ProceedingJoinPoint pjp, AuditLog auditLog,
                                       int result, int errorCode, int costMs) {
        AuditLogEntity entity = new AuditLogEntity();

        // 未认证路径（如登录接口失败）AuthContext 为空，按 DDL 语义记 0 与空串
        LoginUser operator = AuthContext.get();
        entity.setOperatorId(operator == null || operator.getUserId() == null ? 0L : operator.getUserId());
        entity.setOperatorRole(truncate(operator == null ? "" : operator.getRole(), ROLE_MAX));

        entity.setModule(truncate(auditLog.module(), MODULE_MAX));
        entity.setAction(truncate(normalizeAction(auditLog.action(), errorCode), ACTION_MAX));
        entity.setResult(result);
        entity.setErrorCode(errorCode);
        entity.setCostMs(costMs);

        HttpServletRequest request = currentRequest();
        if (request == null) {
            // 非 HTTP 线程（如定时任务）触发被标注方法：环境字段留空，审计行仍需成立
            entity.setIp("");
            entity.setUa("");
            entity.setRequestUri("");
        } else {
            entity.setIp(truncate(resolveClientIp(request), IP_MAX));
            entity.setUa(truncate(request.getHeader("User-Agent"), UA_MAX));
            entity.setRequestUri(truncate(request.getRequestURI(), URI_MAX));
        }

        entity.setParamsMasked(auditLog.recordParams() ? maskParams(pjp.getArgs()) : "");
        return entity;
    }

    /**
     * 规范化审计动作（见《系统设计说明书》7.6）。
     *
     * <p>7.6 把「越权拦截（2001/2002）」与「状态机拒绝（4102）」列进必审事件，并要求这些重点核查项
     * <b>可按动作筛出</b>。但这两类事件是**异常**而不是接口——它们发生在某个被 {@code @AuditLog} 标注的
     * 接口内部，若沿用接口自身的动作（如「申领」），审计查询就无法把它们单独拉出来。
     *
     * <p>因此这里按错误码把动作改成规范值；原接口上下文没有丢——同一行的 {@code request_uri}
     * 与 {@code params_masked} 仍然完整保留。
     *
     * <p>注意 2001 有两条来源：{@code AuthzInterceptor}（拦截器层，切面看不到，由它自己投递
     * {@link com.sems.common.event.AuditEvent}）与 Service 层的角色判断（会被本切面接住）——
     * 两条路径最终都以「越权尝试」这个动作落库，查询侧只需筛一个值。
     */
    private static String normalizeAction(String action, int errorCode) {
        if (errorCode == ErrorCode.FORBIDDEN.getCode()
                || errorCode == ErrorCode.NO_DATA_PERMISSION.getCode()) {
            return "越权尝试";
        }
        if (errorCode == ErrorCode.BAG_ILLEGAL_TRANSITION.getCode()) {
            return "状态机拒绝";
        }
        return action;
    }

    /** 当前线程绑定的 HTTP 请求；异步线程或非 Web 上下文返回 null。 */
    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    /**
     * 解析客户端 IP：只信任 X-Forwarded-For <b>最右一段</b>，缺失时回退 remoteAddr。
     *
     * <p><b>为什么不再取最左一段</b>（7.6 / 9.5：审计 IP 是越权追责与「查看完整 IP」的唯一依据，
     * 必须不可伪造）：部署前提是「Nginx 直接反代后端」（见 deploy/nginx/nginx.conf.example），
     * 该配置用 {@code proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;}——它把
     * <b>与 Nginx 直连的对端地址（$remote_addr）追加到 XFF 最右端</b>；而最左段完整保留客户端
     * 自带的头。也就是说：最左段客户端可任意伪造（携带 {@code X-Forwarded-For: <任意IP>} 即可
     * 污染 audit_log / login_log 的 ip 列），最右一段才是我方可信代理写入、无法伪造的地址。
     *
     * <p>部署形态变化时的前提：若将来在最外层再加一层代理（如云 LB），需让外层代理以
     * {@code $remote_addr} 覆写该头、或按实际可信跳数调整这里的取值，否则只能取到 LB 的地址。
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.lastIndexOf(',');
            String last = (comma >= 0 ? forwarded.substring(comma + 1) : forwarded).trim();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * 序列化并脱敏入参：跳过容器对象与文件流，逐参数转 JSON 树后递归替换敏感字段值；
     * 单个参数序列化失败只降级为占位符，绝不影响业务（7.6）。
     */
    private static String maskParams(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        ArrayNode array = MAPPER.createArrayNode();
        for (Object arg : args) {
            if (isExcluded(arg)) {
                continue;
            }
            JsonNode node;
            try {
                node = MAPPER.valueToTree(arg);
            } catch (RuntimeException e) {
                node = MAPPER.getNodeFactory().textNode("[参数不可序列化]");
            }
            maskNode(node);
            array.add(node);
        }
        try {
            return truncate(array.toString(), PARAMS_MAX);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 排除无审计价值且不可序列化的参数：容器对象、上传文件与各类流。 */
    private static boolean isExcluded(Object arg) {
        return arg == null
                || arg instanceof ServletRequest
                || arg instanceof ServletResponse
                || arg instanceof MultipartFile
                || arg instanceof InputStream
                || arg instanceof OutputStream
                || arg instanceof Reader
                || arg instanceof Writer
                || arg instanceof BindingResult;
    }

    /** 递归脱敏 JSON 树：对象命中敏感字段整体替换，数组逐元素下钻。 */
    private static void maskNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            object.fieldNames().forEachRemaining(fieldNames::add);
            for (String name : fieldNames) {
                if (isSensitiveField(name)) {
                    object.put(name, MASK_VALUE);
                } else {
                    maskNode(object.get(name));
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                maskNode(item);
            }
        }
    }

    /** 契约字段名精确匹配，或名字含 password/phone 片段（命名变体加固）。 */
    private static boolean isSensitiveField(String name) {
        if (name == null) {
            return false;
        }
        if (MASKED_FIELDS.contains(name)) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String fragment : MASKED_FIELD_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** 超长按列宽截断；null 归一为空串，避免写库报错。 */
    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return MaskUtils.truncate(value, max);
    }
}
