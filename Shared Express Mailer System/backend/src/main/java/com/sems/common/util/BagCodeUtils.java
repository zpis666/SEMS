package com.sems.common.util;

import com.sems.common.Constants;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 快递袋编码工具（全站唯一实现，见《系统设计说明书》7.4「快递袋编码与二维码方案」）。
 *
 * <p>编码格式：{@code SJB-{规格码1位}-{出厂年月yyMM}-{5位流水}-{1位校验码}}；
 * 编码不承载站点语义，归属一律以 {@code bag.station_id} 为准（7.4）。
 *
 * <p><b>校验算法（定死，生成与校验必须同源）</b>：
 * 字符表 {@code ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUV"}（索引即值）；
 * 数字 {@code '0'-'9'} 取值 0-9，字母 {@code 'A'-'Z'} 取值 10-35；
 * {@code sum = Σ (i+1) * charValue(body[i])}（body 为去掉连字符与末位校验码后的编码体），
 * {@code index = sum % 31}，校验字符 = {@code ALPHABET.charAt(index)}
 * （结果落在 {@code 0-9A-U}，天然满足袋码正则的 {@code [0-9A-Z]}）。
 * 模 31 混淆算法用于拦截手输错误、提高枚举成本（9.1 业务逻辑滥用对策）。
 *
 * <p>本算法与《系统设计说明书》V1.9 的 7.4 节**逐条对齐**（该节原先只写"模 31 混淆算法"而未给出
 * 算法本体，实现阶段补全后已回写为权威定义）。文档中的示例 {@code SJB-L-2609-00001-4} 即由本算法
 * 得出：{@code checksum("SJBL260900001") == '4'}，可由 {@code UtilsTest} 复核。
 * <b>改算法必须同时改 7.4 与单元测试</b>，否则袋码会"生成得出来、校验不通过"。
 *
 * <p>本类无状态，线程安全。
 */
public final class BagCodeUtils {

    /** 校验字符表：32 字符，索引即值（末尾 U 为 30，模 31 的实际可达上界）。 */
    public static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUV";

    /** 校验模数：31（质数，使连续流水的校验位分布均匀）。 */
    private static final int CHECK_MODULUS = 31;

    /** 固定前缀：SJB- 是印在实物上的编码格式，与包名无关，不得改动。 */
    private static final String PREFIX = "SJB";

    /** 段分隔符。 */
    private static final String SEPARATOR = "-";

    /** 流水号宽度（5 位）与上界。 */
    private static final String SEQ_FORMAT = "%05d";
    private static final int MAX_SEQ = 99_999;

    /** 袋码整体格式（与 Constants.BAG_CODE_REGEX 同源，预编译供高频扫码复用）。 */
    private static final Pattern BAG_CODE_PATTERN = Pattern.compile(Constants.BAG_CODE_REGEX);

    /** 规格码：1 位大写字母。 */
    private static final Pattern SPEC_CODE_PATTERN = Pattern.compile("^[A-Z]$");

    /** 出厂年月：4 位数字 yyMM。 */
    private static final Pattern YEAR_MONTH_PATTERN = Pattern.compile("^\\d{4}$");

    private BagCodeUtils() {
    }

    /**
     * 组装袋码（按规则拼接并计算校验位，不查库；唯一性由 {@code bag.bag_code} 唯一索引兜底）。
     *
     * <p>入参先做格式前置校验：宁可在这里快速失败，也不生成一个 {@link #isValid(String)}
     * 必然拒绝的"半成品袋码"流到下游（届时排查成本高得多）。
     *
     * @param specCode1 规格码，1 位大写字母（如 L）
     * @param yyMM      出厂年月，4 位数字（如 2609）
     * @param seq       5 位流水号，取值 0-99999
     * @return 完整袋码，如 {@code build("L", "2609", 1)}
     */
    public static String build(String specCode1, String yyMM, int seq) {
        if (specCode1 == null || !SPEC_CODE_PATTERN.matcher(specCode1).matches()) {
            throw new IllegalArgumentException("规格码必须为 1 位大写字母：" + specCode1);
        }
        if (yyMM == null || !YEAR_MONTH_PATTERN.matcher(yyMM).matches()) {
            throw new IllegalArgumentException("出厂年月必须为 4 位 yyMM：" + yyMM);
        }
        if (seq < 0 || seq > MAX_SEQ) {
            throw new IllegalArgumentException("流水号须在 0-" + MAX_SEQ + " 范围内：" + seq);
        }
        String seqText = String.format(Locale.ROOT, SEQ_FORMAT, seq);
        String body = PREFIX + specCode1 + yyMM + seqText;
        return PREFIX + SEPARATOR + specCode1 + SEPARATOR + yyMM + SEPARATOR + seqText
                + SEPARATOR + checksum(body);
    }

    /**
     * 校验袋码：先按 {@link Constants#BAG_CODE_REGEX} 验格式，再重算末位校验码比对。
     *
     * <p>只证明"格式 + 校验位"合法，<b>不代表袋存在</b>；存在性与状态校验仍须查库
     * （不存在返回 4106，未激活/报废/遗失返回 4107，见 7.4）。
     *
     * @param code 待校验的完整袋码，可为 null
     * @return 格式与校验位均合法时为 true
     */
    public static boolean isValid(String code) {
        if (code == null || !BAG_CODE_PATTERN.matcher(code).matches()) {
            return false;
        }
        return checksum(bodyOf(code)) == code.charAt(code.length() - 1);
    }

    /**
     * 计算校验字符。
     *
     * @param body 去掉连字符与校验位后的编码体，如 {@code "SJBL260900001"}
     * @return 校验字符（落在 {@code 0-9A-U}）
     */
    public static char checksum(String body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("袋码体不能为空");
        }
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            sum += (i + 1) * charValue(body.charAt(i));
        }
        return ALPHABET.charAt(sum % CHECK_MODULUS);
    }

    /**
     * 取出编码体：完整袋码去掉连字符与末位校验字符（如 {@code SJBL260900001}）。
     */
    private static String bodyOf(String code) {
        return code.substring(0, code.length() - 1).replace(SEPARATOR, "");
    }

    /**
     * 字符取值：数字 {@code '0'-'9'} 取 0-9；字母 {@code 'A'-'Z'} 取 10-35（否则校验无法计算，快速失败）。
     */
    private static int charValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'Z') {
            return c - 'A' + 10;
        }
        throw new IllegalArgumentException("袋码体含非法字符：" + c);
    }
}
