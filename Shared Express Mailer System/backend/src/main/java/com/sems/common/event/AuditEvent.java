package com.sems.common.event;

/**
 * 关键事件审计投递载体（见《系统设计说明书》7.6）。
 *
 * <p><b>为什么需要它</b>：7.6 规定「登录失败、越权拦截（2001/2002）、状态机拒绝（4102）……等关键事件
 * <b>必须</b>入审计」。但这两类事件发生在**切面管不到的地方**——
 * 越权拦截在 {@code AuthzInterceptor} 里抛出、在 Controller 方法被调用之前就中断了请求；
 * 状态机拒绝虽然发生在被 {@code @AuditLog} 标注的调用链内，但它需要以「状态机拒绝」这个
 * <b>独立动作</b>落库，才能被审计查询按动作筛出（7.6 的原话是"可按动作筛出"）。
 *
 * <p>直接让 {@code security} / {@code module.statemachine} 去注入 {@code module.audit} 的服务会破坏
 * 契约第 1 节的依赖方向（audit 是最下游，不该被安全层反向依赖）。因此这里用 Spring 的
 * {@link org.springframework.context.ApplicationEventPublisher} 反向投递：
 * <b>发布方只依赖本类（common 包，人人可依赖），订阅方在 module/audit 里实现</b>，
 * 依赖方向保持单向，且发布方不关心有没有人监听（无监听者时事件被静默丢弃，不影响业务）。
 *
 * <p>事件是同步投递的（Spring 默认语义）：监听器内部自己决定异步落库，见 {@code AuditLogService}。
 *
 * <p>字段与 {@code audit_log} 表列一一对应，监听器直接搬运即可。
 */
public class AuditEvent {

    /** 操作人 ID；0 表示匿名或未知（如未认证路径的越权尝试）。 */
    private Long operatorId;
    /** 操作人角色快照；空串表示匿名。 */
    private String operatorRole;
    /** 客户端 IP（调用方负责取 X-Forwarded-For 首个，展示层再掩码）。 */
    private String ip;
    /** 客户端 User-Agent（调用方负责截断）。 */
    private String ua;
    /** 业务模块，如「快递袋管理」「安全」。 */
    private String module;
    /** 业务动作；本类的主要用途就是把动作写成 "越权尝试" / "状态机拒绝" 这类可筛值。 */
    private String action;
    /** 请求 URI（保留「原本在调哪个接口」的上下文，因为动作已被规范化）。 */
    private String requestUri;
    /** 脱敏后的入参或现场补充说明。 */
    private String paramsMasked;
    /** 结果：1 成功 / 0 失败。 */
    private int result;
    /** 错误码：0 表示成功，其余见 {@code com.sems.common.ErrorCode}。 */
    private int errorCode;
    /** 处理耗时（毫秒）。 */
    private int costMs;

    public AuditEvent() {
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getOperatorRole() {
        return operatorRole;
    }

    public void setOperatorRole(String operatorRole) {
        this.operatorRole = operatorRole;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getUa() {
        return ua;
    }

    public void setUa(String ua) {
        this.ua = ua;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getRequestUri() {
        return requestUri;
    }

    public void setRequestUri(String requestUri) {
        this.requestUri = requestUri;
    }

    public String getParamsMasked() {
        return paramsMasked;
    }

    public void setParamsMasked(String paramsMasked) {
        this.paramsMasked = paramsMasked;
    }

    public int getResult() {
        return result;
    }

    public void setResult(int result) {
        this.result = result;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(int errorCode) {
        this.errorCode = errorCode;
    }

    public int getCostMs() {
        return costMs;
    }

    public void setCostMs(int costMs) {
        this.costMs = costMs;
    }
}
