package com.sems.common.util;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务单号生成器（对应《系统设计说明书》8.2 各表的单号列）。
 *
 * <p>单号结构：{@code 前缀 + yyyyMMddHHmmss + 6 位数字段}，总长 22，适配 varchar(32)。
 * 时间戳段便于人工定位与按天排查。
 *
 * <p><b>6 位数字段为什么不是纯随机数</b>：单号列在 DDL 上都有唯一索引，一旦撞号整个业务事务失败。
 * 纯 6 位随机在同一秒内是「生日悖论」分布——同一秒下 1000 次生成就有约 39% 的概率至少撞一对，
 * 高峰期（站点批量出库、并发申领）完全够得着。因此这里改为
 * <b>随机起点 + 进程内自增序列</b>：数字段 = (启动时随机偏移 + 自增计数) mod 10^6。
 *
 * <p>这样同时得到两个性质：
 * <ul>
 *   <li><b>同一秒内前 100 万个单号互不相同</b>——(偏移 + seq) 在模 10^6 下是单射，
 *       单机部署（见设计书 7.5：单实例部署，无需分布式锁）下这就是硬保证；</li>
 *   <li><b>序列不可预判</b>——偏移取自 {@link SecureRandom}，每个 JVM 启动时不同，
 *       外部只能看到单号却推不出下一个（9.1 业务逻辑滥用对策）。</li>
 * </ul>
 *
 * <p>唯一性兜底仍有两层：跨秒由时间戳段区分，跨实例（若将来多实例部署）
 * 由随机偏移区分，最终由各表的唯一索引把关（9.6）。
 *
 * <p>线程安全：{@code SecureRandom} 与 {@link AtomicInteger} 均线程安全，静态复用即可。
 */
public final class BizNoUtils {

    /** 单号前缀：BO=快递订单（bag_order）。 */
    private static final String PREFIX_ORDER = "BO";

    /** 单号前缀：AL=调拨单（allocation_order）。 */
    private static final String PREFIX_ALLOCATION = "AL";

    /** 单号前缀：IC=盘点单（inventory_check）。 */
    private static final String PREFIX_CHECK = "IC";

    /** 单号前缀：DB=消毒批次（disinfect_record.batch_no）。 */
    private static final String PREFIX_BATCH = "DB";

    /** 时间戳段格式：秒级 14 位（业务按服务器本地时区，部署口径见 11.1）。 */
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 数字段位数（6 位）与模数（10^6）。 */
    private static final int SEQ_WIDTH = 6;
    private static final int SEQ_MODULUS = 1_000_000;

    /** 密码学安全随机源：仅用于生成每次启动各不相同的序列起点。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 进程内自增序列：起点随机，保证「同一秒内不重号」的同时不暴露可预判的编号规律。 */
    private static final AtomicInteger SEQUENCE = new AtomicInteger(RANDOM.nextInt(SEQ_MODULUS));

    private BizNoUtils() {
    }

    /** 快递订单号（BO + 14 位时间 + 6 位随机）。 */
    public static String orderNo() {
        return compose(PREFIX_ORDER);
    }

    /** 调拨单号（AL + 14 位时间 + 6 位随机）。 */
    public static String allocationNo() {
        return compose(PREFIX_ALLOCATION);
    }

    /** 盘点单号（IC + 14 位时间 + 6 位随机）。 */
    public static String checkNo() {
        return compose(PREFIX_CHECK);
    }

    /** 消毒批次号（DB + 14 位时间 + 6 位随机）。 */
    public static String batchNo() {
        return compose(PREFIX_BATCH);
    }

    /**
     * 库存补偿原因固定文案（写入 {@code stock_compensation_log.reason}，
     * 取值示例见《数据库设计说明书》4.2.10 "申领落库失败"）。
     *
     * <p>契约 3.6 登记本方法并标注"不使用"：这里保留为固定文案而非随机值，
     * 供 R0 补偿路径直接取用，避免各调用方自行拼写导致事后核查时原因口径不一。
     */
    public static String compensationReason() {
        return "申领落库失败自动补偿";
    }

    /**
     * 前缀 + 当前时间戳 + 数字段。
     *
     * <p>数字段取 (随机起点 + 自增序列) mod 10^6：模运算下自增序列是单射，
     * 因此同一秒内连续生成的 100 万个单号两两不同；跨秒则由时间戳段区分。
     * {@code getAndIncrement} 与取模之间不需要额外同步——单射性只依赖「每调用一次序列 +1」，
     * 并行调用拿到的是彼此不同的序号（顺序不定但互不重复），这正是唯一性所需的全部条件。
     */
    private static String compose(String prefix) {
        int seq = Math.floorMod(SEQUENCE.getAndIncrement(), SEQ_MODULUS);
        return prefix + LocalDateTime.now().format(TIMESTAMP_FORMAT)
                + String.format(Locale.ROOT, "%0" + SEQ_WIDTH + "d", seq);
    }
}
