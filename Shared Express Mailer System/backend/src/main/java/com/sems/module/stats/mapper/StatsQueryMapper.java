package com.sems.module.stats.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.module.order.entity.BagOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * M6 统计与告警的只读聚合 Mapper（见《系统设计说明书》5.6 后台管理与数据统计、3.5 关键时限与计数口径表）。
 *
 * <p><b>为什么单独建在本模块</b>：基础层与各业务模块的 Mapper 只有 MyBatis-Plus 通用 CRUD
 * （bag/station/order 等模块的 Mapper 已冻结，不追加业务方法），而看板与告警需要
 * 「按状态/按站点分组计数」「跨表条件扫描」这类聚合查询。按并行开发契约
 * 「需要联表或聚合请在本模块自己新建 Mapper」的口径，这些只读查询统一收口在本 Mapper。
 *
 * <p><b>安全</b>：全部为静态 SQL + {@code #{}} 预编译参数，不拼接任何输入
 * （设计书 7.2 的「条件 UPDATE 是最后防线」同理，查询侧以预编译防注入，见 9.4）。
 * SQL 中的 {@code 'IDLE'}/{@code 'ALLOCATED'}/{@code 'SCRAPPED'}/{@code 'LOST'}/{@code 'CANCELLED'}
 * 等字面量即 {@code BagStatus}/{@code OrderStatus} 的枚举码，与 {@code bag.status}、
 * {@code bag_order.status} 列取值一致（契约 0 节：实体状态字段用 String）。
 */
@Mapper
public interface StatsQueryMapper {

    /**
     * 按状态统计袋数（看板 {@code statusDistribution} 与「在用袋数/在循环袋数」派生口径的数据源）。
     *
     * <p>口径见设计书 3.5：「在循环袋数 = 非报废、非遗失」「在用袋数 = 状态 ∈
     * {已分配, 使用中, 回收在途, 待消毒, 可复用}」；无袋的状态不会出现在结果中，由服务层补 0。
     *
     * @return 每行 {@code {status: 状态码, cnt: 袋数}}
     */
    @Select("SELECT status AS status, COUNT(*) AS cnt FROM bag GROUP BY status")
    List<Map<String, Object>> countBagByStatus();

    /**
     * 按站点统计在循环袋数（非报废、非遗失，3.5）：周转率分母与库存分布「按站点」维度的数据源。
     *
     * @return 每行 {@code {stationId: 站点ID, cnt: 在循环袋数}}
     */
    @Select("SELECT station_id AS stationId, COUNT(*) AS cnt FROM bag "
            + "WHERE status NOT IN ('SCRAPPED', 'LOST') GROUP BY station_id")
    List<Map<String, Object>> countInCirculationByStation();

    /**
     * 按站点统计空闲袋数（DB 权威口径，3.5「可用库存口径 = 站点 IDLE 袋数」）：
     * 供看板「预警站点数」在 Redis 计数缺失/熔断降级（{@code StockService.getAvailable} 返回 null）时回落，
     * 遵循 7.2「Redis 为读写视图、DB 为权威」。
     *
     * @return 每行 {@code {stationId: 站点ID, cnt: 空闲袋数}}
     */
    @Select("SELECT station_id AS stationId, COUNT(*) AS cnt FROM bag "
            + "WHERE status = 'IDLE' GROUP BY station_id")
    List<Map<String, Object>> countIdleByStation();

    /**
     * 取消后未回收扫描（告警第一类，见设计书 3.5「取消订单未处理升级：订单取消后 48 小时袋仍为已分配」）。
     *
     * <p>三个条件缺一不可：订单 CANCELLED 且 {@code cancelled_at} 早于时限；对应袋仍为 ALLOCATED
     * （撤回/超时取消不释放袋，袋保持已分配等待站点 #4/#5 回收，见 3.6 与 7.2 R1/R2）；
     * <b>且该取消单是袋的最新订单</b>——袋归还后被再次申领时旧取消单仍在库内（cancelled_at 已超时限），
     * 仅凭「袋 = 已分配」会把新持有人的正常订单误报为异常，故以 NOT EXISTS 排除有更新订单的袋。
     *
     * <p>用 {@link Page} 参数限制单类扫描行数（调用方给上限，见 7.5 定时任务「分批 LIMIT 500」的规模口径），
     * 不写死 LIMIT；排序按取消时间升序，先处理拖得最久的。
     *
     * @param page     扫描上限（调用方传 {@code new Page<>(1, 上限, false)}，不查总数）
     * @param deadline 取消时间上限（now − 48h），早于该时刻才纳入
     * @return 命中告警条件的取消订单（含袋码、订单号、站点、取消时间）
     */
    @Select("SELECT o.* FROM bag_order o "
            + "WHERE o.status = 'CANCELLED' AND o.cancelled_at <= #{deadline} "
            + "AND EXISTS (SELECT 1 FROM bag b WHERE b.bag_code = o.bag_code AND b.status = 'ALLOCATED') "
            + "AND NOT EXISTS (SELECT 1 FROM bag_order newer WHERE newer.bag_code = o.bag_code AND newer.id > o.id) "
            + "ORDER BY o.cancelled_at ASC")
    List<BagOrder> selectCancelledNotRecovered(Page<BagOrder> page, @Param("deadline") LocalDateTime deadline);
}
