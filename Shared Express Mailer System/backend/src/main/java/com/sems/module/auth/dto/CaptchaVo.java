package com.sems.module.auth.dto;

import lombok.Data;

/**
 * 图形验证码出参（{@code GET /api/v1/auth/captcha}，见《系统设计说明书》5.1 与 8.3 键空间规划）。
 *
 * <p>{@code imageBase64} 为 PNG 图片的 Base64 编码，<b>不含</b>
 * {@code data:image/png;base64,} 前缀（前端按需自行拼接），避免两端各拼一次造成双重前缀。
 */
@Data
public class CaptchaVo {

    /** 验证码 ID：登录/注册时随请求回传，服务端一次性消费（TTL 3 分钟，见设计书 3.5）。 */
    private String captchaId;

    /** PNG 图片 Base64 字符串（无 data: 前缀）。 */
    private String imageBase64;
}
