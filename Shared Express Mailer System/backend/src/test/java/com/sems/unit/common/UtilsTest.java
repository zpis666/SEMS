package com.sems.unit.common;

import com.sems.common.Constants;
import com.sems.common.util.BagCodeUtils;
import com.sems.common.util.BizNoUtils;
import com.sems.common.util.MaskUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公共工具类单元测试（对应《系统设计说明书》12.2：「脱敏工具、校验器」入单元测试）。
 *
 * <p>覆盖三处**口径唯一**的实现：
 * <ul>
 *   <li>袋码校验位算法（7.4：末位校验码用于拦截手输错误、提高枚举成本），
 *       这里把算法钉死，任何"顺手优化"都会被断言拦住；</li>
 *   <li>手机号/身份证号类敏感字段脱敏（9.5 敏感数据脱敏）；</li>
 *   <li>业务单号生成（对外唯一、不可预测）。</li>
 * </ul>
 */
class UtilsTest {

    /* ==================== 袋码（设计书 7.4） ==================== */

    @Test
    @DisplayName("袋码生成：格式为 SJB-规格1位-年月4位-流水5位-校验1位，且通过合法性校验")
    void bagCodeBuildAndValidate() {
        String code = BagCodeUtils.build("L", "2609", 1);
        assertEquals("SJB-L-2609-00001-", code.substring(0, code.length() - 1),
                "袋码前段格式应与设计书 7.4 的示例一致");
        assertEquals(18, code.length(),
                "袋码定长 18 字符：SJB(3) + -(1) + 规格(1) + -(1) + 年月(4) + -(1) + 流水(5) + -(1) + 校验(1)，"
                        + "未超过 DDL 中 bag_code varchar(20) 的宽度");
        assertTrue(code.matches(Constants.BAG_CODE_REGEX),
                "生成的袋码必须匹配 Constants.BAG_CODE_REGEX");
        assertTrue(BagCodeUtils.isValid(code), "自生成的袋码必须通过 isValid");

        // 设计书 7.4 的示例：SJB-L-2609-00001-4
        assertEquals("SJB-L-2609-00001-4", code, "示例袋码应与设计书 7.4 逐字一致");
    }

    @Test
    @DisplayName("校验位算法：ALPHABET 取值域为 0-9A-U，改动任意一位即被 isValid 拦下")
    void checksumCatchesSingleCharacterMistakes() {
        String code = BagCodeUtils.build("L", "2609", 1);
        assertTrue(BagCodeUtils.ALPHABET.indexOf(code.charAt(code.length() - 1)) <= 30,
                "校验位取自 ALPHABET 前 31 个字符（0-9 与 A-U），满足 DDL 的 [0-9A-Z] 约束");

        // 手输最常见的错误：把流水里的一位敲错
        String typo = "SJB-L-2609-00002-" + code.charAt(code.length() - 1);
        assertFalse(BagCodeUtils.isValid(typo), "改了流水却沿用旧校验位，必须被判为非法");

        // 大小写错误
        assertFalse(BagCodeUtils.isValid(code.toLowerCase()), "小写袋码不是合法格式");
        // 长度错误
        assertFalse(BagCodeUtils.isValid("SJB-L-2609-0001-7"), "流水段不足 5 位应判非法");
        // 非袋码字符串
        assertFalse(BagCodeUtils.isValid("随便一段字符串"));
        assertFalse(BagCodeUtils.isValid(null));
        assertFalse(BagCodeUtils.isValid(""));
    }

    @Test
    @DisplayName("校验位是纯函数：同样入参永远得到同样结果，不同流水得到不同袋码")
    void checksumIsDeterministic() {
        String body = "SJBL260900001";
        assertEquals(BagCodeUtils.checksum(body), BagCodeUtils.checksum(body));

        Set<String> codes = new HashSet<>();
        for (int seq = 1; seq <= 200; seq++) {
            String code = BagCodeUtils.build("L", "2609", seq);
            assertTrue(codes.add(code), "流水不同却生成了重复袋码：" + code);
            assertTrue(BagCodeUtils.isValid(code), "生成即应合法：" + code);
        }
        // 规格码变化应改变校验位（否则规格被改也验不出来）
        assertNotEquals(BagCodeUtils.build("L", "2609", 1), BagCodeUtils.build("M", "2609", 1));
    }

    /* ==================== 脱敏（设计书 9.5） ==================== */

