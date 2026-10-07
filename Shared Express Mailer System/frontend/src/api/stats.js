import request from '@/utils/request'

/**
 * 数据统计与后台管理接口（M6，见《系统设计说明书》5.6）。
 * 统计口径（在循环袋数、在用袋数、周转率）唯一定义于设计书 3.5，不要在页面里另算一套。
 */

/**
 * 看板汇总：在用袋数、各状态分布、今日取/还、预警站点数、审计队列丢弃数 auditDropCount。
 * 今日取/还由后端按 stats:today:{stationId} 求和读取（或平台汇总键 stats:today:0）。
 */
export function getDashboard(params) {
  return request.get('/stats/dashboard', params)
}

/** 近 N 日取袋/归还订单趋势（折线图）：params.days 默认 7 */
export function getOrderTrend(params) {
  return request.get('/stats/order-trend', params)
}

/** 快递袋周转率（按周期/按站点，柱状图）：周转率 = 统计周期内完成归还确认的订单数 ÷ 在循环袋数均值 */
export function getBagTurnover(params) {
  return request.get('/stats/bag-turnover', params)
}

/** 库存分布（按站点/按状态，饼图；饼图不超过 3 类，见设计书 10.5） */
export function getStockDistribution(params) {
  return request.get('/stats/stock-distribution', params)
}

/* ------------------------- 审计与告警 ------------------------- */

/** 操作日志分页查询（按操作人/模块/动作 action/时间筛选；IP 默认掩码展示，详情才看完整值） */
export function listAuditLogs(params) {
  return request.get('/audit-logs', params)
}

/**
 * 查看单条审计记录的完整 IP（7.6：列表只回掩码值 ipMasked，完整值须逐条查看）。
 * 该查看行为本身会被后端写入一条审计（module=审计查询、action=查看完整IP），接口返回完整 IP 字符串。
 */
export function getAuditLogIp(id) {
  return request.get(`/audit-logs/${id}/ip`)
}

/** 登录日志查询（成功/失败与原因） */
export function listLoginLogs(params) {
  return request.get('/login-logs', params)
}

/**
 * 异常与风控告警清单。七类：
 * ①订单取消满 48h 袋仍已分配 ②订单待回收确认满 72h 未完成 ③调拨出库满 48h 未核收
 * ④寄件后满 7 天超时未归还 ⑤审计队列丢弃 ⑥库存对账差异 ⑦同类高频确认风控线索
 */
export function listAlerts(params) {
  return request.get('/alerts', params)
}
