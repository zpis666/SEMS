package com.sems.module.station.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.bag.entity.Bag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 站点维度袋库存聚合查询 Mapper（只读），查询 {@code bag} 表
 * （口径见《系统设计说明书》5.3 库存视图、3.1 状态定义与 3.5 可用库存一行）。
 *
 * <p><b>为什么在本模块新建：</b>bag 模块的 {@code BagMapper} 属已冻结文件不可修改，
 * 而库存视图与停用前置校验需要按状态聚合（GROUP BY）与计数——BaseMapper 通用方法无法表达聚合，
 * 按实现契约「需要聚合请在本模块自己新建 Mapper」在本模块新建只读 Mapper。
 *
 * <p><b>安全口径：</b>全部 SQL 使用 {@code #{}} 预编译参数绑定，无任何 {@code ${}} 或字符串拼接（设计书 9.4）。
 * 站点可用库存的权威口径 = 该站点 IDLE 袋数（3.1），{@code countWithStatus(#{IDLE})} 即其 DB 实现；
 * Redis 视图与重建见 {@code StockService}（7.2）。
 */
@Mapper
public interface BagStockMapper extends BaseMapper<Bag> {

    /**
     * 按状态聚合站点袋数。
     *
     * @return 每行两个键：{@code status}（袋状态码）、{@code cnt}（数量）
     */
    @Select("SELECT status AS status, COUNT(*) AS cnt FROM bag WHERE station_id = #{stationId} GROUP BY status")
    List<Map<String, Object>> selectStatusCounts(@Param("stationId") Long stationId);

    /** 统计站点处于指定状态的袋数（可用库存 = {@code countWithStatus(sid, IDLE)}）。 */
    @Select("SELECT COUNT(*) FROM bag WHERE station_id = #{stationId} AND status = #{status}")
    long countWithStatus(@Param("stationId") Long stationId, @Param("status") String status);

    /**
     * 统计站点非终态实物数（状态 ≠ 报废，含未激活、待消毒、可复用、维修中、遗失等）：
     * 站点停用前置校验口径（见设计书 5.3 表后说明）。
     */
    @Select("SELECT COUNT(*) FROM bag WHERE station_id = #{stationId} AND status <> #{terminalStatus}")
    long countNonTerminal(@Param("stationId") Long stationId, @Param("terminalStatus") String terminalStatus);
}
