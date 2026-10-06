import request from '@/utils/request'

/** 共享站点管理接口（M3，见《系统设计说明书》5.3）。 */

/** 站点列表（名称/位置/可用库存，过滤停用站），供取袋与归还引导 */
export function listNearbyStations() {
  return request.get('/stations/nearby')
}

/** 站点分页查询（平台管理员） */
export function listStations(params) {
  return request.get('/stations', params)
}

/** 新增站点（名称、地址、开放时段、预警阈值） */
export function createStation(data) {
  return request.post('/stations', data)
}

/** 编辑站点 */
export function updateStation(id, data) {
  return request.put(`/stations/${id}`, data)
}

/**
 * 停用/启用站点。
 * 停用前置（业务规则已收紧）：**可用库存为 0 且（非终态实物为 0 或已指定接收站）**——
 * 仅「可用库存为 0」不足以停用。请求体可带 handoverStationId 指定接收站。
 */
export function updateStationStatus(id, data) {
  return request.patch(`/stations/${id}/status`, data)
}

/** 站点各状态库存实时数（Redis 优先读取） */
export function getStationStock(id) {
  return request.get(`/stations/${id}/stock`)
}

/** 低于预警阈值的站点列表 */
export function listStockWarningStations() {
  return request.get('/stations/stock-warning')
}

/** 回收箱待确认清单（含「未标记但状态为使用中」的袋） */
export function getRecycleBin(id, params) {
  return request.get(`/stations/${id}/recycle-bin`, params)
}

/**
 * 扫码回收确认：对回收箱内袋逐只执行，后端按绑定订单状态自动路由 #4/#5/#7/#8，
 * 确认时袋归属改为确认站点。单批 ≤500 只，返回逐袋结果明细，单只失败不影响其余。
 */
export function recycleConfirm(id, data) {
  return request.post(`/stations/${id}/recycle-confirm`, data)
}
