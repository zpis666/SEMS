package com.sems.module.bag.service;

import com.sems.common.BizException;
import com.sems.common.ErrorCode;
import com.sems.common.util.BagCodeUtils;
import com.sems.module.bag.mapper.BagCodeMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 袋码生成器（见《系统设计说明书》7.4「编码规则」与契约模块版 8.3）。
 *
 * <p>编码格式 {@code SJB-{规格码1位}-{出厂年月yyMM}-{5位流水}-{1位校验码}}；
 * 流水按「同规格 + 同出厂年月」分组自增：取该分组当前最大流水 + 1（从 00001 起，5 位补零），
 * 校验位由 {@link BagCodeUtils#build(String, String, int)} 按模 31 算法生成。
 *
 * <p>唯一性的最终兜底是 {@code bag.bag_code} 唯一索引：本生成器只保证"读到的最大流水之上 +1"，
 * 并发建档撞码时由唯一索引拦下（事务整体回滚后重试），与单号唯一性兜底口径一致（9.6）。
 */
@Service
public class BagCodeGenerator {

    /** 流水段上限：5 位十进制（与 BagCodeUtils 的 MAX_SEQ 同口径）。 */
    private static final int MAX_SEQ = 99_999;

    /** 出厂年月格式 yyMM（设计书 7.4 袋码示例 {@code SJB-L-2609-00001-4} 的 2609 段）。 */
    private static final DateTimeFormatter YY_MM = DateTimeFormatter.ofPattern("yyMM");

    /** 规格码格式：1 位大写字母（与 DTO 校验、BagCodeUtils 口径一致，防内部调用绕过 DTO 校验）。 */
    private static final Pattern SPEC_CODE = Pattern.compile("^[A-Z]$");

    private final BagCodeMapper bagCodeMapper;

    public BagCodeGenerator(BagCodeMapper bagCodeMapper) {
        this.bagCodeMapper = bagCodeMapper;
    }

    /**
     * 生成下一个袋码（单个建档用）。
     *
     * @param spec 规格码（1 位大写字母；DTO 层已用正则校验，这里再验一次防止内部调用绕过）
     * @return 完整袋码
     */
    public String next(String spec) {
        assertSpec(spec);
        String yyMM = currentYearMonth();
        int seq = bagCodeMapper.selectMaxSeq(spec, yyMM) + 1;
        assertSeqAvailable(seq, spec, yyMM);
        return BagCodeUtils.build(spec, yyMM, seq);
    }

    /**
     * 预留连续 count 个袋码（批量生成整批原子用）。
     *
     * <p>一次读取分组最大流水后连续取号：批量接口自身在一个事务内顺序插入，
     * 事务内后续的建档不会与本次预留冲突（同连接可见自身写入）；并发批次撞码由
     * {@code uk_bag_code} 唯一索引兜底。
     *
     * @param spec  规格码
     * @param count 数量（≥1；上限由 Service 层按 {@code sems.batch.max-size} 校验）
     * @return 连续袋码清单（按流水升序）
     */
    public List<String> nextBatch(String spec, int count) {
        assertSpec(spec);
        String yyMM = currentYearMonth();
        int firstSeq = bagCodeMapper.selectMaxSeq(spec, yyMM) + 1;
        assertSeqAvailable(firstSeq + count - 1, spec, yyMM);
        List<String> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            codes.add(BagCodeUtils.build(spec, yyMM, firstSeq + i));
        }
        return codes;
    }

    /** 当前出厂年月（yyMM）：同一自然月内生成的袋共用一个年月段与流水分组。 */
    private static String currentYearMonth() {
        return LocalDate.now().format(YY_MM);
    }

    /** 流水越界检查：5 位段无法承载时快速失败，而不是生成非法编码（3001 便于调用方改规格/月份）。 */
    private static void assertSeqAvailable(int seq, String spec, String yyMM) {
        if (seq < 1 || seq > MAX_SEQ) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "规格 " + spec + " 在 " + yyMM + " 的流水号已用尽（上限 " + MAX_SEQ + "），请调整规格或月份");
        }
    }

    /**
     * 规格码前置校验（3001）：必须在查最大流水之前完成——规格会参与 LIKE 模式串，
     * 非法字符虽经 {@code #{}} 占位无法注入，但会污染分组匹配结果（宁可快速失败）。
     */
    private static void assertSpec(String spec) {
        if (spec == null || !SPEC_CODE.matcher(spec).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "规格码必须为 1 位大写字母：" + spec);
        }
    }
}
