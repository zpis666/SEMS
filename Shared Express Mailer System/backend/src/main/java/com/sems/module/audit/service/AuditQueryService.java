package com.sems.module.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.common.PageResult;
import com.sems.common.util.JsonUtils;
import com.sems.common.util.MaskUtils;
import com.sems.module.audit.dto.AuditLogQuery;
import com.sems.module.audit.dto.AuditLogVo;
import com.sems.module.audit.dto.LoginLogQuery;
import com.sems.module.audit.dto.LoginLogVo;
import com.sems.module.audit.entity.AuditLogEntity;
import com.sems.module.audit.entity.LoginLog;
import com.sems.module.audit.mapper.AuditLogMapper;
import com.sems.module.audit.mapper.LoginLogMapper;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 审计查询服务：操作审计日志与登录日志的分页查询，以及「查看完整 IP」的受控访问
 * （见《系统设计说明书》5.6 后台管理模块、7.6 操作日志与审计、9.5 数据安全与脱敏）。
 *
 * <p><b>IP 隐私口径（7.6 原文：登录与操作 IP 仅平台管理员在审计查询中可见）</b>：
 * 列表接口只回 {@code MaskUtils.maskIp} 掩码后的 {@code 192.168.*.*}；完整值只能经
 * {@code GET /api/v1/audit-logs/{id}/ip}、{@code GET /api/v1/login-logs/{id}/ip}
 * 两个<b>独立端点</b>按记录 ID 单独查看。独立成端点而不是给列表加参数，是为了避免
 * 「分页查询顺带把整页完整 IP 批量回给调用方」——批量泄露与单条受控查看的风险量级完全不同。
 * 每次成功查看完整 IP，本服务都会经 {@link AuditLogService} 落一条审计
 * （module=「审计查询」、action=「查看完整IP」），使"谁在什么时候看了哪条记录的 IP"可回溯。
 *
 * <p><b>防篡改（7.6 第①层）</b>：审计表只插入、只查询，本服务不提供任何修改/删除出口。
 *
 * <p><b>权限</b>：两个模块的垂直越权由 Controller 上的
 * {@code @RequireRole("PLATFORM_ADMIN")} 拦截（9.3），本服务不重复判角色；
 * 数据范围上审计数据本身就是全平台数据，无「本站/本人」维度，因此不需要水平越权校验。
 *
 * <p><b>查询实现</b>：沿用基础层 {@code AuditLogService} 的分页口径（LambdaQueryWrapper、
 * 固定时间倒序、默认 10 条、上限 100），但筛选字段以本模块 DTO 为准独立实现，
 * 不与其查询方法耦合（基础层方法不含 result / phoneMask 筛选）。
 */
@Service
public class AuditQueryService {

    /** 分页默认值/上限（设计书 6.1：默认 10、最大 100，与基础层 AuditLogService 口径一致）。 */
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    /** 「查看完整 IP」审计记录的固定模块与动作（7.6：查看行为记审计，且可按模块/动作筛出）。 */
    private static final String MODULE_AUDIT_QUERY = "审计查询";
    private static final String ACTION_VIEW_FULL_IP = "查看完整IP";

    /** 来源标识：写进 params_masked，供事后追溯区分 audit_log 与 login_log 中同值的记录 ID。 */
    private static final String LOG_TYPE_AUDIT = "audit_log";
    private static final String LOG_TYPE_LOGIN = "login_log";

    /** 与 audit_log 表列宽一致（见 deploy/sql/V1__init_schema.sql）。 */
    private static final int IP_COLUMN_WIDTH = 45;
    private static final int UA_COLUMN_WIDTH = 255;
    private static final int ROLE_COLUMN_WIDTH = 20;
    private static final int URI_COLUMN_WIDTH = 255;
    private static final int PARAMS_COLUMN_WIDTH = 4096;

    private final AuditLogMapper auditLogMapper;
    private final LoginLogMapper loginLogMapper;
    private final AuditLogService auditLogService;

