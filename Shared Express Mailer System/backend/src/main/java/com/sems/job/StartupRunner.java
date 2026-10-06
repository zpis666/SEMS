package com.sems.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sems.infra.RedisKeys;
import com.sems.infra.StockService;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 启动期兜底任务 R3（见《系统设计说明书》7.2「R3 崩溃窗口兜底」与「计数初始化与故障恢复」、7.5）。
 *
 * <p>进程可能在「Lua 预扣成功、DB 事务未落地」之间崩溃，留下无订单行的孤儿预扣凭据
 * {@code stock:hold:{orderNo}}（TTL 25h）。启动期一次性处理：
 * <ol>
 *   <li>用 SCAN 游标扫描 {@code stock:hold:*}（不用 KEYS，避免阻塞单线程 Redis；启动期量小，7.2 原文
 *       「启动期一次性，量小」），按 {@code order_no} 分批与 {@code bag_order} 比对（命中唯一键
 *       {@code uk_order_order_no}）；</li>
 *   <li>无对应订单行的凭据即孤儿 → DEL 并打 WARN 告警（删除失败留待 TTL 自然过期）；</li>
 *   <li>随后调 {@code stockService.initializeAllStations()} 重建全部 ENABLED 站点的库存计数与哨兵键
 *       （7.2「计数初始化与故障恢复」：启动/恢复事件单线程触发）。</li>
 * </ol>
 *
 * <p><b>幂等依据</b>（7.5 表：崩溃窗口兜底）：孤儿判定是确定性比对（凭据存在而订单不存在），
 * 删除操作幂等；重启后重复执行结果一致。兜底主体是 R4 与每日开单计数对账（7.2），
 * 本任务只清理启动瞬间可见的孤儿，不做任何补偿性写入。
 *
 * <p><b>启动可用性纪律</b>：整个过程 try/catch——Redis 不可用时打 ERROR 后返回，
 * <b>绝不让应用启动失败</b>（R3 属兜底增强，缺失由 R4 对账与初始化恢复流程弥补，7.2）。
 */
@Component
public class StartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    /** 分批比对条数上限（7.5：扫描类任务分批，每批最多 500 条）。 */
    private static final int BATCH_SIZE = 500;

    /** WARN 日志携带的样本条数上限（孤儿多时日志保持有界）。 */
    private static final int LOG_SAMPLE_SIZE = 20;

    /** 预扣凭据键前缀（取自 RedisKeys，避免键名口径漂移）。 */
    private static final String HOLD_KEY_PREFIX = RedisKeys.stockHold("");

    private final StringRedisTemplate redisTemplate;

    private final BagOrderMapper bagOrderMapper;

    private final StockService stockService;

    public StartupRunner(StringRedisTemplate redisTemplate, BagOrderMapper bagOrderMapper,
                         StockService stockService) {
        this.redisTemplate = redisTemplate;
        this.bagOrderMapper = bagOrderMapper;
        this.stockService = stockService;
    }

    /**
     * 应用启动后执行 R3：清理孤儿预扣凭据 → 重建全部启用站点计数。
     * 任何异常只记 ERROR 后返回，不影响应用启动（交由 R4 兜底）。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> orderNos = scanHoldOrderNos();
            if (orderNos.isEmpty()) {
                log.info("R3 启动期兜底：未发现预扣凭据，跳过孤儿检查");
            } else {
                List<String> orphans = filterOrphans(orderNos);
                deleteOrphans(orphans);
                log.info("R3 启动期兜底：扫描预扣凭据 {} 个，孤儿 {} 个", orderNos.size(), orphans.size());
            }
            // 随后重建全部启用站点计数（DEL → SETNX init → SET available → SET sentinel → DEL init）
            stockService.initializeAllStations();
        } catch (Exception e) {
            log.error("R3 启动期兜底失败（Redis 不可用等），应用继续启动，交由 R4 库存对账与每日开单计数对账兜底：{}",
                    e.getMessage(), e);
        }
    }

    /** 扫描全部 {@code stock:hold:*} 键并裁出 orderNo；使用 SCAN 游标，避免 KEYS 阻塞 Redis。 */
    private List<String> scanHoldOrderNos() throws Exception {
        List<String> orderNos = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions()
                .match(HOLD_KEY_PREFIX + "*")
                .count(BATCH_SIZE)
                .build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (key != null && key.length() > HOLD_KEY_PREFIX.length()) {
                    orderNos.add(key.substring(HOLD_KEY_PREFIX.length()));
                }
            }
        }
        return orderNos;
    }

    /**
     * 与 {@code bag_order} 比对，返回无订单行的孤儿 orderNo 列表。
     * 每批 {@value #BATCH_SIZE} 个单号一次 IN 查询（命中 uk_order_order_no），内存比对得出差集。
     */
    private List<String> filterOrphans(List<String> orderNos) {
        List<String> orphans = new ArrayList<>();
        for (int from = 0; from < orderNos.size(); from += BATCH_SIZE) {
            List<String> chunk = orderNos.subList(from, Math.min(from + BATCH_SIZE, orderNos.size()));
            Set<String> existing = bagOrderMapper.selectList(new LambdaQueryWrapper<BagOrder>()
                            .select(BagOrder::getOrderNo)
                            .in(BagOrder::getOrderNo, chunk))
                    .stream().map(BagOrder::getOrderNo).collect(Collectors.toSet());
            for (String orderNo : chunk) {
                if (!existing.contains(orderNo)) {
                    orphans.add(orderNo);
                }
            }
        }
        return orphans;
    }

    /**
     * 删除孤儿凭据并打 WARN 告警；单个删除失败只记 ERROR（凭据 TTL 25h 自然过期，
     * 期间不参与任何业务判定——R0 补偿以凭据存在性为幂等依据，但该 orderNo 无订单，
     * 不存在对应的补偿调用方）。
     */
    private void deleteOrphans(List<String> orphans) {
        if (orphans.isEmpty()) {
            return;
        }
        int deleted = 0;
        for (String orderNo : orphans) {
            try {
                redisTemplate.delete(RedisKeys.stockHold(orderNo));
                deleted++;
            } catch (Exception e) {
                log.error("孤儿预扣凭据删除失败，留待 TTL 自然过期：orderNo={}, reason={}", orderNo, e.getMessage());
            }
        }
        log.warn("R3 启动期发现孤儿预扣凭据 {} 个（无对应订单行，崩溃窗口残留），已删除 {} 个；样本：{}",
                orphans.size(), deleted, sample(orphans));
    }

    /** 拼接孤儿单号样本，最多 {@value #LOG_SAMPLE_SIZE} 个。 */
    private static String sample(List<String> orderNos) {
        return orderNos.stream().limit(LOG_SAMPLE_SIZE).collect(Collectors.joining("、"));
    }
}
