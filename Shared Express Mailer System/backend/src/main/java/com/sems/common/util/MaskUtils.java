package com.sems.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 脱敏与摘要工具（见《系统设计说明书》9.5「数据安全与脱敏」）。
 *
 * <p>三条硬约束：
 * <ul>
 *   <li>出参中的手机号一律经 {@link #maskPhone(String)} 后再返回（{@code 138****1234}）；</li>
 *   <li>审计日志中的登录/操作 IP 默认经 {@link #maskIp(String)} 掩码展示（{@code 192.168.*.*}）；</li>
 *   <li>收件人手机号只落 {@link #hmacPhone(String, String, String)} 的带版本摘要
 *       （存 {@code recipient_phone_hash}，仅作留存证据，系统不提供哈希比对入口，
 *       见《数据库设计说明书》4.2.8 与 8.2）。</li>
 * </ul>
 *
 * <p>本类无状态、纯函数，线程安全。
 */
public final class MaskUtils {

    /** HMAC 算法固定 HmacSHA256（与设计书 8.2 的收件人手机号哈希口径一致）。 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 手机号掩码中段固定为 4 个星号。 */
    private static final String PHONE_MASK = "****";

    /** 截断标记（单个字符 U+2026，而不是三个英文句点）。 */
    private static final String ELLIPSIS = "…";

    private MaskUtils() {
    }

    /**
     * 手机号掩码：保留前 3 位与后 4 位。
     *
     * <p>长度不足 11 位（不满足 {@code Constants.PHONE_REGEX} 的手机号）无法套用该规则，
     * 原样返回——不猜测、也不截成更短的值，避免把脏数据伪装成"已脱敏"。
     *
     * @param phone 原始手机号，可为 null
     * @return 掩码后手机号；null 入参返回 null
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 11) {
            return phone;
        }
        return phone.substring(0, 3) + PHONE_MASK + phone.substring(phone.length() - 4);
    }

    /**
     * IP 掩码：IPv4 保留前两段（{@code 192.168.1.10 → 192.168.*.*}）；
     * IPv6 保留前两段（{@code 2001:db8:0:0:0:0:0:1 → 2001:db8:****}）。
     *
     * <p>入参应为纯 IP 字面量（不含端口）；段数不足、无法按规则掩码时返回原值或 {@code ****}，
     * 宁可少露信息也不回显无法掩码的内容。
     *
     * @param ip 原始 IP，可为 null
     * @return 掩码后 IP；null/空串原样返回
     */
    public static String maskIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return ip;
        }
        if (ip.indexOf(':') >= 0) {
            return maskIpv6(ip);
        }
        // IPv4：定位第二个点，保留其前缀两段，后两段置为 *
        int firstDot = ip.indexOf('.');
        int secondDot = firstDot < 0 ? -1 : ip.indexOf('.', firstDot + 1);
        if (secondDot < 0) {
            // 段数不足（如 "192.168"），不猜测，原样返回
            return ip;
        }
        return ip.substring(0, secondDot) + ".*.*";
    }

    /**
     * IPv6 掩码：取前两个非空段（跳过 {@code ::} 压缩产生的空段），其余以 {@code ****} 代替。
     */
    private static String maskIpv6(String ip) {
        String[] segments = ip.split(":");
        StringBuilder kept = new StringBuilder();
        int count = 0;
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            if (count > 0) {
                kept.append(':');
            }
            kept.append(segment);
            if (++count == 2) {
                break;
            }
        }
        if (count < 2) {
            // 无法取出两段（如 "::1"），整段隐藏
            return "****";
        }
        return kept.append(":****").toString();
    }

    /**
     * 收件人手机号摘要：{@code keyVersion + ":" + HMAC-SHA256(phone, key) 的小写 hex}，
     * 形如 {@code v1:ab12…}。版本前缀使密钥轮换后的新旧摘要可区分（见设计书 8.2）；
     * 摘要总长 67 字符，适配 {@code recipient_phone_hash varchar(80)}。
     *
     * @param phone      收件人手机号；null 返回 null（未填手机号时不产生摘要）
     * @param key        HMAC 密钥（UTF-8 字节，来自 {@code sems.security.recipient-hmac-key}）
     * @param keyVersion 密钥版本（来自 {@code sems.security.recipient-hmac-key-version}）
     * @return 带版本前缀的小写 hex 摘要
     */
    public static String hmacPhone(String phone, String key, String keyVersion) {
        if (phone == null) {
            return null;
        }
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("收件人手机号 HMAC 密钥未配置（sems.security.recipient-hmac-key）");
        }
        if (keyVersion == null || keyVersion.isBlank()) {
            throw new IllegalArgumentException("收件人手机号 HMAC 密钥版本未配置（sems.security.recipient-hmac-key-version）");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(phone.getBytes(StandardCharsets.UTF_8));
            return keyVersion + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // JDK 标准实现必带 HmacSHA256；走到这里属运行环境异常，按系统级错误上报
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    /**
     * 超长截断：长度超过 max 时截取前 max 个字符并追加省略号。
     * 用于审计入参快照等有列宽上限的落库字段（见设计书 7.6）。
     *
     * <p>注意口径：返回值长度最大为 {@code max + 1}（省略号另计）。
     * 落库前按列宽调用时请传"列宽 − 1"（如 {@code params_masked varchar(4096)} 传 4095），
     * 避免省略号把总长顶出列宽。
     *
     * @param s   原字符串，可为 null
     * @param max 截断保留的最大字符数（不含省略号）；≤0 时返回空串
     * @return 截断结果；null 入参返回 null
     */
    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (max <= 0) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + ELLIPSIS;
    }
}
