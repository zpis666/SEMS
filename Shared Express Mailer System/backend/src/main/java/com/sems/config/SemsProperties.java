package com.sems.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 业务参数配置（{@code sems.*} 键的唯一绑定点）。
 *
 * <p>覆盖《系统设计说明书》11.2「配置键清单」的全部业务键；时限与计数的口径唯一出处为
 * 设计书 3.5「关键时限与计数口径表」——本类只负责把口径落成可配置项，
 * YAML 中的默认值必须与 3.5/11.2 保持一致，调整须同步这两处文档。
 *
 * <p>绑定方式：启动类已开启 {@code @ConfigurationPropertiesScan}，因此本类不再加 {@code @Component}。
 * 注入方一律构造器注入本对象；业务代码禁止再写死这些阈值（"禁止魔法值"）。
 *
 * <p>安全细节：嵌套类均预实例化，保证未配置任何键时读取不空指针；{@code secret} 与
 * {@code recipientHmacKey} 用 Lombok {@code @ToString.Exclude} 排除出 {@code toString()}，
 * 避免配置对象被日志打印时泄露密钥（见设计书 9.1 信息泄露对策）。
 */
@Data
@ConfigurationProperties(prefix = "sems")
public class SemsProperties {

    /** JWT 签发与续签参数（见设计书 7.1）。 */
    private Jwt jwt = new Jwt();

    /** 袋码二维码对外域名（见设计书 7.4）。 */
    private Qrcode qrcode = new Qrcode();

    /** 取袋在持与频次上限（见设计书 3.5）。 */
    private Borrow borrow = new Borrow();

    /** 登录失败锁定策略（见设计书 9.2）。 */
    private Auth auth = new Auth();

    /** 库存预扣凭据 TTL（见设计书 7.2 / 3.5）。 */
    private Hold hold = new Hold();

    /** 批量接口上限（见设计书 6.2）。 */
    private Batch batch = new Batch();

    /** 遗失认定参数（见设计书 3.5）。 */
    private Lost lost = new Lost();

    /** 调拨在途超时参数（见设计书 3.5）。 */
    private Alloc alloc = new Alloc();

    /** 收件人手机号 HMAC 存储参数（见设计书 8.2 / 9.5）。 */
    private Security security = new Security();

    /** JWT 认证参数。 */
    @Data
    public static class Jwt {

        /** 签名密钥（HS256）：生产必须 ≥32 字节 CSPRNG 随机值、由环境变量注入（见设计书 11.3）。 */
        @ToString.Exclude
        private String secret;

        /**
         * 轮换过渡期的旧签名密钥（HS256）：未配置即无轮换窗口。
         *
         * <p>旧密钥<b>只验不签</b>，用于接受仍持有轮换前令牌的客户端（设计书 7.1「支持双密钥轮换窗口
         * （旧密钥只验不签，过渡 24h）」、11.3「泄露应急预案：轮换密钥」的分阶段落地口径）：
         * 轮换时把原 {@code secret} 挪到本项、新随机值写入 {@code secret}，过渡期结束后从配置移除。
         * 与 {@code secret} 同级按密钥对待，排除出 {@code toString()}，避免日志泄露（9.1）。
         */
        @ToString.Exclude
        private String previousSecret;

        /**
         * 旧密钥验签接受窗口，默认 24h（设计书 7.1「过渡 24h」）。
         *
         * <p>窗口自应用启动（即轮换后的新配置首次加载）起算，过期后即使 {@code previous-secret}
         * 仍被遗留也不再接受旧签名（fail-closed）。access token 最长 2h，轮换 2h 后即不存在
         * 合法旧令牌，24h 是设计书给定的运维过渡余量，覆盖部署与时钟偏差等边界。
         */
        private Duration rotationGrace = Duration.ofHours(24);

        /** 访问令牌有效期，默认 2h（设计书 3.5）。 */
        private Duration accessTtl;

        /** 剩余有效期低于该阈值时可静默续签，默认 30m（设计书 3.5 / 7.1）。 */
        private Duration renewThreshold;
    }

    /** 二维码参数。 */
    @Data
    public static class Qrcode {

        /** 二维码内容域名前缀：{@code https://{domain}/m/bag/{bagCode}}（见设计书 7.4）。 */
        private String domain;
    }

    /** 取袋限额。 */
    @Data
    public static class Borrow {

        /** 在持袋数上限（默认 3）：用户绑定的袋处于「已分配/使用中」两态的订单数上限（设计书 3.5）。 */
        private int openLimit;

        /** 每日取袋次数上限（默认 10），含已撤回/已取消（设计书 3.5）。 */
        private int dailyLimit;
    }

    /** 登录安全参数。 */
    @Data
    public static class Auth {

        /** 连续登录失败锁定阈值（默认 5 次，设计书 9.2）。 */
        private int lockMaxFails;

        /** 锁定时长（默认 15 分钟，设计书 9.2）。 */
        private int lockMinutes;
    }

    /** 库存预扣参数。 */
    @Data
    public static class Hold {

        /** 预扣凭据 TTL，默认 25h（≥24h 业务窗口 + 1h 余量，设计书 3.5 / 7.2）。 */
        private Duration ttl;
    }

    /** 批量接口参数。 */
    @Data
    public static class Batch {

        /** 批量接口默认上限（默认 500，设计书 6.2）。 */
        private int maxSize;

        /** 消毒登记批量上限例外值（默认 200，设计书 5.4/6.2）。 */
        private int disinfectMax;
    }

    /** 遗失认定参数。 */
    @Data
    public static class Lost {

        /** 寄件提交后未归还达到该天数方可确认遗失（默认 14 天，设计书 3.5）。 */
        private int confirmDays;
    }

    /** 调拨参数。 */
    @Data
    public static class Alloc {

        /** 出库后未核收判定在途超时的小时数（默认 48h，设计书 3.5）。 */
        private int timeoutHours;
    }

    /** 收件人手机号摘要密钥（见《数据库设计说明书》4.2.8 {@code recipient_phone_hash}）。 */
    @Data
    public static class Security {

        /**
         * HMAC-SHA256 密钥。默认值仅保证本地未配置时可启动，生产必须经环境变量注入
         * （见设计书 11.3 密钥运维：轮换后对存量哈希做版本标记）。
         */
        @ToString.Exclude
        private String recipientHmacKey = "dev-only-hmac-key-change-me";

        /** 摘要的密钥版本前缀（参与 {@code recipient_phone_hash} 的 {@code v1:} 前缀）；轮换密钥时递增。 */
        private String recipientHmacKeyVersion = "v1";
    }
}
