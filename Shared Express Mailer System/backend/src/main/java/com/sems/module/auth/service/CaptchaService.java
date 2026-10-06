package com.sems.module.auth.service;

import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.infra.RedisKeys;
import com.sems.module.auth.dto.CaptchaVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * 图形验证码服务（见《系统设计说明书》5.1 注册、9.2 登录防护，
 * 键空间与时限口径见 8.3「{@code captcha:{captchaId}}（String，TTL 3 分钟，一次性）」）。
 *
 * <p><b>为什么存 Redis</b>：8.3 把验证码列为 Redis 键空间成员（架构图同口径）——
 * TTL 由 Redis 原生过期承担、应用重启不丢已签发验证码、多实例部署可跨节点校验；
 * 此前实现只存进程内存，与 8.3 的键空间承诺不符，故按 8.3 改正（键统一经
 * {@link RedisKeys#captcha(String)} 生成，避免键名漂移）。
 *
 * <p><b>Redis 不可用时的降级口径（fail-closed）</b>：验证码是登录/注册的人机屏障，
 * Redis 故障时<b>不放行</b>——生成与校验一律抛「验证码服务暂不可用，请稍后重试」。
 * 这与 7.2 降级矩阵对「应用层限流」的 fail-open 不同：限流失效有 Nginx 按 IP 桶兜底，
 * 人机校验没有等价旁路，静默放行等于关掉 9.2 的验证码防护。
 *
 * <p><b>一次性语义</b>：{@link #verifyAndConsume} 用 {@code GETDEL} 原子地「取出并删除」后再比对——
 * 无论对错，验证码用后即废，防止同一张图被反复用于撞库（与登录失败锁定共同构成 9.2 的登录防护）。
 *
 * <p><b>图片生成</b>：java.awt 直接绘制（BufferedImage + ImageIO 输出 PNG + Base64），
 * 不引第三方验证码库；字符集剔除了 0/O、1/I/L 等易混淆字符，降低正常用户输错率。
 *
 * <p>线程安全：{@link SecureRandom} 可并发使用；「一次性」由 Redis {@code GETDEL} 的原子性保证，
 * 同一 captchaId 的并发校验至多一个线程取到值。
 */
@Service
public class CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

    /**
     * 验证码有效期：3 分钟（设计书 3.5「图形验证码 TTL 3 分钟，一次性」定死，不进配置）。
     */
    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(3);

    /** 验证码字符数：4 位（设计书 5.1「4 位字符 PNG」）。 */
    private static final int CODE_LENGTH = 4;

    /** 图片尺寸（像素）：够显示 4 个加粗字符并容纳干扰线。 */
    private static final int IMAGE_WIDTH = 120;
    private static final int IMAGE_HEIGHT = 40;

    /** 干扰线数量：抬高 OCR 自动识别成本（不追求绝对防机器识别，强度主要由失败锁定承担）。 */
    private static final int INTERFERENCE_LINES = 6;

    /** 单字符最大旋转弧度（约 ±20°）：抗 OCR 的同时保证人眼可读。 */
    private static final double MAX_ROTATION_RADIANS = 0.35;

    /**
     * 取码字符集：仅保留 2-9 与不含 I/L/O 的大写字母（共 32 个），
     * 避免 0/O、1/I/L 在图片上难以分辨导致正常用户误输。
     */
    private static final String CHARACTERS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    /** 字符字体：逻辑字体 SansSerif，避免依赖具体平台字体（云端 Linux 无 Arial 时也可回退）。 */
    private static final Font CODE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 26);

    /** 背景色：与前端页面底色一致的浅灰（#F5F7FA），便于页面融合展示。 */
    private static final Color BACKGROUND = new Color(0xF5, 0xF7, 0xFA);

    /** Redis 客户端：验证码的存储与一次性消费（键 {@code captcha:{captchaId}}，见 8.3）。 */
    private final StringRedisTemplate redis;

    /** 强随机源：验证码属安全凭据，禁止用可预测的 java.util.Random（设计书 9.1 防枚举）。 */
    private final SecureRandom random = new SecureRandom();

    public CaptchaService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 生成一张验证码并写入 Redis（TTL 3 分钟）。
     *
     * @return {@link CaptchaVo}：captchaId 与不含 data: 前缀的 PNG Base64
     * @throws BizException {@link ErrorCode#SYSTEM_ERROR}（5000）Redis 不可用时提示稍后重试，不放行
     */
    public CaptchaVo generate() {
        String code = randomCode();
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        try {
            redis.opsForValue().set(RedisKeys.captcha(captchaId), code, CAPTCHA_TTL);
        } catch (RuntimeException e) {
            // Redis 故障时不返回一张永远无法校验通过的图（那会把用户卡在登录页）
            log.error("验证码写入 Redis 失败（fail-closed，不放行）：captchaId={}", captchaId, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "验证码服务暂不可用，请稍后重试");
        }
        CaptchaVo vo = new CaptchaVo();
        vo.setCaptchaId(captchaId);
        vo.setImageBase64(renderPngBase64(code));
        return vo;
    }

    /**
     * 校验并消费验证码（登录的人机校验、注册的必填校验共用）。
     *
     * <p>一次性：用 {@code GETDEL} 原子地取出并删除——无论校验对错，该 captchaId 都已作废
     * （设计书 3.5/8.3「一次性」）；校验失败抛 3001，调用方可据此把「验证码不合格」
     * 计入登录日志（fail_reason=验证码）。Redis 不可用时抛 5000 提示稍后重试（不放行）。
     *
     * @param captchaId   验证码 ID
     * @param captchaCode 用户输入的字符（大小写不敏感，前后空白容忍）
     * @throws BizException {@link ErrorCode#PARAM_INVALID}（3001）或 {@link ErrorCode#SYSTEM_ERROR}（5000）
     */
    public void verifyAndConsume(String captchaId, String captchaCode) {
        if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请输入图形验证码");
        }
        // GETDEL：取出即删除，保证「一次性」在并发下也成立（先移除后比对，杜绝"一图多试"）
        String expected;
        try {
            expected = redis.opsForValue().getAndDelete(RedisKeys.captcha(captchaId));
        } catch (RuntimeException e) {
            log.error("验证码读取 Redis 失败（fail-closed，不放行）：captchaId={}", captchaId, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "验证码服务暂不可用，请稍后重试");
        }
        if (expected == null || !expected.equalsIgnoreCase(captchaCode.trim())) {
            // 不存在、已过期（Redis TTL 到期）与比对失败对外统一口径，不向攻击者区分原因
            throw new BizException(ErrorCode.PARAM_INVALID, "图形验证码不正确或已过期");
        }
    }

    /** 随机取 4 位字符（SecureRandom，防止验证码本身可被预测）。 */
    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return code.toString();
    }

    /**
     * 把验证码渲染为 PNG 图片并 Base64 编码（无 data: 前缀）。
     *
     * <p>绘制要素：浅色底、随机深色字符（逐字符随机颜色与旋转）、若干干扰线。
     * 旋转用 {@link AffineTransform} 的 save/restore 语义——每个字符画完必须复位，
     * 否则旋转变换会累乘到后续字符，整行逐渐歪斜到不可读。
     */
    private String renderPngBase64(String code) {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

            // 干扰线：随机起止点，颜色取 0-199 使线条偏灰、不淹没字符
            g.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < INTERFERENCE_LINES; i++) {
                g.setColor(randomColor(200));
                g.drawLine(random.nextInt(IMAGE_WIDTH), random.nextInt(IMAGE_HEIGHT),
                        random.nextInt(IMAGE_WIDTH), random.nextInt(IMAGE_HEIGHT));
            }

            g.setFont(CODE_FONT);
            int step = IMAGE_WIDTH / (CODE_LENGTH + 1);
            for (int i = 0; i < code.length(); i++) {
                AffineTransform origin = g.getTransform();
                double angle = (random.nextDouble() - 0.5) * 2 * MAX_ROTATION_RADIANS;
                int x = step / 2 + i * step;
                int y = IMAGE_HEIGHT - 8;
                g.rotate(angle, x + step / 2.0, IMAGE_HEIGHT / 2.0);
                g.setColor(randomColor(140));
                g.drawString(String.valueOf(code.charAt(i)), x, y);
                g.setTransform(origin);
            }
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            // 写内存流失败只可能是图像编码器异常，属系统级错误；对外不暴露细节
            log.error("验证码 PNG 渲染失败", e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "验证码生成失败");
        }
    }

    /** 随机深色（RGB 各分量取 [0, bound)）：保证在浅底上有足够对比度。 */
    private Color randomColor(int bound) {
        return new Color(random.nextInt(bound), random.nextInt(bound), random.nextInt(bound));
    }
}
