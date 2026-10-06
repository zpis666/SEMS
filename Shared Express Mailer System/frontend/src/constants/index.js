/**
 * 全站枚举与文案映射 —— 前端侧唯一出处。
 *
 * 口径来源：《系统设计说明书》3.1（袋 11 态）、3.6（订单 6 态 + 超时标记）、10.5（状态色映射）、
 * 6.4（错误码）。**颜色不写在这里**——状态色通过 CSS 变量名引用，取色一律走 styles/tokens.css，
 * 组件按 `variant` 决定用描边还是实心浅底（设计书 10.5：颜色相同者靠「描边/实心」区分）。
 */

/** 快递袋状态（11 态，码值与后端 bag.status 一致） */
export const BAG_STATUS = {
  INACTIVE: 'INACTIVE',
  IDLE: 'IDLE',
  ALLOCATED: 'ALLOCATED',
  IN_USE: 'IN_USE',
  RETURNING: 'RETURNING',
  TO_DISINFECT: 'TO_DISINFECT',
  REUSABLE: 'REUSABLE',
  TRANSFERRING: 'TRANSFERRING',
  REPAIRING: 'REPAIRING',
  LOST: 'LOST',
  SCRAPPED: 'SCRAPPED',
}

/**
 * 袋状态展示元数据。
 * variant：outline=描边、solid=实心浅底（同色两态靠它区分，见 10.5）
 * token：CSS 变量名（不含 var()），组件里用 `var(${token})` 取色
 */
export const BAG_STATUS_META = {
  INACTIVE: { label: '未激活', token: '--sems-bag-inactive', variant: 'outline' },
  IDLE: { label: '空闲', token: '--sems-bag-idle', variant: 'outline' },
  ALLOCATED: { label: '已分配', token: '--sems-bag-allocated', variant: 'solid' },
  IN_USE: { label: '使用中', token: '--sems-bag-in-use', variant: 'solid' },
  RETURNING: { label: '回收在途', token: '--sems-bag-returning', variant: 'solid' },
  TO_DISINFECT: { label: '待消毒', token: '--sems-bag-to-disinfect', variant: 'solid' },
  REUSABLE: { label: '可复用', token: '--sems-bag-reusable', variant: 'solid' },
  TRANSFERRING: { label: '调拨在途', token: '--sems-bag-transferring', variant: 'outline' },
  REPAIRING: { label: '维修中', token: '--sems-bag-repairing', variant: 'solid' },
  LOST: { label: '已遗失', token: '--sems-bag-lost', variant: 'outline' },
  SCRAPPED: { label: '报废', token: '--sems-bag-scrapped', variant: 'solid' },
}

/** 订单状态（6 态，码值与后端 bag_order.status 一致） */
export const ORDER_STATUS = {
  PENDING_SEND: 'PENDING_SEND',
  IN_USE: 'IN_USE',
  PENDING_CONFIRM: 'PENDING_CONFIRM',
  FINISHED: 'FINISHED',
  CANCELLED: 'CANCELLED',
  LOST: 'LOST',
}

export const ORDER_STATUS_META = {
  PENDING_SEND: { label: '待寄件', token: '--sems-order-pending-send', variant: 'outline' },
  IN_USE: { label: '使用中', token: '--sems-order-in-use', variant: 'solid' },
  PENDING_CONFIRM: { label: '待回收确认', token: '--sems-order-pending-confirm', variant: 'outline' },
  FINISHED: { label: '已完成', token: '--sems-order-finished', variant: 'solid' },
  CANCELLED: { label: '已取消', token: '--sems-order-cancelled', variant: 'outline' },
  LOST: { label: '已遗失', token: '--sems-order-lost', variant: 'outline' },
}

/** 袋状态流转事件（与后端 BagEvent 20 个码值一致，用于流转时间线的节点文案） */
export const BAG_EVENT_LABEL = {
  ACTIVATE: '激活上架',
  BORROW: '扫码申领',
  SHIP: '提交寄件',
  RETURN_MARK: '标记归还',
  RECOVER_UNUSED: '未使用回收',
  RECOVER_CONFIRM: '回收确认',
  RECOVER_DIRECT: '回收确认（直投）',
  DISINFECT_PASS: '消毒完成（合格）',
  DISINFECT_FAIL: '消毒完成（不合格）',
  SHELVE: '上架入库',
  REPAIR_START: '转维修',
  REPAIR_DONE: '修复入库',
  SCRAP: '报废',
  SCRAP_DIRECT: '直接报废',
  ALLOCATE_OUT: '调拨出库',
  ALLOCATE_IN: '调拨入库',
  LOST_CONFIRM: '确认遗失',
  LOST_IN_TRANSIT: '在途遗失确认',
  FOUND: '找回',
  STATUS_CORRECT: '状态修正',
}