    public AuditQueryService(AuditLogMapper auditLogMapper,
                             LoginLogMapper loginLogMapper,
                             AuditLogService auditLogService) {
        this.auditLogMapper = auditLogMapper;
        this.loginLogMapper = loginLogMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * 操作审计日志分页查询（5.6）：按操作人/模块/动作/结果/时间区间筛选，固定按时间倒序。
     *
     * <p>只读事务：审计表只 SELECT；返回的 {@code records} 已由 {@link AuditLogVo} 完成 IP 掩码。
     */
    @Transactional(readOnly = true)
    public PageResult<AuditLogVo> pageAuditLogs(AuditLogQuery query) {
        Page<AuditLogEntity> page = new Page<>(normalizePageNum(query.getPageNum()),
                normalizePageSize(query.getPageSize()));
        String module = trimToNull(query.getModule());
        String action = trimToNull(query.getAction());
        LambdaQueryWrapper<AuditLogEntity> wrapper = Wrappers.lambdaQuery(AuditLogEntity.class)
                .eq(query.getOperatorId() != null, AuditLogEntity::getOperatorId, query.getOperatorId())
                .eq(module != null, AuditLogEntity::getModule, module)
                .eq(action != null, AuditLogEntity::getAction, action)
                .eq(query.getResult() != null, AuditLogEntity::getResult, query.getResult())
                .ge(query.getBeginTime() != null, AuditLogEntity::getCreatedAt, query.getBeginTime())
                .le(query.getEndTime() != null, AuditLogEntity::getCreatedAt, query.getEndTime())
                // 同一毫秒内多条记录按自增 ID 再排序，保证分页翻页不重不漏
                .orderByDesc(AuditLogEntity::getCreatedAt)
                .orderByDesc(AuditLogEntity::getId);
        Page<AuditLogEntity> result = auditLogMapper.selectPage(page, wrapper);
        List<AuditLogVo> records = result.getRecords().stream().map(AuditLogVo::from).toList();
        return PageResult.of(records, result.getTotal(), result.getSize());
    }

    /**
     * 登录日志分页查询（5.6）：按结果/手机号掩码/时间区间筛选，固定按登录时间倒序。
     *
     * <p>只读事务：登录日志同样只插不改（7.6）；返回的 {@code records} 已由
     * {@link LoginLogVo} 完成 IP 掩码。
     */
    @Transactional(readOnly = true)
    public PageResult<LoginLogVo> pageLoginLogs(LoginLogQuery query) {
        Page<LoginLog> page = new Page<>(normalizePageNum(query.getPageNum()),
                normalizePageSize(query.getPageSize()));
        String phoneMask = resolvePhoneMask(query.getPhoneMask(), query.getPhone());
        Page<LoginLog> result = loginLogMapper.selectPage(page, Wrappers.lambdaQuery(LoginLog.class)
                .eq(phoneMask != null, LoginLog::getPhoneMask, phoneMask)
                .eq(query.getResult() != null, LoginLog::getResult, query.getResult())
                .ge(query.getBeginTime() != null, LoginLog::getCreatedAt, query.getBeginTime())
                .le(query.getEndTime() != null, LoginLog::getCreatedAt, query.getEndTime())
                .orderByDesc(LoginLog::getCreatedAt)
                .orderByDesc(LoginLog::getId));
        List<LoginLogVo> records = result.getRecords().stream().map(LoginLogVo::from).toList();
        return PageResult.of(records, result.getTotal(), result.getSize());
    }

    /**
     * 查看单条操作审计记录的完整 IP（7.6 详情可看完整值），并将本次查看行为记入审计。
     *
     * @param id      审计记录 ID（来自列表项）
     * @param request 当前 HTTP 请求，用于给「查看完整IP」审计行采集 IP/UA/URI
     * @return 完整 IP 字符串（原值为空时返回空串）
     * @throws BizException 记录不存在或已按保留期策略归档清理（3001）
     */
    @Transactional(readOnly = true)
    public String viewAuditLogIp(Long id, HttpServletRequest request) {
        long startNanos = System.nanoTime();
        AuditLogEntity entity = auditLogMapper.selectById(id);
        if (entity == null) {
            // 不存在（含已归档清理）按参数问题处理：没有新增错误码可用，3001 语义最接近
            throw new BizException(ErrorCode.PARAM_INVALID, "审计记录不存在或已归档");
        }
        recordIpView(LOG_TYPE_AUDIT, id, request, startNanos);
        return entity.getIp() == null ? "" : entity.getIp();
    }

    /**
     * 查看单条登录日志的完整 IP（7.6 详情可看完整值），并将本次查看行为记入审计。
     *
     * @param id      登录日志 ID（来自列表项）
     * @param request 当前 HTTP 请求，用于给「查看完整IP」审计行采集 IP/UA/URI
     * @return 完整 IP 字符串（原值为空时返回空串）
     * @throws BizException 记录不存在或已按保留期策略归档清理（3001）
     */
    @Transactional(readOnly = true)
    public String viewLoginLogIp(Long id, HttpServletRequest request) {
        long startNanos = System.nanoTime();
        LoginLog loginLog = loginLogMapper.selectById(id);
        if (loginLog == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "登录日志不存在或已归档");
        }
        recordIpView(LOG_TYPE_LOGIN, id, request, startNanos);
        return loginLog.getIp() == null ? "" : loginLog.getIp();
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 写一条「查看完整IP」审计（7.6）：只记成功查看——失败查询没有暴露任何 IP，
     * 不构成需要留痕的"查看"动作；写入经 {@link AuditLogService#record} 异步通道，
     * 队列饱和时按关键级别策略降级，绝不因审计故障让查询报错。
     */
    private void recordIpView(String logType, Long id, HttpServletRequest request, long startNanos) {
        AuditLogEntity audit = new AuditLogEntity();
        // 能进入本方法说明已通过认证与平台管理员鉴权；仍按「无上下文记 0/空」防御，保证审计行成立
        LoginUser operator = AuthContext.get();
        audit.setOperatorId(operator == null || operator.getUserId() == null ? 0L : operator.getUserId());
        audit.setOperatorRole(truncateToColumn(operator == null ? null : operator.getRole(), ROLE_COLUMN_WIDTH));
        audit.setModule(MODULE_AUDIT_QUERY);
        audit.setAction(ACTION_VIEW_FULL_IP);
        audit.setResult(1);
        audit.setErrorCode(ErrorCode.SUCCESS.getCode());
        audit.setCostMs((int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000L));
        if (request == null) {
            // 端点只可能经 HTTP 调用；兜底留空字段，避免非 Web 线程（未来被复用）空指针
            audit.setIp("");
            audit.setUa("");
            audit.setRequestUri("");
        } else {
            audit.setIp(truncateToColumn(resolveClientIp(request), IP_COLUMN_WIDTH));
            audit.setUa(truncateToColumn(request.getHeader("User-Agent"), UA_COLUMN_WIDTH));
            audit.setRequestUri(truncateToColumn(request.getRequestURI(), URI_COLUMN_WIDTH));
        }
        // 记录被查看的记录 ID 与来源表：params_masked 属审计快照字段，无需再脱敏
        audit.setParamsMasked(truncateToColumn(
                JsonUtils.toJson(Map.of("logType", logType, "id", id)), PARAMS_COLUMN_WIDTH));
        auditLogService.record(audit);
    }

    /**
     * 客户端 IP 解析：与审计切面 {@code AuditAspect} 同口径——生产经 Nginx 反代时
     * remoteAddr 只是代理地址，优先取 {@code X-Forwarded-For} 第一个地址（最靠近真实客户端），
     * 缺失时回退 remoteAddr（7.6 审计要素采集口径）。
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * 手机号筛选值归一：正式参数 {@code phoneMask} 优先，兼容前端既有参数 {@code phone}；
     * 完整手机号（满足 {@link Constants#PHONE_REGEX}）自动掩码后再精确匹配——login_log
     * 只存掩码，不归一的话输入完整号会得到"查无记录"的假空结果。
     */
    private static String resolvePhoneMask(String phoneMask, String phoneAlias) {
        String value = trimToNull(phoneMask);
        if (value == null) {
            value = trimToNull(phoneAlias);
        }
        if (value == null) {
            return null;
        }
        return value.matches(Constants.PHONE_REGEX) ? MaskUtils.maskPhone(value) : value;
    }

    /** 去首尾空白；空/全空白归一为 null（供条件构造器判空使用）。 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 按数据库列宽截断：MaskUtils.truncate 追加的省略号另计 1 字符，
     * 故按「列宽 − 1」截断，保证截断结果总长不顶出列宽（避免审计写入因超长失败）。
     */
    private static String truncateToColumn(String value, int columnWidth) {
        if (value == null) {
            return "";
        }
        return MaskUtils.truncate(value, columnWidth - 1);
    }

    /** 页码归一：小于 1 归 1（与基础层 AuditLogService 口径一致）。 */
    private static int normalizePageNum(int pageNum) {
        return pageNum < 1 ? 1 : pageNum;
    }

    /** 每页条数归一：小于 1 取默认值，超过 100 截为 100（设计书 6.1）。 */
    private static int normalizePageSize(int pageSize) {
        if (pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
