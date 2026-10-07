import request from '@/utils/request'

/** 快递袋管理接口（M4，见《系统设计说明书》5.4）。 */

/**
 * 扫码后查询袋信息（登录用户）。
 * 后端按最小必要集返回：规格、是否可领用、所在站点名称；与操作人无归属关系的袋不返回更多（7.4 防枚举）。
 * 空闲/报废/遗失返回 4107，袋码不存在返回 4106。
 */
export function getBagInfo(bagCode) {
  return request.get(`/bags/${bagCode}/info`)
}

/** 袋台账分页查询：{ pageNum, pageSize, status, stationId, keyword }（站点管理员限本站） */
export function listBags(params) {
  return request.get('/bags', params)
}

/** 袋全生命周期流转日志（追溯；本站站点管理员仅限本站袋） */
export function getBagTrace(bagCode) {
  return request.get(`/bags/${bagCode}/trace`)
}

/** 单只袋二维码 PNG（内容为 /m/bag/{袋码}）：返回 blob，用于展示与标签补打 */
export function getBagQrcode(bagCode) {
  return request.download(`/bags/${bagCode}/qrcode`)
}

/** 新建袋档案（编码自动生成，登记站点；初始状态=空闲） */
export function createBag(data) {
  return request.post('/bags', data)
}

/** 批量生成 ≤500 只/批并下载二维码 PNG 压缩包（整批原子；返回 blob 流，是统一 JSON 之外声明的唯一例外） */
export function batchGenerate(data) {
  return request.download('/bags/batch-generate', null, { data, method: 'post' })
}

/** 历史接口，已弃用：建档即空闲，后端返回 4102 */
export function activateBag(bagCode) {
  return request.post(`/bags/${bagCode}/activate`)
}

/** 上架入库（可复用→空闲，转移 #11；批量逐袋部分成功，单批 ≤500，消毒上限 200 是特例） */
export function stockIn(data) {
  return request.post('/bags/stock-in', data)
}

/** 出库（仅用于报废袋实物出库与经批准的盘点差异出库；批量逐袋部分成功） */
export function stockOut(data) {
  return request.post('/bags/stock-out', data)
}

/** 未使用回收（已分配→空闲，转移 #4；绑定订单须已取消；station_id→确认站点） */
export function unusedReturn(bagCode, data) {
  return request.post(`/bags/${bagCode}/unused-return`, data)
}

/** 维修登记（转移 #12） */
export function repairBag(bagCode, data) {
  return request.post(`/bags/${bagCode}/repair`, data)
}

/** 维修完成（修复→可复用 / 无法修复→报废，转移 #13/#14） */
export function repairComplete(bagCode, data) {
  return request.post(`/bags/${bagCode}/repair-complete`, data)
}

/** 直接报废登记（转移 #15；审计重点核查项） */
export function scrapBag(bagCode, data) {
  return request.post(`/bags/${bagCode}/scrap`, data)
}

/** 遗失找回（已遗失→待消毒，转移 #20；不重复计 use_count） */
export function lostRecovery(bagCode, data) {
  return request.post(`/bags/${bagCode}/lost-recovery`, data)
}

/** 状态修正（转移 #21；必填修正原因，盘点单号不作为入参；目标态白名单 {空闲,维修中,已遗失,报废}；跨站须指定站点） */
export function statusCorrect(bagCode, data) {
  return request.post(`/bags/${bagCode}/status-correct`, data)
}

/* ------------------------- 消毒批次 ------------------------- */

/** 消毒批次分页查询（按站点/时间/结果筛选，含批次明细逐袋结果；本站站点管理员限本站） */
export function listDisinfections(params) {
  return request.get('/disinfections', params)
}

/** 消毒批次登记（袋码明细 ≤200/批，为批量上限的特例值；逐袋返回结果明细） */
export function createDisinfection(data) {
  return request.post('/disinfections', data)
}

/* ------------------------- 盘点 ------------------------- */

/** 盘点单分页查询（含袋级差异清单；本站站点管理员限本站） */
export function listInventoryChecks(params) {
  return request.get('/inventory-checks', params)
}

/** 提交盘点单（账面/实际/差异，差异自动计算） */
export function createInventoryCheck(data) {
  return request.post('/inventory-checks', data)
}

/** 作废盘点单（仅「待修正」可作废；盘点单不可删除，作废后可重新盘点） */
export function voidInventoryCheck(id, data) {
  return request.post(`/inventory-checks/${id}/void`, data)
}