/** 调拨单状态（6 态） */
export const ALLOCATION_STATUS_META = {
  PENDING_DISPATCH: { label: '待出库', type: 'info' },
  IN_TRANSIT: { label: '在途', type: 'primary' },
  COMPLETED: { label: '已完成', type: 'success' },
  ABNORMAL: { label: '异常', type: 'warning' },
  CANCELLED: { label: '已取消', type: 'info' },
  TERMINATED: { label: '已终止', type: 'danger' },
}

/** 调拨明细状态（5 态） */
export const ALLOCATION_DETAIL_STATUS_META = {
  PENDING_DISPATCH: { label: '待出库', type: 'info' },
  DISPATCHED: { label: '已出库', type: 'primary' },
  RECEIVED: { label: '已核收', type: 'success' },
  REMOVED: { label: '已剔除', type: 'info' },
  LOST: { label: '已遗失', type: 'danger' },
}

/** 盘点单状态（3 态） */
export const INVENTORY_CHECK_STATUS_META = {
  PENDING_FIX: { label: '待修正', type: 'warning' },
  CLOSED: { label: '已闭环', type: 'success' },
  VOID: { label: '作废', type: 'info' },
}

/** 角色 */
export const ROLE_LABEL = {
  USER: '普通用户',
  STATION_ADMIN: '站点管理员',
  PLATFORM_ADMIN: '平台管理员',
}

/** 账号状态 */
export const USER_STATUS_META = {
  ENABLED: { label: '启用', type: 'success' },
  DISABLED: { label: '禁用', type: 'danger' },
}

/** 站点状态 */
export const STATION_STATUS_META = {
  ENABLED: { label: '启用', type: 'success' },
  DISABLED: { label: '停用', type: 'info' },
}

/** 消毒方式（与 disinfect_record.method 取值对齐） */
export const DISINFECT_METHODS = ['紫外线', '臭氧', '高温', '喷洒']

/** 消毒结果 */
export const DISINFECT_RESULT_META = {
  合格: { label: '合格', type: 'success' },
  不合格: { label: '不合格', type: 'danger' },
  部分不合格: { label: '部分不合格', type: 'warning' },
}

/** 维修结果 */
export const REPAIR_RESULT_META = {
  修复: { label: '修复', type: 'success' },
  无法修复: { label: '无法修复', type: 'danger' },
}

/** 责任记录：业务类型 / 责任类型 / 状态 */
export const LIABILITY_BIZ_TYPE_META = {
  ORDER: { label: '订单', type: 'primary' },
  ALLOCATION: { label: '调拨', type: 'warning' },
}
export const LIABILITY_TYPE_LABEL = {
  遗失: '遗失',
  在途遗失: '在途遗失',
  超时: '超时',
}
export const LIABILITY_STATUS_META = {
  PENDING: { label: '待认定', type: 'warning' },
  CONFIRMED: { label: '已认定', type: 'success' },
}

/**
 * 告警类型（对应 /alerts 的七类，见设计书 5.6）。
 * 前四类由定时任务扫描产生，⑤⑥由对应写入点累计，⑦由确认类操作频次阈值触发。
 */
export const ALERT_TYPE_META = {
  CANCELLED_NOT_RECOVERED: { label: '取消后未回收', desc: '订单取消后满 48 小时袋仍为已分配' },
  RETURNING_TIMEOUT: { label: '回收在途超时', desc: '回收在途满 72 小时未确认' },
  ALLOCATION_TIMEOUT: { label: '调拨未核收', desc: '调拨出库满 48 小时未核收' },
  SHIP_TIMEOUT: { label: '寄件超时未归还', desc: '寄件后满 7 天超时未归还' },
  AUDIT_DROP: { label: '审计队列丢弃', desc: '审计队列饱和，事件降级写入应急文件' },
  STOCK_RECONCILE_DIFF: { label: '库存对账差异', desc: 'Redis 计数与 DB 口径不一致' },
  RISK_CONFIRM_FREQUENCY: { label: '高频确认风控', desc: '同站点同操作人 10 分钟内确认类操作 ≥20 次' },
}

/** 分页默认值（与后端 6.1 一致：pageNum 默认 1、pageSize 默认 10、最大 100） */
export const PAGE_SIZES = [10, 20, 50, 100]
export const DEFAULT_PAGE_SIZE = 10

/** 批量接口上限（前端先拦一道，后端 3001 才是权威；消毒登记是 200 的特例，见 5.4） */
export const BATCH_MAX_SIZE = 500
export const DISINFECT_BATCH_MAX = 200
