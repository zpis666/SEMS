package com.sems.infra;

/**
 * Redis 键空间工厂（键名与《系统设计说明书》8.3「缓存与键空间」逐字一致）。
 *
 * <p>为什么把键名收口到这一个类：8.3 是「键名/类型/TTL」的唯一对照表，
 * 若允许业务代码各自拼字符串，键名会随实现漂移，依赖前缀扫描的任务
 * （R4 对账、R3 启动期孤儿检查、巡检与告警）都会失效。
 *
 * <p>约定：全部为静态方法，无状态、可并发调用；本类只负责生成键，
 * 不负责读写与设置 TTL——TTL 口径见 3.5 与 8.3，由调用方或基础设施服务设置。
 */
public final class RedisKeys {

    /** 幂等键的匿名退化段：匿名写接口没有 uid，键退化为 api + clientRequestId（见设计书 9.6）。 */
    private static final String ANON = "anon";

    /** 批量接口逐袋幂等键前缀（见设计书 9.6「幂等键粒度」）。 */
    private static final String BAG_IDEMPOTENT_PREFIX = "idempotent:bag:";

    /** 工具类，禁止实例化。 */
    private RedisKeys() {
    }

    /** 站点可用库存计数（无 TTL，由对账维护）。 */
    public static String stockAvailable(Long sid) {
        return "stock:available:" + sid;
    }

    /** 申领预扣凭据（value 为 orderNo/stationId/bagCode 的 JSON，TTL 25h，仅作凭据）。 */
    public static String stockHold(String orderNo) {
        return "stock:hold:" + orderNo;
    }

    /** 站点计数初始化互斥标记（SETNX，防惊群；Lua 首查命中即返回 5102）。 */
    public static String stockInit(Long sid) {
        return "stock:init:" + sid;
    }

    /** 站点计数对账互斥标记（Lua 命中返回 -2 对应 5103，客户端指数退避重试）。 */
    public static String stockReconcile(Long sid) {
        return "stock:reconcile:" + sid;
    }

    /** 站点计数哨兵键（随计数初始化写入；缺失即触发 7.2 的重建，覆盖 Redis 被清空的情形）。 */
    public static String stockSentinel(Long sid) {
        return "stock:sentinel:" + sid;
    }

    /** 在持袋数计数（≤ 上限；TTL 14 天滑动，每次申领重置，见 3.5）。 */
    public static String borrowOpen(Long uid) {
        return "borrow:open:" + uid;
    }

    /** 取袋日频次计数（≤ 上限；TTL 当日+1 天，见 3.5）。 */
    public static String borrowDaily(Long uid, String yyyyMMdd) {
        return "borrow:daily:" + uid + ":" + yyyyMMdd;
    }

    /** JWT 黑名单（TTL 取 token 剩余有效期）。 */
    public static String authDeny(String jti) {
        return "auth:deny:" + jti;
    }

    /** token_version 读缓存（TTL 60s；降级期直读 DB 保持即时失效，见 7.2）。 */
    public static String authUserVer(Long uid) {
        return "auth:userver:" + uid;
    }

    /** 登录失败计数（锁定策略，见 9.2）。 */
    public static String loginFail(String phone) {
        return "login:fail:" + phone;
    }

    /** 图形验证码（一次性，TTL 3 分钟）。 */
    public static String captcha(String captchaId) {
        return "captcha:" + captchaId;
    }

    /**
     * 写操作幂等记录（TTL 10 分钟，见 9.6）。
     *
     * <p>uid 为 null 时退化为不含 uid 的匿名键——匿名写接口（注册/登录/验证码）默认不启用
     * 接口级幂等，确需防重放的匿名写接口才落到该退化键，避免匿名流量互相覆盖记录。
     */
    public static String idempotent(Long uid, String api, String clientRequestId) {
        return uid == null
                ? "idempotent:" + ANON + ":" + api + ":" + clientRequestId
                : "idempotent:" + uid + ":" + api + ":" + clientRequestId;
    }

    /**
     * 批量接口与状态机内部的逐袋幂等键（clientRequestId + bagCode，见 9.6「幂等键粒度」）。
     *
     * <p>不再叠加 uid：键已经由「一次请求 + 一只袋」唯一确定，加 uid 只会无谓拉长键。
     */
    public static String idempotentBag(String clientRequestId, String bagCode) {
        return BAG_IDEMPOTENT_PREFIX + clientRequestId + ":" + bagCode;
    }

    /** 固定窗口限流计数（subject = 登录后的 uid 或匿名请求来源 ip，窗口口径见 3.5）。 */
    public static String rate(String subject, String api) {
        return "rate:" + subject + ":" + api;
    }

    /** 站点今日取/还实时计数（Hash；写入路径与清零规则见 8.3）。 */
    public static String statsToday(Long sid) {
        return "stats:today:" + sid;
    }

    /** 风控计数：同站同人确认类操作次数（TTL 10 分钟，阈值见 3.5「风控告警阈值」）。 */
    public static String auditRisk(String stationId, String uid) {
        return "risk:confirm:" + stationId + ":" + uid;
    }
}
