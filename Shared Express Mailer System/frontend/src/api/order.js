import request from '@/utils/request'

/** 订单接口（M2 用户侧 / M5 平台侧，见《系统设计说明书》5.2 与 5.5）。 */

/**
 * 扫码申领取袋（M2）。
 * 后端在一个 Lua 内原子完成「在持袋数 ≤3 + 取袋日频次 ≤10 + 库存扣减 + 预扣凭据写入」，
 * 再在事务里建订单（待寄件）并把袋置为已分配。失败码：4101 库存不足 / 4104 在持袋数超限 /
 * 4109 频次超限 / 4103 并发重复申领 / 4110 袋被调拨单锁定 / 5102·5103 可重试。
 */
export function borrow(data) {
  return request.post('/borrows', data)
}

/** 提交寄件信息（快递公司、运单号、收件人手机号→落库转掩码+哈希）；订单非待寄件报 4105 */
export function submitShipping(orderNo, data) {
  return request.post(`/borrows/${orderNo}/shipping`, data)
}

/**
 * 撤回申领：**仅把订单置为已取消，不改袋状态、不释放在持袋数额度**（见设计书 3.2 订单级操作说明）。
 * 袋仍由站点管理员走未使用回收（#4）或回收确认（#5）闭环。
 */
export function cancelBorrow(orderNo) {
  return request.delete(`/borrows/${orderNo}`)
}

/** 标记「已投放回收箱」（选择站点，记 return_station_id；袋保持使用中，订单→待回收确认） */
export function markReturn(orderNo, data) {
  return request.post(`/orders/${orderNo}/return-mark`, data)
}

/** 本人订单分页查询（状态筛选） */
export function listMyOrders(params) {
  return request.get('/orders/mine', params)
}

/** 本人订单详情（校验归属，非本人报 2002） */
export function getOrder(orderNo) {
  return request.get(`/orders/${orderNo}`)
}

/* ------------------------- 以下为平台管理员接口 ------------------------- */

/** 全量订单查询（状态/站点/时间筛选） */
export function listOrders(params) {
  return request.get('/orders', params)
}

/** 超时未归还订单列表（timeout_flag=1；超时是标记不是状态，见设计书 3.6） */
export function listTimeoutOrders(params) {
  return request.get('/orders/timeout', params)
}

/** 确认遗失（袋→已遗失，订单→已遗失（含已取消订单终态覆盖），写责任记录） */
export function confirmLost(orderNo, data) {
  return request.post(`/orders/${orderNo}/lost-confirm`, data)
}
