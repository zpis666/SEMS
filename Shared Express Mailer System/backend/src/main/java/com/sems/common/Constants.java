package com.sems.common;

/**
 * 全站公共常量：正则、请求头名、角色码、账号状态码。
 *
 * <p>目的：把散落在 Controller/Service 里的字面量收敛到唯一出处，避免各模块各写一份后漂移。
 * 引用出处：《系统设计说明书》4.1（角色码）、7.4 与 9.4（袋码正则）、9.2（密码策略）、
 * 9.6（幂等请求头）、10.4（手机号/运单号校验口径）。
 *
 * <p>与配置的边界：可运维调整的时限/阈值一律见 {@code SemsProperties}（配置键清单见 11.2）；
 * 本类只保留与部署无关的格式/标识常量，以及设计书 3.5 明确固定为 10 分钟的幂等窗口
 * {@link #IDEMPOTENT_TTL_MINUTES}。
 */
public final class Constants {

    /**
     * 袋码格式：{@code SJB-{规格码1位}-{出厂年月yyMM}-{5位流水}-{1位校验码}}，
     * 示例 {@code SJB-L-2609-00001-4}（见设计书 7.4；校验位算法见 {@code BagCodeUtils}）。
     */
    public static final String BAG_CODE_REGEX = "^SJB-[A-Z]-\\d{4}-\\d{5}-[0-9A-Z]$";

    /** 手机号：中国大陆 11 位（见设计书 10.4）。 */
    public static final String PHONE_REGEX = "^1[3-9]\\d{9}$";

    /** 密码：至少 8 位且同时含字母与（数字或符号）两类字符，{@code _} 归入符号类（见设计书 9.2）。 */
    public static final String PASSWORD_REGEX = "^(?=.*[A-Za-z])(?=.*[0-9\\W_]).{8,}$";

    /** 运单号：非空、8–20 位字母数字（见设计书 10.4；公司+单号联合唯一在 Service 层校验）。 */
    public static final String TRACKING_NO_REGEX = "^[A-Za-z0-9]{8,20}$";

    /** 幂等请求头 {@code clientRequestId} 的 UUID 格式（写接口必带，见设计书 9.6）。 */
    public static final String UUID_REGEX = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    /** 幂等请求头名（写接口统一从此头取幂等 ID）。 */
    public static final String CLIENT_REQUEST_ID_HEADER = "clientRequestId";

    /** 认证请求头名。 */
    public static final String AUTH_HEADER = "Authorization";

    /** Bearer 令牌前缀（注意末尾含一个空格）。 */
    public static final String BEARER_PREFIX = "Bearer ";

    /** 角色码：普通用户（与 {@code sys_role.code} 一致，见设计书 4.1）。 */
    public static final String ROLE_USER = "USER";

    /** 角色码：站点管理员（一岗一站，经 {@code sys_user.station_id} 绑定站点）。 */
    public static final String ROLE_STATION_ADMIN = "STATION_ADMIN";

    /** 角色码：平台管理员（全局管理）。 */
    public static final String ROLE_PLATFORM_ADMIN = "PLATFORM_ADMIN";

    /** 账号状态：启用（与 {@code sys_user.status} 一致）。 */
    public static final String STATUS_ENABLED = "ENABLED";

    /** 账号状态：停用。 */
    public static final String STATUS_DISABLED = "DISABLED";

    /**
     * 写接口幂等窗口（分钟）：设计书 3.5 将其固定为 10 分钟（"写操作幂等窗口"一行），
     * 未列入 11.2 配置键清单，故不放 {@code SemsProperties}，避免出现两处可调口径。
     */
    public static final int IDEMPOTENT_TTL_MINUTES = 10;

    private Constants() {
    }
}