    @Test
    @DisplayName("手机号脱敏：138****1234 形式，非法输入不误伤而是原样返回")
    void maskPhoneKeepsFirstThreeAndLastFour() {
        assertEquals("138****1234", MaskUtils.maskPhone("13800001234"));
        assertEquals("159****8888", MaskUtils.maskPhone("15912348888"));

        // 长度不足或为空时不抛异常（脱敏是展示层兜底，不该因为脏数据把接口打挂）
        assertEquals("", MaskUtils.maskPhone(""));
        assertNull(MaskUtils.maskPhone(null), "null 入参原样返回（脱敏是展示层兜底，不该因脏数据抛异常）");
        assertEquals("123", MaskUtils.maskPhone("123"));
    }

    @Test
    @DisplayName("IP 脱敏：保留网段、掩掉主机位；IPv6 同样处理")
    void maskIpHidesHostPart() {
        String v4 = MaskUtils.maskIp("192.168.1.10");
        assertTrue(v4.startsWith("192.168."), "应保留前两段网段：" + v4);
        assertFalse(v4.contains("1.10"), "主机位不得出现在掩码结果里：" + v4);

        String v6 = MaskUtils.maskIp("2001:db8::1");
        assertTrue(v6.contains("*"), "IPv6 也应被掩码：" + v6);

        assertNull(MaskUtils.maskIp(null));
        assertEquals("", MaskUtils.maskIp(""));
    }

    @Test
    @DisplayName("收件人手机号哈希：带密钥版本前缀、同输入同输出、异输入异输出，且不可反推")
    void hmacPhoneIsStableAndVersioned() {
        String key = "unit-test-key-0123456789abcdef";
        String h1 = MaskUtils.hmacPhone("13800001234", key, "v1");
        String h2 = MaskUtils.hmacPhone("13800001234", key, "v1");
        String h3 = MaskUtils.hmacPhone("13800001235", key, "v1");
        String h4 = MaskUtils.hmacPhone("13800001234", key, "v2");

        assertEquals(h1, h2, "同一手机号同一密钥必须得到同一哈希（留存证据才有可比性）");
        assertNotEquals(h1, h3, "不同手机号不应碰撞");
        assertNotEquals(h1, h4, "密钥版本不同即视为不同哈希（支持轮换）");
        assertTrue(h1.startsWith("v1:"), "哈希必须带密钥版本前缀，供轮换时区分存量：" + h1);
        assertFalse(h1.contains("13800001234"), "哈希不得包含明文手机号");
        assertTrue(h1.length() > 60, "SHA-256 十六进制应为 64 字符（另加版本前缀）：" + h1.length());

        // 设计书 8.2：本系统不提供哈希比对入口，所以这里只验证确定性，不暴露比对方法
        assertNotEquals(h1, MaskUtils.hmacPhone("13800001234", "another-key", "v1"),
                "换密钥必须得到不同哈希");
    }

    @Test
    @DisplayName("超长截断：保留前 max 个字符并追加省略号，边界不抛异常")
    void truncateAppendsEllipsis() {
        assertEquals("abc", MaskUtils.truncate("abc", 10));
        assertEquals("abcde…", MaskUtils.truncate("abcdefghij", 5));
        assertNull(MaskUtils.truncate(null, 5));
        assertEquals("", MaskUtils.truncate("abcdefg", 0));
    }

    /* ==================== 业务单号 ==================== */

    @Test
    @DisplayName("业务单号：前缀区分单据类型、长度不超 DDL 的 varchar(32)、SecureRandom 保证不重复")
    void bizNoIsPrefixedUniqueAndFitsColumn() {
        String orderNo = BizNoUtils.orderNo();
        String allocNo = BizNoUtils.allocationNo();
        String checkNo = BizNoUtils.checkNo();
        String batchNo = BizNoUtils.batchNo();

        assertTrue(orderNo.startsWith("BO"), "订单号前缀 BO：" + orderNo);
        assertTrue(allocNo.startsWith("AL"), "调拨单号前缀 AL：" + allocNo);
        assertTrue(checkNo.startsWith("IC"), "盘点单号前缀 IC：" + checkNo);
        assertTrue(batchNo.startsWith("DB"), "消毒批次号前缀 DB：" + batchNo);

        for (String no : new String[]{orderNo, allocNo, checkNo, batchNo}) {
            assertTrue(no.length() <= 32, "单号长度不得超过 DDL 的 varchar(32)：" + no);
            assertTrue(no.matches("^[A-Z]{2}\\d+$"), "单号应为「前缀 + 数字」，便于日志检索：" + no);
        }

        // 用 SecureRandom 生成，1000 次不应出现碰撞（毕设规模下足够）
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(BizNoUtils.orderNo()), "订单号出现重复，随机性不足");
        }
    }
}
