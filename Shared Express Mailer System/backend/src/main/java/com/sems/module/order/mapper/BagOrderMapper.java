package com.sems.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sems.module.order.entity.BagOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 快递订单 Mapper（对应表 {@code bag_order}，见《系统设计说明书》8.2「核心数据表概要」）。
 *
 * <p>仅继承 MyBatis-Plus 通用 CRUD，不声明自定义方法：分页与条件查询经 Service 层用
 * LambdaWrapper 预编译构造；排序字段走白名单映射，禁止拼接 SQL（见设计书 9.4）。
 */
@Mapper
public interface BagOrderMapper extends BaseMapper<BagOrder> {

    // ------------------------------------------------------------------
    // M6 统计聚合（按并行开发契约 8.6 「bag-turnover/order-trend 聚合落在本类」追加于末尾；
    // 仅新增方法，未改动本类其它内容）
    // ------------------------------------------------------------------

    /**
     * 近 N 日取袋趋势：按建单时间 {@code created_at} 的自然日分组计数（设计书 5.6 / 3.5）。
     *
     * <p>日期以 {@code DATE_FORMAT(..., '%Y-%m-%d')} 字符串输出，避免 DATE 列在不同驱动版本下
     * 映射类型不一致；{@code GROUP BY statDate} 为 MySQL 支持的按选择别名分组。
     * 参数全部 {@code #{}} 预编译（9.4 注入防护），区间为左闭右开，由调用方保证。
     *
     * @param begin 统计起始时间（含）
     * @param end   统计结束时间（不含）
     * @return 每行 {@code {statDate: yyyy-MM-dd, cnt: 当日取袋订单数}}
     */
    @Select("SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS statDate, COUNT(*) AS cnt "
            + "FROM bag_order "
            + "WHERE created_at >= #{begin} AND created_at < #{end} "
            + "GROUP BY statDate")
    List<Map<String, Object>> countBorrowByDay(@Param("begin") LocalDateTime begin,
                                               @Param("end") LocalDateTime end);

    /**
     * 近 N 日归还趋势：按回收确认时间 {@code confirmed_at} 的自然日分组计数（设计书 5.6 / 3.5）。
     *
     * <p>与 {@link #countBorrowByDay} 同口径（左闭右开、字符串日期、预编译参数）。
     *
     * @param begin 统计起始时间（含）
     * @param end   统计结束时间（不含）
     * @return 每行 {@code {statDate: yyyy-MM-dd, cnt: 当日归还确认订单数}}
     */
    @Select("SELECT DATE_FORMAT(confirmed_at, '%Y-%m-%d') AS statDate, COUNT(*) AS cnt "
            + "FROM bag_order "
            + "WHERE confirmed_at >= #{begin} AND confirmed_at < #{end} "
            + "GROUP BY statDate")
    List<Map<String, Object>> countReturnByDay(@Param("begin") LocalDateTime begin,
                                               @Param("end") LocalDateTime end);

    /**
     * 按站点统计周期内完成归还确认的订单数（周转率分子，设计书 5.6 / 3.5）：
     * 仅统计 {@code status = FINISHED}（即 {@code OrderStatus.FINISHED} 枚举码，回收确认 #7/#8 后的终态）
     * 且 {@code confirmed_at} 落在周期内的订单，按取袋站点 {@code station_id} 分组。
     *
     * @param begin 周期起始（含）
     * @param end   周期结束（不含）
     * @return 每行 {@code {stationId: 站点ID, cnt: 完成归还确认订单数}}
     */
    @Select("SELECT station_id AS stationId, COUNT(*) AS cnt "
            + "FROM bag_order "
            + "WHERE status = 'FINISHED' AND confirmed_at >= #{begin} AND confirmed_at < #{end} "
            + "GROUP BY station_id")
    List<Map<String, Object>> countFinishedByStation(@Param("begin") LocalDateTime begin,
                                                     @Param("end") LocalDateTime end);
}
