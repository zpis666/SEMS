import request from '@/utils/request'

/**
 * 站点间调拨接口（M3 的「调」环节，见《系统设计说明书》5.3 与 3.4.3）。
 * 单据状态机：待出库 --首只出库--> 在途 → 全部核收 --完成--> 已完成；
 * 部分核收/在途遗失确认后单据置「异常」，由平台管理员决定继续核收或终止。
 */

/**
 * 调拨单分页查询。
 * 相关站点管理员会被后端**默认强制过滤**为「源站或目标站=操作人站点」（见设计书 4.4），前端不必也不应自己传站点条件。
 */
export function listAllocations(params) {
  return request.get('/allocations', params)
}

/** 调拨单详情（含明细逐袋核收进度；在途满 48 小时标注超时提示；非源/目标站报 2002） */
export function getAllocation(id) {
  return request.get(`/allocations/${id}`)
}

/** 创建调拨单（源/目标站点、袋码清单）；**建单即锁定明细袋**，被锁定的袋不能再被申领/转维修/报废/修正 */
export function createAllocation(data) {
  return request.post('/allocations', data)
}

/** 取消调拨单（仅「待出库」可取消，明细袋解锁回空闲；否则报 4105） */
export function cancelAllocation(id, data) {
  return request.delete(`/allocations/${id}`, { data })
}

/** 调拨出库（源站站点管理员）：袋 空闲→调拨在途，源站库存 −N；单据状态不允许时报 4105 */
export function dispatchAllocation(id, data) {
  return request.post(`/allocations/${id}/dispatch`, data)
}

/** 调拨核收入库（目标站站点管理员）：袋 调拨在途→空闲（归属改目标站），目标站库存 +N */
export function receiveAllocation(id, data) {
  return request.post(`/allocations/${id}/receive`, data)
}

/** 在途遗失确认（平台管理员）：袋→已遗失，调拨单置异常，写责任记录（责任主体=源站） */
export function confirmAllocationLost(id, data) {
  return request.post(`/allocations/${id}/lost-confirm`, data)
}

/**
 * 剔除明细（平台管理员）：仅「待出库/在途」且该袋未出库**且仍为空闲**时可剔除，袋解锁回空闲。
 * 袋已非空闲（如被转维修/报废/状态修正）时无法剔除，须先终止调拨单再另行处置。
 */
export function removeAllocationDetail(id, data) {
  return request.post(`/allocations/${id}/remove-detail`, data)
}
