package com.sems.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.infra.CounterService;
import com.sems.module.bag.entity.Bag;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.statemachine.BagStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 开单计数对账任务（每日 02:00；见《系统设计说明书》7.5「定时任务设计」开单计数对账行、
 * 7.2「在持袋数计数」口径与 3.5「在持袋数上限」；契约模块版 8.8）。
 *
 * <p>流程：
 * <ol>
 *   <li>装载「当前处于两态」的袋码集合：{@code bag.status ∈ {ALLOCATED, IN_USE}}（3.5 口径），
 *       按主键游标分批（每批 {@value #BATCH_SIZE} 条，命中 {@code idx_bag_status}）；</li>
 *   <li>以袋码集合反查订单（命中 {@code idx_order_bag_code}，袋码按 {@value #BATCH_SIZE} 一组分块
 *       IN 查询），取每只袋 id 最大的订单作为其<b>当前持有订单</b>，按用户聚合出 DB 口径计数——
 *       与 7.2 降级路径「COUNT 袋处于已分配/使用中两态的订单数」同口径：撤回/超时取消（R1/R2 不释放额度）的
 *       订单计入，用户归还过的历史订单不随袋被他人借出重新计入（3.5「袋离开已分配/使用中才 −1」）；</li>
 *   <li>在已载入的在持袋上有订单的用户全部作为候选（含当前不持袋、仅剩历史订单的用户），
 *       保证残留的旧值能被纠回 0；</li>
 *   <li>与 {@code borrow:open:{uid}}（{@link CounterService#getOpenCount}）比对，不一致时用
 *       {@code SETEX} 显式续期 14 天写回 DB 口径值（{@link CounterService#setOpenCount}，
 *       3.5/8.3「14 天滑动、对账重建时显式续期」）；两侧均为零（键不存在且 DB 计数为 0）则不写回，
 *       避免为无在持用户创建计数键。</li>
 * </ol>
 *
 * <p><b>幂等依据</b>（7.5 表「幂等依据」列：计数键重置）：写回是「整值 SET + 固定 TTL」的幂等操作，
 * 重复执行结果一致；本轮与下一轮之间即使再发生申领/回收（计数正常增减），下轮对账仍以 DB 权威口径收敛。
 * 单机部署不加分布式锁（7.5）。
 *
 * <p><b>执行与容错</b>：单条用户处理独立 try/catch（单用户 Redis 抖动不拖垮整轮），
 * 各阶段整体 try/catch 兜底，异常只记 ERROR 并转入下一阶段 / 下一日，绝不终止调度线程。
 */
@Component
public class OpenCountReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(OpenCountReconcileJob.class);

    /** 单批处理条数上限（7.5：扫描类任务分批，每批最多 500 条）。 */
    private static final int BATCH_SIZE = 500;

    /** 单轮最多遍历批数（袋子装载阶段的分批上限；防止异常数据导致单轮无限循环）。 */
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final BagMapper bagMapper;

    private final BagOrderMapper bagOrderMapper;

    private final CounterService counterService;

    public OpenCountReconcileJob(BagMapper bagMapper, BagOrderMapper bagOrderMapper,
                                 CounterService counterService) {
        this.bagMapper = bagMapper;
        this.bagOrderMapper = bagOrderMapper;
        this.counterService = counterService;
    }

    /**
     * 每日 02:00 执行（7.5 表：cron {@code 0 0 2 * * ?}），低峰期整值纠偏全部在持用户计数。
     * 本方法不向外抛异常，保证调度线程不被一次故障终止。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void reconcile() {
        try {
            Set<String> threeStateBagCodes = loadThreeStateBagCodes();
            if (threeStateBagCodes.isEmpty()) {
                log.info("开单计数对账：当前无在持袋，跳过");
                return;
            }
            Map<Long, Integer> dbCounts = countInFlightByHolder(threeStateBagCodes);
            if (dbCounts.isEmpty()) {
                log.info("开单计数对账：无相关订单用户，跳过（在持袋 {} 只）", threeStateBagCodes.size());
                return;
            }
            int fixed = reconcileUsers(dbCounts);
            log.info("开单计数对账完成：候选用户 {} 人，修正 {} 人（在持袋 {} 只）",
                    dbCounts.size(), fixed, threeStateBagCodes.size());
        } catch (Exception e) {
            log.error("开单计数对账任务执行异常，本轮到此处终止，下一日重试：{}", e.getMessage(), e);
        }
    }

    /**
     * 装载当前处于 {已分配, 使用中} 的袋码集合（3.5 在持袋数口径的基础集合）。
     * 按主键游标分批走 {@code idx_bag_status}，命中单轮批数上限即停并告警（剩余留待下一日）。
     */
    private Set<String> loadThreeStateBagCodes() {
        Set<String> codes = new HashSet<>();
        long lastId = 0L;
        for (int round = 0; round < MAX_BATCHES_PER_RUN; round++) {
            List<Bag> batch = bagMapper.selectPage(new Page<>(1, BATCH_SIZE, false),
                    new LambdaQueryWrapper<Bag>()
                            .select(Bag::getId, Bag::getBagCode)
                            .in(Bag::getStatus, BagStatus.ALLOCATED.code(),
                                    BagStatus.IN_USE.code())
                            .gt(Bag::getId, lastId)
                            .orderByAsc(Bag::getId)).getRecords();
            if (batch.isEmpty()) {
                return codes;
            }
            for (Bag bag : batch) {
                codes.add(bag.getBagCode());
            }
            lastId = batch.get(batch.size() - 1).getId();
            if (batch.size() < BATCH_SIZE) {
                return codes;
            }
            if (round == MAX_BATCHES_PER_RUN - 1) {
                log.warn("在持袋装载达到单轮批次上限（{}×{}），本次对账按已装载集合执行，剩余留待下一日",
                        MAX_BATCHES_PER_RUN, BATCH_SIZE);
            }
        }
        return codes;
    }

    /**
     * 聚合 DB 权威口径的在持袋数（按用户）：袋处于已分配/使用中两态的<b>当前持有订单</b>（同袋 id 最大的订单）
     * 计入其用户；同一袋的历史订单不计入（旧订单已随袋离开这两种状态抵扣过 −1，3.5）。
     *
     * <p>撤回/超时取消（R1/R2）的订单虽为已取消，但袋仍为已分配——它们是该袋的当前持有订单，
     * 必须计入（3.5 明确不释放在持袋数额度）；因此这里不能按订单状态过滤。
     *
     * <p>返回 Map 覆盖「在已载入的在持袋上有订单的全部用户」：持有者计数 ≥ 1，其余为 0——
     * 后者一并写回 0，可把降级期漏减等原因残留的旧值纠掉。
     */
    private Map<Long, Integer> countInFlightByHolder(Set<String> threeStateBagCodes) {
        List<String> codes = new ArrayList<>(threeStateBagCodes);
        Map<String, BagOrder> latestByBag = new HashMap<>();
        Set<Long> candidateUsers = new HashSet<>();
        for (int from = 0; from < codes.size(); from += BATCH_SIZE) {
            List<String> chunk = codes.subList(from, Math.min(from + BATCH_SIZE, codes.size()));
            List<BagOrder> orders = bagOrderMapper.selectList(new LambdaQueryWrapper<BagOrder>()
                    .select(BagOrder::getId, BagOrder::getUserId, BagOrder::getBagCode)
                    .in(BagOrder::getBagCode, chunk));
            for (BagOrder order : orders) {
                if (order.getUserId() != null) {
                    candidateUsers.add(order.getUserId());
                }
                // 当前持有订单 = 该袋 id 最大的订单：新订单只能在袋回到空闲后创建，故最大 id 即现持有者
                latestByBag.merge(order.getBagCode(), order, (a, b) -> a.getId() >= b.getId() ? a : b);
            }
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (Long userId : candidateUsers) {
            counts.put(userId, 0);
        }
        for (BagOrder holder : latestByBag.values()) {
            if (holder.getUserId() != null) {
                counts.merge(holder.getUserId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 对候选用户逐人做「DB 口径 vs borrow:open 计数」比对与修正；返回修正人数。 */
    private int reconcileUsers(Map<Long, Integer> dbCounts) {
        int fixed = 0;
        for (Map.Entry<Long, Integer> entry : dbCounts.entrySet()) {
            Long userId = entry.getKey();
            try {
                if (reconcileOne(userId, entry.getValue())) {
                    fixed++;
                }
            } catch (Exception e) {
                // Redis 抖动等只影响该用户：下一日对账会再次尝试，不影响其余用户
                log.error("用户 {} 在持袋数对账失败，跳过：{}", userId, e.getMessage(), e);
            }
        }
        return fixed;
    }

    /**
     * 单用户比对与修正；返回是否发生写回。
     *
     * <p>两侧均为零（Redis 无键且 DB 计数为 0）时不写回：为无在持用户创建计数键没有意义，
     * 只会白白放大键空间；其余不一致一律以 DB 口径 SETEX 写回（14 天显式续期，3.5/8.3）。
     */
    private boolean reconcileOne(Long userId, int dbCount) {
        Integer redisCount = counterService.getOpenCount(userId);
        if (redisCount == null && dbCount == 0) {
            return false;
        }
        if (redisCount != null && redisCount.intValue() == dbCount) {
            return false;
        }
        counterService.setOpenCount(userId, dbCount);
        log.warn("在持袋数对账修正：uid={}，Redis={} → DB={}（SETEX 续期 14 天）",
                userId, redisCount == null ? "(键缺失)" : redisCount, dbCount);
        return true;
    }
}
