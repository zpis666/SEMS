package com.sems.module.bag.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.config.SemsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 袋码二维码生成服务（见《系统设计说明书》7.4「快递袋编码与二维码方案」、5.4）。
 *
 * <p>二维码内容 = {@code sems.qrcode.domain} + {@code /m/bag/{袋码}}（域名由配置注入，
 * 不参与编码与签名；变更域名后旧二维码需换标重印，见 7.4）。输出 PNG：
 * 上方 400×400 二维码 + 下方 60 像素白条标注袋码文本（字体 SansSerif），
 * 便于打印贴标后人工核对与台账展示。
 *
 * <p>实现用 ZXing 生成图形矩阵后经 AWT 拼版：Spring Boot 默认 headless 模式，
 * 不依赖显示设备；接口只返回 {@code byte[]}，响应头由 Controller 设置。
 */
@Service
public class QrCodeService {

    private static final Logger log = LoggerFactory.getLogger(QrCodeService.class);

    /** 二维码边长（像素）：与契约 8.3 的尺寸口径一致。 */
    private static final int QR_SIZE = 400;

    /** 下方袋码文本条高度（像素）。 */
    private static final int TEXT_BAR_HEIGHT = 60;

    /** 袋码文本字号与字体（字体名固定 SansSerif，保证跨平台可渲染）。 */
    private static final int TEXT_FONT_SIZE = 26;
    private static final String TEXT_FONT_NAME = "SansSerif";

    /** 扫码落地页路径前缀：{@code {domain}/m/bag/{袋码}}（设计书 7.4）。 */
    private static final String LANDING_PATH_PREFIX = "/m/bag/";

    private final SemsProperties properties;

    public QrCodeService(SemsProperties properties) {
        this.properties = properties;
    }

    /**
     * 生成一只袋的二维码 PNG。
     *
     * @param bagCode 袋码（调用方已校验存在性与归属）
     * @return PNG 字节（含下方袋码文本）
     */
    public byte[] generatePng(String bagCode) {
        String content = buildContent(bagCode);
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE);
            BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(matrix);

            // 画布 = 二维码 + 下方文本条；先铺白底，避免透明像素在部分打印机上反色
            BufferedImage canvas = new BufferedImage(QR_SIZE, QR_SIZE + TEXT_BAR_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = canvas.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
                graphics.drawImage(qrImage, 0, 0, null);
                drawBagCodeText(graphics, bagCode);
            } finally {
                graphics.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(canvas, "png", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            // 生成失败属系统内部问题（编码内容/运行环境异常），对外只给 5000，详细原因记日志
            log.error("袋码 {} 二维码生成失败：{}", bagCode, e.getMessage(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "二维码生成失败");
        }
    }

    /** 在文本条内水平居中绘制袋码（文本宽度不足画布宽度时才会出现，超宽自动左对齐裁剪）。 */
    private static void drawBagCodeText(Graphics2D graphics, String bagCode) {
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(TEXT_FONT_NAME, Font.PLAIN, TEXT_FONT_SIZE));
        FontMetrics metrics = graphics.getFontMetrics();
        int textWidth = metrics.stringWidth(bagCode);
        int x = Math.max(0, (QR_SIZE - textWidth) / 2);
        // 垂直居中：基线 = 文本条起点 + (条高 + 字高上行 - 下行) / 2
        int y = QR_SIZE + (TEXT_BAR_HEIGHT + metrics.getAscent() - metrics.getDescent()) / 2;
        graphics.drawString(bagCode, x, y);
    }

    /** 拼接扫码内容：{@code {sems.qrcode.domain}/m/bag/{袋码}}；域名未配置时退化为相对路径并告警。 */
    private String buildContent(String bagCode) {
        String domain = properties.getQrcode().getDomain();
        if (domain == null || domain.isBlank()) {
            // 未配置域名仍生成二维码，但扫码无法直接打开落地页：记 WARN 提示运维补配置（11.2）
            log.warn("未配置 sems.qrcode.domain，二维码内容退化为相对路径：/m/bag/{}", bagCode);
            return LANDING_PATH_PREFIX + bagCode;
        }
        String trimmed = domain.endsWith("/") ? domain.substring(0, domain.length() - 1) : domain;
        return trimmed + LANDING_PATH_PREFIX + bagCode;
    }
}
