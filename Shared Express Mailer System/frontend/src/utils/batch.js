/**
 * 批量接口结果信封解析 —— 全站唯一入口（回收确认 / 上架入库 / 出库 / 消毒登记）。
 *
 * 契约（《系统设计说明书》6.2「批量接口的部分失败」）：四个逐袋批量接口统一返回同一信封——
 * data 为对象；逐袋明细固定放在 data.items（元素字段固定为 bagCode / success / code / message）；
 * 批次计数为 data.total / data.success / data.failed。各接口可在信封上追加批次级字段
 * （回收确认：proxy / crossStation / directWithoutMark；消毒登记：id / batchNo / result）。
 *
 * 这里**只认 data.items**：results / records / details / 裸数组等历史形态一律不再兼容。
 * 单一形态是刻意约束——兼容代码会把契约漂移悄悄盖住（后端改了键名而前端「恰好」还能跑，
 * 联调期就无从发现两端口径已经不一致，缺陷被拖到最后一刻）。格式不符时返回空信封并
 * console.warn，让漂移在控制台立刻可见。
 */

/** 空信封：格式不符合约定时的安全返回值，页面按「本批 0 条」渲染，不阻塞后续操作 */
function emptyEnvelope() {
  return { items: [], total: 0, success: 0, failed: 0 }
}

/** 取后端给的批次计数；字段缺失或非数字时回退到按 items 现算，避免 NaN 渗进界面 */
function resolveCount(value, fallback) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

/**
 * 解析批量接口返回的信封，返回 { items, total, success, failed }。
 *
 * - data 为空：返回空信封（无提交袋等边界场景，不算契约违规）；
 * - data 不是对象、或 items 不是数组：返回空信封并 console.warn（显式的契约漂移信号）；
 * - 计数优先用后端给的 total / success / failed（批量上限 500，items 可能被后端截断，
 *   计数以后端为准），缺失时按 items 现算。
 */
export function extractBatch(data) {
  if (data === null || data === undefined) return emptyEnvelope()
  if (typeof data !== 'object' || Array.isArray(data) || !Array.isArray(data.items)) {
    console.warn('批量接口返回格式不符合约定：期望 data.items 为数组', data)
    return emptyEnvelope()
  }
  const { items } = data
  return {
    items,
    total: resolveCount(data.total, items.length),
    success: resolveCount(data.success, items.filter((item) => item && item.success).length),
    failed: resolveCount(data.failed, items.filter((item) => !(item && item.success)).length),
  }
}
