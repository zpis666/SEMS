package com.sems.infra;

import com.sems.common.Constants;
import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.common.util.JsonUtils;
import com.sems.security.AuthContext;
import com.sems.security.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写接口幂等服务（见《系统设计说明书》9.6「幂等设计」与 8.3 键空间）。
 *
 * <p>记录状态机（9.6）：请求进入先以 SETNX 写「处理中」占位 → 成功后在数据库事务提交后改写为「已完成」并记首次结果
 * → 任何失败（业务校验失败、对账中 -2 等）由调用方立即 {@link #clear} 删除占位，事务回滚也会清理占位，
 * 保证客户端重试不会被自己的占位挡死；记录清理只依赖 TTL，无需定时任务。
 *
 * <p>记录值为 JSON：{@code {"status":"PROCESSING"}} 与 {@code {"status":"COMPLETED","result":...}}。
 * {@code result} 由调用方先行序列化（JsonUtils）后以字符串传入，本服务按不透明字符串存取、
 * 命中时原样回放——这样回放结果与首次响应逐字节一致，回放方无需理解业务结构。
 *
 * <p>键中包含 uid：从当前请求登录态（{@link AuthContext}）推导；匿名写接口无登录态时
 * 自动退化为 anon 键（9.6 的豁免与退化口径），故本服务对匿名调用同样可用。
 *
 * <p><b>Redis 故障时的降级行为（7.2 降级矩阵）</b>：7.2 对「幂等（clientRequestId）」这一项写的是
 * 「由 {@code bag_order.client_request_id} 与 {@code stock_compensation_log} 唯一键兜底」，
 * 也就是说降级期**幂等让位给数据库唯一键**，而不是让请求失败。
 * 因此本服务的每个方法都在内部吞掉 Redis 访问异常并记 WARN：
 * {@link #findCompleted} 视作「未命中」放行、其余写操作视作已完成——重复提交最终由唯一键拦下。
 *
 * <p>为什么不把这件事留给各调用方：原先只有取袋链路自带了一套 quiet 包装，而订单、快递袋、
 * 站点等七个服务都是直接调用，于是「Redis 一挂，取袋还能用、标记归还和遗失确认直接 500」。
 * 降级口径要么全站一致、要么就是没降级，所以统一收口到本服务内部。
 */
@Service
public class IdempotentService {

    /** {@link #findCompleted} 命中「处理中」占位时的返回值：调用方据此提示客户端稍后重试。 */
    public static final String PROCESSING = "PROCESSING";

    /** 「已完成」状态字面量（仅本服务内部使用，对外只暴露已完成记录的 result）。 */
    private static final String COMPLETED = "COMPLETED";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_RESULT = "result";

    /** 幂等记录 TTL 10 分钟（设计书 3.5「写操作幂等窗口」，常量口径见契约 Constants）。 */
    private static final Duration TTL = Duration.ofMinutes(Constants.IDEMPOTENT_TTL_MINUTES);

    private static final Logger log = LoggerFactory.getLogger(IdempotentService.class);

    private final StringRedisTemplate redis;

    public IdempotentService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 查幂等结果（只读，不写键）。
     *
     * @return 命中「已完成」→ 首次结果字符串（无 result 时为空串）；命中「处理中」→ {@link #PROCESSING}；
     *         未命中 → {@code null}
     */
    public String findCompleted(String api, String clientRequestId) {
        String raw;
        try {
            raw = redis.opsForValue().get(key(api, clientRequestId));
        } catch (DataAccessException e) {
            // 降级：查不到就当作首次执行放行（7.2 降级矩阵——幂等由 DB 唯一键兜底）。
            // 若在这里上抛，Redis 一挂所有写接口都会变成 500，而设计明确要求降级期业务仍可用。
            log.warn("幂等查询降级（Redis 不可用，按未命中放行）：api={}, crid={}, reason={}",
                    api, clientRequestId, e.getMessage());
            return null;
        }
        if (raw == null) {
            return null;
        }
        // 用通配泛型接收：记录结构由本服务定义、读侧只按键取值，无需把值强转成具体类型
        Map<?, ?> record = JsonUtils.parse(raw, Map.class);
        Object status = record == null ? null : record.get(FIELD_STATUS);
        if (PROCESSING.equals(status)) {
            return PROCESSING;
        }
        if (COMPLETED.equals(status)) {
            Object result = record.get(FIELD_RESULT);
            return result == null ? "" : String.valueOf(result);
        }
        // 结构未知（理论上不可达，记录只由本服务写入）：按未命中处理，
        // 宁可让请求重复执行一次，也不能把请求永久挡死在无法识别的记录上
        return null;
    }

    /** 写「处理中」占位。SETNX 未确认成功时提示可重试，已有记录绝不覆盖；Redis 故障仍按降级策略放行。 */
    public void markProcessing(String api, String clientRequestId) {
        Map<String, Object> placeholder = new LinkedHashMap<>();
        placeholder.put(FIELD_STATUS, PROCESSING);
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(
                    key(api, clientRequestId), JsonUtils.toJson(placeholder), TTL);
            if (!Boolean.TRUE.equals(acquired)) {
                throw new BizException(ErrorCode.STOCK_RECONCILING, "相同幂等请求正在处理，请稍后重试");
            }
        } catch (DataAccessException e) {
            log.warn("幂等占位写入降级（Redis 不可用，跳过占位）：api={}, crid={}, reason={}",
                    api, clientRequestId, e.getMessage());
        }
    }

    /** 把占位改写为「已完成」并记录首次结果；事务内只在提交后写缓存，回放窗口从提交完成时起算。 */
    public void markCompleted(String api, String clientRequestId, Object result) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put(FIELD_STATUS, COMPLETED);
        if (result != null) {
            // 调用方一般传入已序列化字符串；兼容直接传对象的情形，统一落为字符串，保证回放类型稳定
            record.put(FIELD_RESULT, result instanceof String text ? text : JsonUtils.toJson(result));
        }
        String cacheKey = key(api, clientRequestId);
        String completedValue = JsonUtils.toJson(record);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeCompleted(cacheKey, completedValue, api, clientRequestId);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        clearKey(cacheKey, api, clientRequestId);
                    }
                }
            });
            return;
        }
        writeCompleted(cacheKey, completedValue, api, clientRequestId);
    }

    /** 失败即删占位：任何失败分支（含 5103 对账中）都必须调用，否则重试会被自己的「处理中」挡住。 */
    public void clear(String api, String clientRequestId) {
        clearKey(key(api, clientRequestId), api, clientRequestId);
    }

    private void writeCompleted(String cacheKey, String completedValue, String api, String clientRequestId) {
        try {
            redis.opsForValue().set(cacheKey, completedValue, TTL);
        } catch (DataAccessException e) {
            // 业务已成功，写幂等结果失败不能反过来把成功请求报成失败
            log.warn("幂等结果写入降级（Redis 不可用，业务已成功）：api={}, crid={}, reason={}",
                    api, clientRequestId, e.getMessage());
        }
    }

    private void clearKey(String cacheKey, String api, String clientRequestId) {
        try {
            redis.delete(cacheKey);
        } catch (DataAccessException e) {
            // 删占位失败不能掩盖调用方正在抛出的原始业务异常
            log.warn("幂等占位删除降级（Redis 不可用）：api={}, crid={}, reason={}",
                    api, clientRequestId, e.getMessage());
        }
    }

    /**
     * 逐袋幂等（批量接口与状态机内部）：键 = clientRequestId + bagCode，TTL 同 10 分钟。
     *
     * @return true = 该袋在本次请求中首次处理；false = 已处理过，调用方直接跳过
     */
    public boolean tryAcquireBag(String clientRequestId, String bagCode) {
        try {
            Boolean first = redis.opsForValue()
                    .setIfAbsent(RedisKeys.idempotentBag(clientRequestId, bagCode), "1", TTL);
            return Boolean.TRUE.equals(first);
        } catch (DataAccessException e) {
            // 降级：无法去重时放行（返回 true 表示「首次处理」），逐袋重复由状态机的
            // 条件 UPDATE 与业务前置校验兜底——这比让整批 500 更符合 7.2 的降级意图
            log.warn("逐袋幂等降级（Redis 不可用，按首次放行）：crid={}, bagCode={}, reason={}",
                    clientRequestId, bagCode, e.getMessage());
            return true;
        }
    }

    /** 由当前登录态推导幂等键：登录请求带 uid，匿名请求退化（见 9.6）。 */
    private String key(String api, String clientRequestId) {
        LoginUser current = AuthContext.get();
        return RedisKeys.idempotent(current == null ? null : current.getUserId(), api, clientRequestId);
    }
}
