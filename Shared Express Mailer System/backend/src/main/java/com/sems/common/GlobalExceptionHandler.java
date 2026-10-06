package com.sems.common;

import com.sems.aspect.AuditLog;
import com.sems.common.event.AuditEvent;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器：把所有异常转换为 {@link Result} 统一格式（见《系统设计说明书》6.6）。
 *
 * <p>处理链与响应口径：
 * <ul>
 *   <li>参数校验失败 → 400 + 3001 + 字段错误明细（WARN，<b>不记录参数值</b>）；</li>
 *   <li>业务异常 → 按 {@link ErrorCode} 携带的 HTTP 状态 + 业务错误码（INFO）；
 *       其中无权限（403 + 2001/2002）按 6.6 记 WARN 并补投越权尝试审计（7.6，见
 *       {@link #publishForbiddenAudit}）；</li>
 *   <li>系统异常兜底 → 500 + 5000，全量堆栈记 ERROR，<b>不向前端暴露堆栈</b>。</li>
 * </ul>
 *
 * <p>未认证（401 + 1001）与无权限（403 + 2001/2002）由拦截器或 Service 归属校验抛出，
 * 异常最终都汇聚到本处理器成响应；越权尝试的 WARN 与审计兜底见 {@link #publishForbiddenAudit}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 可重试响应建议的等待秒数（配合 5102/5103，见 6.6）。 */
    private static final String RETRY_AFTER_SECONDS = "1";

    /** 越权尝试的规范化审计动作（7.6：越权拦截必须入审计且可按动作筛出）。 */
    private static final String ACTION_FORBIDDEN_ATTEMPT = "越权尝试";

    /**
     * 关键事件审计投递器（7.6）：发布方只依赖 common 包的 {@link AuditEvent}，
     * 订阅端在 module/audit 的 {@code AuditLogService}，依赖方向不反向。
     */
    private final ApplicationEventPublisher eventPublisher;

    public GlobalExceptionHandler(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** 业务异常：正常业务分支，不记堆栈。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException ex) {
        ErrorCode code = ex.getErrorCode();
        if (code == ErrorCode.FORBIDDEN || code == ErrorCode.NO_DATA_PERMISSION) {
            // 6.6：无权限（403）记 WARN + 审计（越权尝试）；7.6：越权拦截（2001/2002）必须入审计
            HttpServletRequest request = currentRequest();
            log.warn("越权尝试：code={}, message={}, uri={}", code.getCode(), ex.getMessage(),
                    request == null ? "-" : request.getRequestURI());
            publishForbiddenAudit(ex, code, request);
        } else if (code.getHttpStatus() >= 500 || code == ErrorCode.STOCK_NOT_READY
                || code == ErrorCode.STOCK_RECONCILING) {
            log.warn("业务异常：code={}, message={}", code.getCode(), ex.getMessage());
        } else {
            log.info("业务分支：code={}, message={}", code.getCode(), ex.getMessage());
        }
        return build(code, ex.getMessage(), null);
    }

    /**
     * 水平越权（2002）的兜底审计（7.6「越权拦截（2001/2002）必须入审计」）。
     *
     * <p>为什么需要本兜底：切面只作用于 {@code @AuditLog} 标注的方法——未标注的查询接口
     * （订单详情、袋台账、调拨详情、消毒批次、盘点单等）在 Service 层抛出的 2002 不经过切面，
     * 此前只留 INFO 日志、审计表零记录；垂直越权（2001）由 {@code AuthzInterceptor} 在拦截点
     * 自行投递事件，本兜底不再重复发。
     *
     * <p>为什么已标 {@code @AuditLog} 的方法也要跳过：切面已按 7.6 把该异常归一为「越权尝试」
     * 动作落库（见 {@code AuditAspect#normalizeAction}），本兜底只补切面看不到的路径，避免双写。
     */
    private void publishForbiddenAudit(BizException ex, ErrorCode code, HttpServletRequest request) {
        // 2001 的审计已由 AuthzInterceptor 在拦截点投递；此处只兜底 2002，避免同一事件重复落库
        if (code != ErrorCode.NO_DATA_PERMISSION) {
            return;
        }
        try {
            if (request != null && isAspectCovered(request)) {
                return;
            }
            LoginUser operator = AuthContext.get();
            AuditEvent event = new AuditEvent();
            event.setOperatorId(operator == null || operator.getUserId() == null ? 0L : operator.getUserId());
            event.setOperatorRole(operator == null || operator.getRole() == null ? "" : operator.getRole());
            event.setModule("安全");
            event.setAction(ACTION_FORBIDDEN_ATTEMPT);
            event.setResult(0);
            event.setErrorCode(code.getCode());
            event.setCostMs(0);
            if (request != null) {
                event.setIp(truncate(resolveClientIp(request), 45));
                event.setUa(truncate(request.getHeader("User-Agent"), 255));
                event.setRequestUri(truncate(request.getRequestURI(), 255));
            } else {
                event.setIp("");
                event.setUa("");
                event.setRequestUri("");
            }
            // 现场说明：非标 @AuditLog 的查询接口不采集入参，这里保留异常文案作为补充上下文
            event.setParamsMasked(truncate("code=" + code.getCode() + "，message=" + ex.getMessage(), 4096));
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            // 审计属旁路能力：投递失败只记 ERROR，绝不改变越权请求的拒绝结果（7.6）
            log.error("越权审计投递失败（不影响响应）：code={}", code.getCode(), e);
        }
    }

    /**
     * 该请求是否已被 {@code AuditAspect} 留痕（方法级 {@code @AuditLog}）。
     *
     * <p>取 Spring 在匹配处理器时写入的 {@link HandlerMapping#BEST_MATCHING_HANDLER_ATTRIBUTE}，
     * 与切面 {@code @annotation} 的覆盖范围一致（都只看方法级注解）。
     */
    private static boolean isAspectCovered(HttpServletRequest request) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        return handler instanceof HandlerMethod handlerMethod
                && AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), AuditLog.class);
    }

    /** 当前线程绑定的 HTTP 请求；非 Web 上下文返回 null。 */
    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    /**
     * 解析客户端 IP：只信任 X-Forwarded-For 最右一段（与 {@code AuditAspect} 同口径
     * 与同部署前提，见 deploy/nginx/nginx.conf.example 的 {@code $proxy_add_x_forwarded_for}：
     * 可信代理把真实对端地址追加在最右端，最左段客户端可伪造）；缺失时回退 remoteAddr。
     * 与切面各自独立实现（本类对 aspect 包的唯一引用是 {@code @AuditLog} 注解类型，
     * 用于判定该请求是否已由切面留痕，见 {@link #isAspectCovered}）。
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
        String remote = request.getRemoteAddr();
        return remote == null ? "" : remote;
    }

    /** 超长按列宽截断；null 归一为空串，避免写库报错。 */
    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 请求体参数校验失败（@RequestBody + @Valid）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Map<String, String>>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        // 参数校验只记字段名，不记参数值（6.6）
        log.warn("参数校验失败（@RequestBody）：字段={}", fieldErrors.keySet());
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.getMessage(), fieldErrors);
    }

    /** 表单/查询参数绑定失败。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Map<String, String>>> handleBind(BindException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("参数校验失败（Bind）：字段={}", fieldErrors.keySet());
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.getMessage(), fieldErrors);
    }

    /** 单参数校验失败（@RequestParam/@PathVariable + @Validated）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Map<String, String>>> handleConstraintViolation(
            ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            fieldErrors.putIfAbsent(String.valueOf(v.getPropertyPath()), v.getMessage());
        }
        log.warn("参数校验失败（@Validated）：字段={}", fieldErrors.keySet());
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.getMessage(), fieldErrors);
    }

    /** 缺必填参数、类型不匹配、请求体不可读：都归 3001。 */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception ex) {
        log.warn("请求参数不合法：{}", ex.getClass().getSimpleName());
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.getMessage(), null);
    }

    /** 请求方法不支持：属调用方问题，归 3001 便于前端定位。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("请求方法不支持：{}", ex.getMethod());
        return build(ErrorCode.PARAM_INVALID, "请求方法不支持", null);
    }

    /** 系统异常兜底：全量堆栈记 ERROR，对外只给 5000。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex) {
        log.error("系统异常：{}", ex.getMessage(), ex);
        return build(ErrorCode.SYSTEM_ERROR, ErrorCode.SYSTEM_ERROR.getMessage(), null);
    }

    private <T> ResponseEntity<Result<T>> build(ErrorCode code, String message, T data) {
        HttpHeaders headers = new HttpHeaders();
        if (code.isRetryable()) {
            headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return ResponseEntity.status(code.getHttpStatus())
                .headers(headers)
                .body(Result.fail(code, message, data));
    }
}
