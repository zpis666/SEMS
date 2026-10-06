package com.sems.module.bag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 袋码流水查询 Mapper（见《系统设计说明书》7.4「快递袋编码与二维码方案」）。
 *
 * <p><b>为什么单独建接口：</b>袋码生成需要「同规格 + 同出厂年月」分组下的最大流水（聚合查询），
 * 而基础层的 {@code BagMapper} 只继承 MyBatis-Plus 通用 CRUD 且属冻结文件（不得修改）；
 * 按契约「联表或聚合在本模块自建 Mapper、参数占位禁止拼接 SQL」的口径，把这条查询独立在本接口。
 *
 * <p>流水段在袋码中的位置固定：{@code SJB-规格-年月-流水-校验} 共 18 字符，
 * 流水为第 12-16 位（SQL 的 SUBSTRING 下标从 1 起算），故取 {@code SUBSTRING(bag_code, 12, 5)}。
 *
 * <p>过滤用 LIKE 前缀：命中 {@code uk_bag_code} 唯一索引的索引段扫描，避免全表扫描；
 * {@code spec} 与 {@code yyMM} 由调用方先校验为定长格式（1 位大写字母 / 4 位数字），
 * 模式串中不含用户可控的通配符，且全程 {@code #{}} 预编译占位（见设计书 9.4 注入防护）。
 */
@Mapper
public interface BagCodeMapper {

    /**
     * 查同规格 + 同出厂年月（yyMM）下的最大流水号。
     *
     * <p>分组依据就是袋码本身（编码不承载站点语义，见设计书 7.4）：
     * 前缀 {@code SJB-{spec}-{yyMM}-} 相同的袋即同一分组。
     *
     * @param spec 规格码（1 位大写字母，如 L）
     * @param yyMM 出厂年月（4 位数字，如 2609）
     * @return 该分组当前最大流水号；分组内尚无袋时返回 0
     */
    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING(bag_code, 12, 5) AS UNSIGNED)), 0) FROM bag "
            + "WHERE bag_code LIKE CONCAT('SJB-', #{spec}, '-', #{yyMM}, '-%')")
    int selectMaxSeq(@Param("spec") String spec, @Param("yyMM") String yyMM);
}
