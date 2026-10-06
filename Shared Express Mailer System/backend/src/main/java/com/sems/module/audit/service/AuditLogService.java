package com.sems.module.audit.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sems.common.ErrorCode;
import com.sems.common.PageResult;
import com.sems.common.event.AuditEvent;
import com.sems.module.audit.entity.AuditLogEntity;
import com.sems.module.audit.entity.LoginLog;
import com.sems.module.audit.entity.SysMetric;
import com.sems.module.audit.mapper.AuditLogMapper;
import com.sems.module.audit.mapper.LoginLogMapper;
import com.sems.module.audit.mapper.SysMetricMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 审计落库服务：审计/登录日志的异步写入、队列饱和降级与分页查询（见《系统设计说明书》7.6）。
 *
 * <p>写入模型（7.6「异步落库与队列饱和策略」）：
 * <ol>
 *   <li>常规路径：任务提交到独立审计线程池 {@code auditExecutor}（有界队列，配置见 {@code AsyncConfig}）；</li>
 *   <li>队列饱和：关键事件（报废/状态修正/回收确认/越权，以及 2001/2002/4102 拒绝类）降级为同步落库，绝不丢；
 *       普通事件追加写入应急文件 {@code logs/audit-fallback-yyyyMMdd.log} 并累加
 *       {@code sys_metric.audit_drop_count}（看板与告警经 {@link #getAuditDropCount()} 读取）；</li>
 *   <li>启动回放：应用就绪后把应急文件中的事件补录进库，成功行删除、失败行保留待下次重试。</li>
 * </ol>
 *
 * <p>防篡改（7.6）：审计表只插入，本服务不提供任何 update/delete 能力。
 *
 * <p>可用性纪律：本服务所有对外方法均不向调用方抛异常——审计属于旁路能力，
 * 任何故障都不能拖垮业务主链路（调用方仍可自行兜底，见 {@code AuditAspect}）。
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    /** 审计队列丢弃计数指标键（sys_metric.metric_key；看板字段 auditDropCount） */
    public static final String METRIC_AUDIT_DROP_COUNT = "audit_drop_count";

    /** 关键事件动作关键字：队列饱和时降级为同步写（7.6），命中任一即视为关键事件 */
    private static final String[] CRITICAL_ACTION_KEYWORDS = {"报废", "状态修正", "回收确认", "越权"};

    /**
     * 关键事件错误码（7.6：登录失败、越权拦截（2001/2002）、状态机拒绝（4102）……必须入审计）。
     *
     * <p>为什么不能只看 action 关键字：拒绝类关键事件落库前动作会被归一为
     * 「越权尝试」「状态机拒绝」等规范值（见 {@code AuditAspect#normalizeAction} 与
     * {@code RecycleService} 的补投），其中「状态机拒绝」不含下方任何关键字。
     * 只看关键字会让 4102 拒绝在队列饱和时被误判为普通事件写入应急文件，而应急文件只在
     * 下次启动回放——审计洪峰（往往正是攻击高峰）中的关键拒绝可能长期不入库，
     * 与 7.6「关键事件降级为同步写」「绝不丢」的意图不符。
     * 因此以错误码兜底判定：action 关键字与 errorCode 两条通道任一命中即按关键事件同步写。
     */
    private static final Set<Integer> CRITICAL_ERROR_CODES = Set.of(
            ErrorCode.FORBIDDEN.getCode(),
            ErrorCode.NO_DATA_PERMISSION.getCode(),
            ErrorCode.BAG_ILLEGAL_TRANSITION.getCode());

    /** 应急文件目录与文件名前缀（与应用日志同目录，运行期生成、不入库） */
    private static final String FALLBACK_DIR = "logs";
    private static final String FALLBACK_FILE_PREFIX = "audit-fallback-";

    /** 应急文件行类型标记：回放时据此还原到正确的表 */
    private static final String KIND_AUDIT = "AUDIT";
    private static final String KIND_LOGIN = "LOGIN";

    /** 分页默认值与上限（与全站分页约定一致：默认 10、最大 100） */
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 应急文件专用序列化器：必须能独立完成「写文件 + 启动回放」闭环，
     * 因此注册 JSR-310（LocalDateTime）、关闭未知字段失败（向前兼容后续加列）。
     */
    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final AuditLogMapper auditLogMapper;
    private final LoginLogMapper loginLogMapper;
    private final SysMetricMapper sysMetricMapper;
    private final ThreadPoolTaskExecutor auditExecutor;

    /** 应急文件读写互斥锁：并发降级写与启动回放不得互相踩踏 */
    private final Object fallbackFileLock = new Object();

    public AuditLogService(AuditLogMapper auditLogMapper,
                           LoginLogMapper loginLogMapper,
                           SysMetricMapper sysMetricMapper,
                           @Qualifier("auditExecutor") ThreadPoolTaskExecutor auditExecutor) {
        this.auditLogMapper = auditLogMapper;
        this.loginLogMapper = loginLogMapper;
        this.sysMetricMapper = sysMetricMapper;
        this.auditExecutor = auditExecutor;
    }

    /**
     * 接住「切面管不到的关键事件」——见 {@link AuditEvent} 的类注释：
     * 越权拦截（2001/2002）在拦截器里抛出、状态机拒绝（4102）需要以独立动作落库，
     * 两者都由发布方投递事件到这里，再走统一的异步落库路径（含队列饱和降级）。
     *
     * <p>监听器<b>不抛异常</b>：审计故障绝不能反向影响拦截器/状态机对请求的拒绝结果。
     */
    @EventListener
    public void onAuditEvent(AuditEvent event) {
        if (event == null) {
            return;
        }
        try {
            AuditLogEntity entity = new AuditLogEntity();
            entity.setOperatorId(event.getOperatorId() == null ? 0L : event.getOperatorId());
            entity.setOperatorRole(event.getOperatorRole() == null ? "" : event.getOperatorRole());
            entity.setIp(event.getIp() == null ? "" : event.getIp());
            entity.setUa(event.getUa() == null ? "" : event.getUa());
            entity.setModule(event.getModule() == null ? "" : event.getModule());
            entity.setAction(event.getAction() == null ? "" : event.getAction());
            entity.setRequestUri(event.getRequestUri() == null ? "" : event.getRequestUri());
            entity.setParamsMasked(event.getParamsMasked() == null ? "" : event.getParamsMasked());
            entity.setResult(event.getResult());
            entity.setErrorCode(event.getErrorCode());
            entity.setCostMs(event.getCostMs());
            record(entity);
        } catch (Exception e) {
            log.error("关键事件审计投递失败（不影响业务）：action={}", event.getAction(), e);
        }
    }

    /**
     * 审计落库唯一入口（审计表只插入）：先提交异步队列，队列饱和时按事件级别降级（7.6）。
     * 本方法保证不抛异常。
     */
    public void record(AuditLogEntity entity) {
        if (entity == null) {
            return;
        }
        if (isSaturated()) {
            log.warn("审计线程池已饱和，按事件级别降级：action={}", entity.getAction());
            degrade(entity);
            return;
        }
        try {
            auditExecutor.execute(() -> persistAudit(entity));
        } catch (RuntimeException e) {
            // 防御：提交阶段的任何异常（线程池关闭等）同样走降级，避免审计静默丢失
            log.warn("审计任务提交失败，按事件级别降级：action={}, reason={}", entity.getAction(), e.getMessage());
            degrade(entity);
        }
    }

    /**
     * 登录日志落库（登录成功/失败均需写 login_log，见 9.2）：走同一审计线程池；
     * 队列饱和时直接同步写——登录日志是账号安全事件，不能延后也不能丢。
     * 本方法保证不抛异常。
     */
    public void recordLogin(LoginLog loginLog) {
        if (loginLog == null) {
            return;
        }
        if (isSaturated()) {
            log.warn("审计线程池已饱和，登录日志改为同步写入：userId={}", loginLog.getUserId());
            persistLoginSynchronously(loginLog);
            return;
        }
        try {
            auditExecutor.execute(() -> persistLogin(loginLog));
        } catch (RuntimeException e) {
            log.warn("登录日志任务提交失败，改为同步写入：userId={}, reason={}", loginLog.getUserId(), e.getMessage());
            persistLoginSynchronously(loginLog);
        }
    }

    /**
     * 探测审计线程池是否已饱和（有界队列打满且线程用尽）。
     *
     * <p>为什么主动探测而不是等 {@code execute} 抛拒绝异常：{@code auditExecutor} 配置的饱和策略是
     * {@code CallerRunsPolicy}（见 AsyncConfig），队列打满时任务由调用线程直接执行、不会抛异常；
     * 若不探测，设计书 7.6 的「普通事件转应急文件」路径永远不会触发，审计洪峰还会拖慢请求线程。
     * 探测本身允许存在竞态：即使漏判，调用者运行策略仍是「不丢审计」的兜底。
     */
    private boolean isSaturated() {
        try {
            ThreadPoolExecutor pool = auditExecutor.getThreadPoolExecutor();
            return pool.getQueue().remainingCapacity() == 0
                    && pool.getActiveCount() >= pool.getMaximumPoolSize();
        } catch (RuntimeException e) {
            // 线程池未初始化等异常情形按未饱和处理，交给正常提交路径
            return false;
        }
    }

    /** 今日审计队列丢弃计数（stat_date=今天；无记录返回 0）。供看板 auditDropCount 与告警读取。 */
    public long getAuditDropCount() {
        try {
            SysMetric metric = sysMetricMapper.selectOne(Wrappers.lambdaQuery(SysMetric.class)
                    .eq(SysMetric::getMetricKey, METRIC_AUDIT_DROP_COUNT)
                    .eq(SysMetric::getStatDate, LocalDate.now()));
            return metric == null || metric.getMetricValue() == null ? 0L : metric.getMetricValue();
        } catch (RuntimeException e) {
            log.warn("读取审计丢弃计数失败，按 0 返回：{}", e.getMessage());
            return 0L;
        }
    }

    /**
     * 操作日志分页查询（平台管理员接口 /api/v1/audit-logs 的数据源，见 5.6）：
     * 按操作人/模块/动作/时间筛选，固定按时间倒序（最近的审计优先展示）。
     */
    public PageResult<AuditLogEntity> pageAuditLogs(int pageNum, int pageSize, Long operatorId,
                                                    String module, String action,
                                                    LocalDateTime beginTime, LocalDateTime endTime) {
        Page<AuditLogEntity> page = new Page<>(normalizePageNum(pageNum), normalizePageSize(pageSize));
        Page<AuditLogEntity> result = auditLogMapper.selectPage(page, Wrappers.lambdaQuery(AuditLogEntity.class)
                .eq(operatorId != null, AuditLogEntity::getOperatorId, operatorId)
                .eq(StringUtils.hasText(module), AuditLogEntity::getModule, module)
                .eq(StringUtils.hasText(action), AuditLogEntity::getAction, action)
                .ge(beginTime != null, AuditLogEntity::getCreatedAt, beginTime)
                .le(endTime != null, AuditLogEntity::getCreatedAt, endTime)
                .orderByDesc(AuditLogEntity::getCreatedAt)
                .orderByDesc(AuditLogEntity::getId));
        return PageResult.of(result.getRecords(), result.getTotal(), result.getSize());
    }

    /** 登录日志分页查询（平台管理员接口 /api/v1/login-logs 的数据源）：按用户/结果/时间筛选。 */
    public PageResult<LoginLog> pageLoginLogs(int pageNum, int pageSize, Long userId, Integer result,
                                              LocalDateTime beginTime, LocalDateTime endTime) {
        Page<LoginLog> page = new Page<>(normalizePageNum(pageNum), normalizePageSize(pageSize));
        Page<LoginLog> records = loginLogMapper.selectPage(page, Wrappers.lambdaQuery(LoginLog.class)
                .eq(userId != null, LoginLog::getUserId, userId)
                .eq(result != null, LoginLog::getResult, result)
                .ge(beginTime != null, LoginLog::getCreatedAt, beginTime)
                .le(endTime != null, LoginLog::getCreatedAt, endTime)
                .orderByDesc(LoginLog::getCreatedAt)
                .orderByDesc(LoginLog::getId));
        return PageResult.of(records.getRecords(), records.getTotal(), records.getSize());
    }

    /**
     * 启动回放应急文件（7.6）：应用就绪后扫描 logs/audit-fallback-*.log，
     * 逐行补录；全部成功的文件删除，存在失败行的文件保留（下次启动重试），避免审计静默丢失。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void replayFallbackFiles() {
        Path dir = Path.of(FALLBACK_DIR);
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, FALLBACK_FILE_PREFIX + "*.log")) {
            stream.forEach(files::add);
        } catch (IOException e) {
            log.error("审计应急文件扫描失败：dir={}", dir, e);
            return;
        }
        for (Path file : files) {
            replayFile(file);
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 异步线程内的审计插入：失败不重试入队，直接转应急文件并计数，保证不丢。 */
    private void persistAudit(AuditLogEntity entity) {
        try {
            auditLogMapper.insert(entity);
        } catch (RuntimeException e) {
            log.error("审计异步落库失败，转应急文件：module={}, action={}", entity.getModule(), entity.getAction(), e);
            writeFallback(KIND_AUDIT, entity);
            bumpDropCount();
        }
    }

    /** 异步线程内的登录日志插入：失败同样转应急文件。 */
    private void persistLogin(LoginLog loginLog) {
        try {
            loginLogMapper.insert(loginLog);
        } catch (RuntimeException e) {
            log.error("登录日志异步落库失败，转应急文件：userId={}", loginLog.getUserId(), e);
            writeFallback(KIND_LOGIN, loginLog);
            bumpDropCount();
        }
    }

    /**
     * 队列饱和降级（7.6）：关键事件改同步写（成功即完成，不计数）；
     * 同步写仍失败或普通事件，则写应急文件并累加丢弃计数。
     */
    private void degrade(AuditLogEntity entity) {
        if (isCritical(entity)) {
            try {
                auditLogMapper.insert(entity);
                return;
            } catch (RuntimeException e) {
                log.error("关键事件同步落库仍失败，转应急文件：action={}", entity.getAction(), e);
            }
        }
        writeFallback(KIND_AUDIT, entity);
        bumpDropCount();
    }

    /** 登录日志同步写入；失败转应急文件并计数（登录事件同样不允许静默丢失）。 */
    private void persistLoginSynchronously(LoginLog loginLog) {
        try {
            loginLogMapper.insert(loginLog);
        } catch (RuntimeException e) {
            log.error("登录日志同步写入失败，转应急文件：userId={}", loginLog.getUserId(), e);
            writeFallback(KIND_LOGIN, loginLog);
            bumpDropCount();
        }
    }

    /**
     * 关键事件判定（7.6）：action 含 报废/状态修正/回收确认/越权 任一关键字，
     * 或 errorCode 为越权拦截（2001/2002）/状态机拒绝（4102）——两条通道任一命中即为关键事件。
     */
    private boolean isCritical(AuditLogEntity entity) {
        if (entity == null) {
            return false;
        }
        String action = entity.getAction();
        if (action != null) {
            for (String keyword : CRITICAL_ACTION_KEYWORDS) {
                if (action.contains(keyword)) {
                    return true;
                }
            }
        }
        Integer errorCode = entity.getErrorCode();
        return errorCode != null && CRITICAL_ERROR_CODES.contains(errorCode);
    }

    /** 追加一行应急文件记录：{"kind":"AUDIT|LOGIN","payload":{...}}。 */
    private void writeFallback(String kind, Object payload) {
        String line;
        try {
            line = FALLBACK_MAPPER.writeValueAsString(new FallbackLine(kind, payload));
        } catch (Exception e) {
            log.error("审计应急文件序列化失败，事件无法留存：kind={}", kind, e);
            return;
        }
        Path file = fallbackFile();
        synchronized (fallbackFileLock) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.error("审计应急文件写入失败：file={}", file, e);
            }
        }
    }

    /** 累加当日审计丢弃计数（sys_metric 原子累加，见 7.6）。 */
    private void bumpDropCount() {
        try {
            sysMetricMapper.increment(METRIC_AUDIT_DROP_COUNT, LocalDate.now());
        } catch (RuntimeException e) {
            log.error("审计丢弃计数累加失败", e);
        }
    }

    /** 回放单个应急文件；与降级写共用互斥锁，读、插入、删除期间不允许并发追加。 */
    private void replayFile(Path file) {
        synchronized (fallbackFileLock) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("审计应急文件读取失败：file={}", file, e);
                return;
            }
            if (lines.isEmpty()) {
                deleteQuietly(file);
                return;
            }
            List<String> failed = new ArrayList<>();
            int replayed = 0;
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                if (replayLine(line)) {
                    replayed++;
                } else {
                    failed.add(line);
                }
            }
            if (failed.isEmpty()) {
                deleteQuietly(file);
                log.info("审计应急文件回放完成：file={}, rows={}", file.getFileName(), replayed);
            } else {
                // 已入库行不回写，避免重复审计；失败行保留待下次启动重试
                rewriteQuietly(file, failed);
                log.warn("审计应急文件部分回放失败：file={}, replayed={}, kept={}", file.getFileName(), replayed, failed.size());
            }
        }
    }

    /** 回放单行；返回 false 表示该行保留待重试（解析失败或入库失败）。 */
    private boolean replayLine(String line) {
        try {
            FallbackLine envelope = FALLBACK_MAPPER.readValue(line, FallbackLine.class);
            if (envelope == null || envelope.getPayload() == null) {
                return true;
            }
            if (KIND_AUDIT.equals(envelope.getKind())) {
                auditLogMapper.insert(FALLBACK_MAPPER.convertValue(envelope.getPayload(), AuditLogEntity.class));
            } else if (KIND_LOGIN.equals(envelope.getKind())) {
                loginLogMapper.insert(FALLBACK_MAPPER.convertValue(envelope.getPayload(), LoginLog.class));
            } else {
                // 未知类型不再阻塞文件清理，记 WARN 供人工核查
                log.warn("审计应急文件行类型未知，已忽略：kind={}", envelope.getKind());
            }
            return true;
        } catch (Exception e) {
            log.warn("审计应急文件行回放失败，保留待下次重试：{}", e.getMessage());
            return false;
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("审计应急文件删除失败：file={}", file, e.getMessage());
        }
    }

    private void rewriteQuietly(Path file, List<String> lines) {
        try {
            Files.write(file, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.error("审计应急文件重写失败：file={}", file, e);
        }
    }

    private Path fallbackFile() {
        return Path.of(FALLBACK_DIR, FALLBACK_FILE_PREFIX + LocalDate.now().format(FILE_DATE) + ".log");
    }

    private static int normalizePageNum(int pageNum) {
        return pageNum < 1 ? 1 : pageNum;
    }

    private static int normalizePageSize(int pageSize) {
        if (pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * 应急文件行封装：kind 标记事件归属表（AUDIT/LOGIN），payload 为实体快照。
     * 手写 getter/setter 供 Jackson 序列化与回放反序列化使用。
     */
    public static class FallbackLine {

        private String kind;
        private Object payload;

        public FallbackLine() {
        }

        public FallbackLine(String kind, Object payload) {
            this.kind = kind;
            this.payload = payload;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public Object getPayload() {
            return payload;
        }

        public void setPayload(Object payload) {
            this.payload = payload;
        }
    }
}
