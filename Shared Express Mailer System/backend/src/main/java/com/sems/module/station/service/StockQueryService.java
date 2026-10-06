package com.sems.module.station.service;

import com.sems.infra.StockService;
import com.sems.module.statemachine.BagStatus;
import com.sems.module.station.mapper.BagStockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站点袋库存查询服务：按状态聚合与可用库存读取
 * （见《系统设计说明书》5.3 站点库存视图、3.1 状态定义、7.2「Redis 库存并发扣减方案」）。
 *
 * <p><b>可用库存口径（唯一）：</b>站点可用库存 = 该站点 IDLE 袋数（3.1 表后说明）；
 * DB 为权威（{@link #countIdle}），Redis 为读写视图（{@link #readAvailableWithRebuild}）。
 * 本服务同时供站点模块（库存视图、停用前置校验、nearby 引导）与盘点模块复用（账面数快照）。
 *
 * <p><b>降级口径：</b>Redis 键缺失（未初始化/被清空）时按契约 5.2 触发一次
 * {@link StockService#rebuildStationStock} 重建并重读；熔断打开或重建失败返回 null，
 * 由调用方决定按 5102（可重试）或 DB 权威口径兜底——绝不静默给出错误计数。
 */
@Service
public class StockQueryService {

    private static final Logger log = LoggerFactory.getLogger(StockQueryService.class);

    private final BagStockMapper bagStockMapper;
    private final StockService stockService;

    public StockQueryService(BagStockMapper bagStockMapper, StockService stockService) {
        this.bagStockMapper = bagStockMapper;
        this.stockService = stockService;
    }

    /**
     * 按状态统计某站点袋数。
     *
     * <p>返回值覆盖全部 11 个袋状态（无记录的状态补 0），键为 {@code BagStatus} 码值：
     * 库存视图与盘点账面数都要求「全量口径」，缺状态补 0 可避免各调用方各写一份补零逻辑。
     * 库中出现未登记状态码时跳过并记 WARN（不因脏数据打断整表读取）。
     */
    public Map<String, Long> countByStatus(Long stationId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (BagStatus status : BagStatus.values()) {
            counts.put(status.code(), 0L);
        }
        List<Map<String, Object>> rows = bagStockMapper.selectStatusCounts(stationId);
        for (Map<String, Object> row : rows) {
            String status = stringValue(row, "status");
            if (status == null) {
                continue;
            }
            if (!counts.containsKey(status)) {
                log.warn("站点 {} 存在未登记状态的袋（status={}），本次聚合跳过该行", stationId, status);
                continue;
            }
            counts.put(status, longValue(row, "cnt"));
        }
        return counts;
    }

    /**
     * 非终态实物数（状态 ≠ 报废）：站点停用前置校验口径（见设计书 5.3 表后说明），
     * 含未激活、待消毒、可复用、维修中、遗失等状态的袋。
     */
    public long countNonTerminal(Long stationId) {
        return bagStockMapper.countNonTerminal(stationId, BagStatus.SCRAPPED.code());
    }

    /** DB 权威可用库存 = 该站点 IDLE 袋数（3.1 与 7.2 R4 对账的权威口径）。 */
    public int countIdle(Long stationId) {
        return (int) bagStockMapper.countWithStatus(stationId, BagStatus.IDLE.code());
    }

    /**
     * 读取 Redis 可用库存视图（键缺失时触发一次重建后重读）。
     *
     * @return 计数值；熔断打开或重建失败等仍不可用时返回 {@code null}，
     *         由调用方按 5102（可重试）或 DB 权威口径处理
     */
    public Integer readAvailableWithRebuild(Long stationId) {
        Integer available = stockService.getAvailable(stationId);
        if (available != null) {
            return available;
        }
        if (stockService.isDegraded()) {
            // 熔断期间不写 Redis 计数，避免恢复后计数偏高（7.2 降级矩阵），直接交由调用方兜底
            return null;
        }
        // 键缺失 = 计数未就绪：按契约 5.2「返回 null 时调用方触发重建」重建后重读一次
        stockService.rebuildStationStock(stationId);
        return stockService.getAvailable(stationId);
    }

    /**
     * 可用库存（Redis 优先、DB 权威兜底）：适用于展示与判定类读取
     * （nearby 引导、预警列表、停用前置校验）。这些场景不应因 Redis 计数短暂缺失而不可用，
     * 而 DB 的 COUNT(IDLE) 本身就是权威口径，兜底结果并不失真。
     */
    public int getAvailableStock(Long stationId) {
        Integer available = readAvailableWithRebuild(stationId);
        return available != null ? available : countIdle(stationId);
    }

    /** 从聚合行中取字符串键值（忽略大小写，兼容不同驱动的列标签大小写差异）。 */
    private static String stringValue(Map<String, Object> row, String key) {
        Object value = lookup(row, key);
        return value == null ? null : String.valueOf(value);
    }

    /** 从聚合行中取数值键值（COUNT 结果为数字类型）。 */
    private static long longValue(Map<String, Object> row, String key) {
        Object value = lookup(row, key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 按键查值：先精确命中，再忽略大小写兜底。 */
    private static Object lookup(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
