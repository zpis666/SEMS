<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatCard from '@/components/StatCard.vue'
import { getDashboard, getOrderTrend, getBagTurnover, getStockDistribution } from '@/api/stats'
import { BAG_STATUS_META } from '@/constants'
import { useWebSocketStore } from '@/stores/websocket'

/**
 * 平台端数据看板（《系统设计说明书》5.6 / 10.2 / 10.5「看板」条，需求 FR-M6-01~05、FR-M6-12）。
 *
 *  - KPI 四列卡（在用袋数、在循环袋数、今日取袋、今日归还）：1200px 以上四列、768–1200 两列、768 以下单列；
 *  - 告警位：审计队列丢弃数 auditDropCount 与库存对账差异有值时展示，点击跳转 /platform/alerts；
 *  - 三张图表：订单趋势折线、库存分布饼图（最多三类）、按站点周转率柱状；
 *  - 每块数据独立维护「加载骨架 / 空态 / 失败重试」三态；
 *  - 订阅 WebSocket 主题 platform/dashboard，命中推送时静默重拉（设计书 7.3）。
 *
 * 数据字段说明：统计接口的字段名尚未在本波前端契约中逐字固化，
 * 这里按「语义候选名」做兼容读取（见 pick / countOf），字段缺失时一律按 0 处理，绝不抛错白屏。
 */

const router = useRouter()
const wsStore = useWebSocketStore()

/* ------------------------------ 四块数据各自的加载状态 ------------------------------ */

const summaryState = reactive({ status: 'loading', message: '', raw: null })
const trendState = reactive({ status: 'loading', message: '', raw: null })
const distributionState = reactive({ status: 'loading', message: '', raw: null })
const turnoverState = reactive({ status: 'loading', message: '', raw: null })

/** 看板最近一次成功更新的时刻（工具栏展示） */
const updatedAt = ref('')
/** 手动刷新中（避免重复触发） */
const refreshing = ref(false)

/* ------------------------------ 取值兼容工具 ------------------------------ */

/** 依次尝试候选字段名，返回首个非空值 */
function pick(source, keys, fallback = 0) {
  if (!source || typeof source !== 'object') return fallback
  for (const key of keys) {
    const value = source[key]
    if (value !== undefined && value !== null && value !== '') return value
  }
  return fallback
}

function toNumber(value, fallback = 0) {
  const num = Number(value)
  return Number.isFinite(num) ? num : fallback
}

/** 计数值兼容：数字直接用；数组取长度；对象取 count / diffCount / total */
function countOf(value) {
  if (Array.isArray(value)) return value.length
  if (value && typeof value === 'object') {
    return toNumber(value.count ?? value.diffCount ?? value.total ?? 0)
  }
  return toNumber(value, 0)
}

function toNumberList(value) {
  return Array.isArray(value) ? value.map((item) => toNumber(item, 0)) : []
}

/* ------------------------------ KPI 与告警位 ------------------------------ */

const kpi = computed(() => {
  const raw = summaryState.raw
  return {
    inUse: toNumber(pick(raw, ['inUseCount', 'inUseBagCount', 'inUse'])),
    inCycle: toNumber(pick(raw, ['inCycleCount', 'inCycleBagCount', 'inCycle'])),
    todayBorrow: toNumber(pick(raw, ['todayBorrowCount', 'todayBorrow', 'todayBorrowNum'])),
    todayReturn: toNumber(pick(raw, ['todayReturnCount', 'todayReturn', 'todayReturnNum'])),
    auditDropCount: countOf(pick(raw, ['auditDropCount', 'auditDroppedCount'], 0)),
    reconcileDiff: countOf(
      pick(raw, ['reconcileDiffCount', 'reconcileDiff', 'stockReconcileDiff', 'reconcileDiffNum'], 0),
    ),
  }
})

/** 告警条：有值才显示（设计书 10.2「含审计队列丢弃与对账差异告警位」） */
const alerts = computed(() => {
  const list = []
  if (kpi.value.auditDropCount > 0) {
    list.push({
      key: 'audit',
      text: `审计队列已丢弃 ${kpi.value.auditDropCount} 条事件（审计写入降级为应急文件），可靠性需人工核查`,
    })
  }
  if (kpi.value.reconcileDiff > 0) {
    list.push({
      key: 'reconcile',
      text: `检测到库存对账差异 ${kpi.value.reconcileDiff} 项（Redis 计数与数据库口径不一致），已影响可用库存口径`,
    })
  }
  return list
})

function goAlerts() {
  router.push('/platform/alerts')
}

/* ------------------------------ 图表数据归一化 ------------------------------ */

/** 订单趋势：兼容「点集数组」与「平行数组」两种常见返回 */
function normalizeTrend(raw) {
  const empty = { dates: [], borrow: [], returned: [] }
  if (!raw) return empty
  const list = Array.isArray(raw) ? raw : pick(raw, ['records', 'items', 'list', 'trend', 'points'], null)
  if (Array.isArray(list)) {
    return {
      dates: list.map((item) => String(pick(item, ['date', 'day', 'statDate', 'label'], ''))),
      borrow: list.map((item) => toNumber(pick(item, ['borrowCount', 'borrow', 'borrowNum', 'ordersBorrow']), 0)),
      returned: list.map((item) =>
        toNumber(pick(item, ['returnCount', 'returnedCount', 'return', 'returned', 'returnNum', 'ordersReturn']), 0),
      ),
    }
  }
  const dates = pick(raw, ['dates', 'days', 'dateList', 'xAxis'], [])
  return {
    dates: Array.isArray(dates) ? dates.map(String) : [],
    borrow: toNumberList(pick(raw, ['borrowCounts', 'borrowList', 'borrow', 'borrowNums'], [])),
    returned: toNumberList(pick(raw, ['returnCounts', 'returnList', 'returnedCounts', 'returned', 'return', 'returnNums'], [])),
  }
}

/** 库存分布：兼容「{name,value} 列表」「{status,count} 列表」「{状态码: 数量} 映射」三类返回，并收敛为最多三类 */
function normalizeDistribution(raw) {
  const list = []
  const source = Array.isArray(raw)
    ? raw
    : pick(raw, ['items', 'distribution', 'list', 'records', 'statusDistribution', 'stockList'], null)
  if (Array.isArray(source)) {
    source.forEach((item) => {
      if (!item || typeof item !== 'object') return
      const code = String(pick(item, ['status', 'code'], ''))
      const name = String(
        pick(item, ['name', 'label', 'stationName', 'statusName'], '') || BAG_STATUS_META[code]?.label || '',
      )
      if (!name) return
      list.push({ name, value: toNumber(pick(item, ['value', 'count', 'num', 'total'], 0)) })
    })
  } else if (source && typeof source === 'object') {
    Object.entries(source).forEach(([key, value]) => {
      list.push({ name: BAG_STATUS_META[key]?.label || key, value: toNumber(value, 0) })
    })
  }
  const sorted = list.filter((item) => item.value > 0).sort((a, b) => b.value - a.value)
  if (sorted.length <= 3) return sorted
  // 饼图不超过三类（设计书 10.5 禁止事项 4）：保留占比最高的两类，其余合并为「其他」
  const other = sorted.slice(2).reduce((sum, item) => sum + item.value, 0)
  return [...sorted.slice(0, 2), { name: '其他', value: other }]
}

/** 周转率：按站点的柱状数据 */
function normalizeTurnover(raw) {
  const source = Array.isArray(raw)
    ? raw
    : pick(raw, ['items', 'list', 'records', 'stations', 'stationTurnover', 'turnoverList'], null)
  if (!Array.isArray(source)) return []
  return source
    .filter((item) => item && typeof item === 'object')
    .map((item) => ({
      name: String(pick(item, ['stationName', 'name', 'label', 'station'], '') || '—'),
      value: toNumber(pick(item, ['turnoverRate', 'rate', 'value', 'turnover', 'ratio'], 0)),
    }))
}

const trendView = computed(() => normalizeTrend(trendState.raw))
const distributionView = computed(() => normalizeDistribution(distributionState.raw))
const turnoverView = computed(() => normalizeTurnover(turnoverState.raw))

/* ------------------------------ ECharts 令牌取色 ------------------------------ */

/**
 * 读取设计令牌的计算值。
 *
 * ECharts 用 canvas 绘制，option 里的颜色**不能**写 'var(--sems-chart-1)' 这样的
 * CSS 变量字符串——canvas 不解析 CSS 变量，会退化成默认色或黑色。
 * 因此必须先用 getComputedStyle(document.documentElement).getPropertyValue(...)
 * 读出计算后的真实色值（并 trim 掉首尾空白），再传给 option。
 */
function readToken(name) {
  if (typeof window === 'undefined' || typeof document === 'undefined') return ''
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

/** 读取令牌里的数值（如 12px、4px），供 ECharts 的纯数字参数使用 */
function readTokenNumber(name, fallback) {
  const parsed = Number.parseFloat(readToken(name))
  return Number.isFinite(parsed) ? parsed : fallback
}

/** 图表色板：只用 --sems-chart-1..5（设计书 10.5 禁止色板外的高饱和色） */
function readChartColors() {
  return [1, 2, 3, 4, 5].map((index) => readToken(`--sems-chart-${index}`))
}

/** 轴标签：12px 次要色（设计书 10.5「轴标 12px 次要色」） */
function axisLabelStyle() {
  return {
    color: readToken('--sems-text-secondary'),
    fontSize: readTokenNumber('--sems-font-size-xs', 12),
  }
}

function commonAxis() {
  return {
    axisLine: { lineStyle: { color: readToken('--sems-border') } },
    axisTick: { show: false },
    axisLabel: axisLabelStyle(),
  }
}

/** tooltip：白底浮层 + 二级阴影 + 等宽数字（设计书 10.5） */
function commonTooltip(trigger = 'axis') {
  return {
    trigger,
    backgroundColor: readToken('--sems-bg-card'),
    borderColor: readToken('--sems-border'),
    borderWidth: 1,
    textStyle: { color: readToken('--sems-text-regular') },
    extraCssText: `box-shadow: ${readToken('--sems-shadow-popup')}; border-radius: ${readToken(
      '--sems-radius-base',
    )}; font-variant-numeric: tabular-nums;`,
  }
}

/** 图例常驻（设计书 10.5「图例常驻并直接标注」） */
function commonLegend() {
  return {
    show: true,
    top: 0,
    icon: 'roundRect',
    itemWidth: 12,
    itemHeight: 8,
    textStyle: {
      color: readToken('--sems-text-secondary'),
      fontSize: readTokenNumber('--sems-font-size-xs', 12),
    },
  }
}

function borderSplitLine() {
  return { lineStyle: { color: readToken('--sems-border') } }
}

/* ------------------------------ 三张图表的 option ------------------------------ */

function buildTrendOption() {
  const colors = readChartColors()
  const view = trendView.value
  return {
    color: [colors[0], colors[1]],
    tooltip: commonTooltip('axis'),
    legend: commonLegend(),
    grid: { left: 0, right: 8, top: 36, bottom: 0, containLabel: true },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: view.dates,
      ...commonAxis(),
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: axisLabelStyle(),
      splitLine: borderSplitLine(),
    },
    series: [
      {
        name: '取袋',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2 },
        data: view.borrow,
      },
      {
        name: '归还',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2 },
        data: view.returned,
      },
    ],
  }
}

function buildDistributionOption() {
  const colors = readChartColors()
  const items = distributionView.value
  return {
    color: colors.slice(0, Math.max(items.length, 1)),
    tooltip: {
      ...commonTooltip('item'),
      formatter: '{b}：{c}（{d}%）',
    },
    legend: {
      ...commonLegend(),
      top: 'auto',
      bottom: 0,
    },
    series: [
      {
        name: '库存分布',
        type: 'pie',
        radius: '62%',
        center: ['50%', '46%'],
        avoidLabelOverlap: true,
        label: {
          show: true,
          formatter: '{b} {d}%',
          color: readToken('--sems-text-regular'),
          fontSize: readTokenNumber('--sems-font-size-xs', 12),
        },
        labelLine: {
          length: 8,
          length2: 8,
          lineStyle: { color: readToken('--sems-border') },
        },
        itemStyle: {
          borderColor: readToken('--sems-bg-card'),
          borderWidth: 2,
        },
        data: items,
      },
    ],
  }
}

function buildTurnoverOption() {
  const colors = readChartColors()
  const items = turnoverView.value
  const radius = readTokenNumber('--sems-radius-tag', 4)
  return {
    color: [colors[2]],
    tooltip: {
      ...commonTooltip('axis'),
      axisPointer: { type: 'shadow' },
    },
    legend: commonLegend(),
    grid: { left: 0, right: 8, top: 36, bottom: 0, containLabel: true },
    xAxis: {
      type: 'category',
      data: items.map((item) => item.name),
      ...commonAxis(),
      axisLabel: { ...axisLabelStyle(), interval: 0, hideOverlap: true },
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: axisLabelStyle(),
      splitLine: borderSplitLine(),
    },
    series: [
      {
        name: '周转率',
        type: 'bar',
        barMaxWidth: 36,
        itemStyle: { borderRadius: [radius, radius, 0, 0] },
        data: items.map((item) => item.value),
      },
    ],
  }
}

/* ------------------------------ 图表实例管理（手动 init / resize / dispose） ------------------------------ */

const trendEl = ref(null)
const distributionEl = ref(null)
const turnoverEl = ref(null)

const chartInstances = {}
const resizeObservers = {}
let windowResizeBound = false

function resizeAllCharts() {
  Object.values(chartInstances).forEach((instance) => instance.resize())
}

/** 销毁单个图表实例与它的监听（重新 init 前、或数据变空时调用） */
function releaseChart(key) {
  if (resizeObservers[key]) {
    resizeObservers[key].disconnect()
    delete resizeObservers[key]
  }
  if (chartInstances[key]) {
    chartInstances[key].dispose()
    delete chartInstances[key]
  }
}

/** 按容器元素确保图表实例存在；容器被 v-if 换掉时重建，避免在旧 DOM 上残留 */
function ensureChart(key, el) {
  const existing = chartInstances[key]
  if (existing && existing.getDom() === el) return existing
  releaseChart(key)
  const instance = echarts.init(el)
  chartInstances[key] = instance
  if (typeof ResizeObserver !== 'undefined') {
    // 容器尺寸变化（栅格换行、侧边栏折叠）时自适应
    const observer = new ResizeObserver(() => instance.resize())
    observer.observe(el)
    resizeObservers[key] = observer
  } else if (!windowResizeBound) {
    // 兜底：无 ResizeObserver 的环境退回 window resize
    window.addEventListener('resize', resizeAllCharts)
    windowResizeBound = true
  }
  return instance
}

function renderTrend() {
  const el = trendEl.value
  if (!el) return
  ensureChart('trend', el).setOption(buildTrendOption(), true)
}

function renderDistribution() {
  const el = distributionEl.value
  if (!el) return
  ensureChart('distribution', el).setOption(buildDistributionOption(), true)
}

function renderTurnover() {
  const el = turnoverEl.value
  if (!el) return
  ensureChart('turnover', el).setOption(buildTurnoverOption(), true)
}

/** 页面卸载：dispose 全部实例并解绑所有监听（设计书要求，防内存泄漏） */
function disposeCharts() {
  Object.keys(chartInstances).forEach((key) => releaseChart(key))
  Object.keys(resizeObservers).forEach((key) => {
    resizeObservers[key].disconnect()
    delete resizeObservers[key]
  })
  if (windowResizeBound) {
    window.removeEventListener('resize', resizeAllCharts)
    windowResizeBound = false
  }
}

/* ------------------------------ 数据加载（静默刷新与失败保留） ------------------------------ */

const DEFAULT_ERROR = '数据加载失败，请重试'

/**
 * 失败处理：静默刷新（WS 推送触发）时若有旧数据则保留并轻提示；
 * 首次加载或页面上的「重试」按钮触发时展示错误态。
 */
function handleFailure(state, error, silent) {
  if (silent && state.raw) {
    ElMessage.error('看板刷新失败，已保留当前数据')
    return
  }
  state.status = 'error'
  state.message = error?.message || DEFAULT_ERROR
}

async function loadSummary(silent = false) {
  if (silent && summaryState.status === 'loading') return
  if (!silent) summaryState.status = 'loading'
  try {
    summaryState.raw = await getDashboard()
    summaryState.status = 'ready'
    summaryState.message = ''
  } catch (error) {
    handleFailure(summaryState, error, silent)
  }
}

async function loadTrend(silent = false) {
  if (silent && trendState.status === 'loading') return
  if (!silent) trendState.status = 'loading'
  try {
    trendState.raw = await getOrderTrend({ days: 7 })
    trendState.status = 'ready'
    trendState.message = ''
    await nextTick()
    if (trendView.value.dates.length) renderTrend()
    else releaseChart('trend')
  } catch (error) {
    handleFailure(trendState, error, silent)
    if (trendState.status === 'error') releaseChart('trend')
  }
}

async function loadDistribution(silent = false) {
  if (silent && distributionState.status === 'loading') return
  if (!silent) distributionState.status = 'loading'
  try {
    distributionState.raw = await getStockDistribution()
    distributionState.status = 'ready'
    distributionState.message = ''
    await nextTick()
    if (distributionView.value.length) renderDistribution()
    else releaseChart('distribution')
  } catch (error) {
    handleFailure(distributionState, error, silent)
    if (distributionState.status === 'error') releaseChart('distribution')
  }
}

async function loadTurnover(silent = false) {
  if (silent && turnoverState.status === 'loading') return
  if (!silent) turnoverState.status = 'loading'
  try {
    turnoverState.raw = await getBagTurnover()
    turnoverState.status = 'ready'
    turnoverState.message = ''
    await nextTick()
    if (turnoverView.value.length) renderTurnover()
    else releaseChart('turnover')
  } catch (error) {
    handleFailure(turnoverState, error, silent)
    if (turnoverState.status === 'error') releaseChart('turnover')
  }
}

function markUpdated() {
  updatedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

/** 首次加载：四块并行拉取，各自维护状态 */
async function loadAll() {
  await Promise.all([loadSummary(), loadTrend(), loadDistribution(), loadTurnover()])
  markUpdated()
}

/** 刷新（WS 推送与工具栏按钮共用）：静默刷新保留旧数据，避免闪屏 */
async function refreshAll() {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await Promise.all([
      loadSummary(true),
      loadTrend(true),
      loadDistribution(true),
      loadTurnover(true),
    ])
    markUpdated()
  } finally {
    refreshing.value = false
  }
}

/* ------------------------------ WebSocket 实时刷新 ------------------------------ */

/** 本页订阅主题：与后端 WsSessionManager.TOPIC_PLATFORM_DASHBOARD 一致（设计书 7.3 主题表） */
const DASHBOARD_TOPIC = 'platform/dashboard'

let refreshTimer = null

watch(
  () => wsStore.lastMessage,
  (message) => {
    if (!message) return
    // 只响应本页订阅的主题；消息体未带 topic 时放行，避免因后端报文差异漏刷新
    if (message.topic && message.topic !== DASHBOARD_TOPIC) return
    // 合并短时间内的多条推送，避免刷新风暴
    if (refreshTimer) clearTimeout(refreshTimer)
    refreshTimer = setTimeout(() => {
      refreshTimer = null
      refreshAll()
    }, 800)
  },
)

/**
 * 强制重发订阅：store 断线时会清空订阅集合，且 subscribe() 按集合去重，
 * 因此「重新鉴权成功」与「进页面时连接尚未就绪」两种情形都要退订再订阅才发得出去；
 * 否则重连后看板再也收不到 platform/dashboard 推送、实时刷新静默失效（设计书 7.3）。
 */
function resendDashboardTopic() {
  wsStore.unsubscribe(DASHBOARD_TOPIC)
  wsStore.subscribe(DASHBOARD_TOPIC)
}

watch(
  () => wsStore.authed,
  (authed) => {
    if (authed) resendDashboardTopic()
  },
)

/**
 * 回前台兜底刷新：App.vue 在 visibilitychange 时广播全局事件 `sems:visibility-resume`
 * （设计书 7.3：不做消息 ACK/重放，回前台除重连外还要重新拉取当前页数据兜底）。
 */
function handleVisibilityResume() {
  refreshAll()
}

onMounted(() => {
  wsStore.subscribe(DASHBOARD_TOPIC)
  window.addEventListener('sems:visibility-resume', handleVisibilityResume)
  loadAll()
})

onUnmounted(() => {
  wsStore.unsubscribe(DASHBOARD_TOPIC)
  window.removeEventListener('sems:visibility-resume', handleVisibilityResume)
  if (refreshTimer) {
    clearTimeout(refreshTimer)
    refreshTimer = null
  }
  // 手动初始化的实例必须手动销毁
  disposeCharts()
})
</script>

<template>
  <div class="dashboard">
    <section class="dashboard__hero">
      <div class="dashboard__hero-copy">
        <p class="dashboard__eyebrow"><span class="dashboard__live-dot" /> CIRCULAR OPERATIONS</p>
        <h2>让每一次循环，都清晰可见。</h2>
        <p>聚合取袋、归还与站点库存，以数据驱动更高效的绿色运营。</p>
      </div>
      <div class="dashboard__hero-cycle" aria-hidden="true">
        <span><el-icon :size="30"><Refresh /></el-icon></span>
        <div><strong>取 · 用 · 还 · 洗 · 调</strong><small>一袋一码，全程可追溯</small></div>
      </div>
      <div class="dashboard__toolbar">
        <span><el-icon><Clock /></el-icon>更新于 <span class="dashboard__time">{{ updatedAt || '—' }}</span></span>
        <el-button class="dashboard__refresh" :loading="refreshing" @click="refreshAll()"><el-icon v-if="!refreshing"><Refresh /></el-icon>刷新数据</el-button>
      </div>
    </section>

    <!-- 告警位：审计队列丢弃 / 库存对账差异，有值才显示，点击跳转异常与风控告警 -->
    <div v-if="alerts.length" class="dashboard__alerts">
      <el-alert
        v-for="item in alerts"
        :key="item.key"
        class="dashboard__alert"
        type="warning"
        :closable="false"
        show-icon
        role="button"
        tabindex="0"
        @click="goAlerts"
        @keydown.enter="goAlerts"
      >
        <template v-slot:title>
          <span>{{ item.text }}</span>
          <span class="dashboard__alert-action">去处置</span>
        </template>
      </el-alert>
    </div>

    <!-- KPI 四列卡：1200px 以上四列、768–1200 两列、768 以下单列 -->
    <section class="dashboard__kpi" aria-label="核心指标">
      <div v-if="summaryState.status === 'loading'" class="dashboard__kpi-skeleton">
        <PageSkeleton type="card" :rows="4" />
      </div>
      <div v-else-if="summaryState.status === 'error'" class="dashboard__block dashboard__kpi-error">
        <EmptyState icon="Warning" :description="summaryState.message">
          <el-button type="primary" @click="loadSummary()">重试</el-button>
        </EmptyState>
      </div>
      <template v-else>
        <StatCard label="在用袋数" :value="kpi.inUse" unit="只" icon="Box" hint="正在为用户提供服务" />
        <StatCard label="在循环袋数" :value="kpi.inCycle" unit="只" icon="Refresh" accent="blue" hint="持续流转的快递袋" />
        <StatCard label="今日取袋" :value="kpi.todayBorrow" unit="单" icon="Download" accent="amber" hint="今日累计取袋订单" />
        <StatCard label="今日归还" :value="kpi.todayReturn" unit="单" icon="Upload" accent="green" hint="今日累计归还订单" />
      </template>
    </section>

    <!-- 图表三块：趋势折线 / 库存分布饼图 / 周转率柱状 -->
    <section class="dashboard__charts" aria-label="统计图表">
      <PageCard title="订单趋势" subtitle="近 7 日取袋与归还订单量" class="dashboard__chart-wide">
        <div class="dashboard__chart-box">
          <div v-if="trendState.status === 'loading'" class="dashboard__chart-skeleton">
            <PageSkeleton type="chart" />
          </div>
          <div v-else-if="trendState.status === 'error'" class="dashboard__block dashboard__chart-block">
            <EmptyState icon="Warning" :description="trendState.message">
              <el-button type="primary" @click="loadTrend()">重试</el-button>
            </EmptyState>
          </div>
          <div v-else-if="!trendView.dates.length" class="dashboard__chart-empty">
            <EmptyState icon="DataLine" description="暂无订单趋势数据">
              <el-button @click="refreshAll()">刷新</el-button>
            </EmptyState>
          </div>
          <div v-else ref="trendEl" class="dashboard__chart"></div>
        </div>
      </PageCard>

      <PageCard title="库存分布" subtitle="全平台各状态袋数占比（最多展示三类）">
        <div class="dashboard__chart-box">
          <div v-if="distributionState.status === 'loading'" class="dashboard__chart-skeleton">
            <PageSkeleton type="chart" />
          </div>
          <div v-else-if="distributionState.status === 'error'" class="dashboard__block dashboard__chart-block">
            <EmptyState icon="Warning" :description="distributionState.message">
              <el-button type="primary" @click="loadDistribution()">重试</el-button>
            </EmptyState>
          </div>
          <div v-else-if="!distributionView.length" class="dashboard__chart-empty">
            <EmptyState icon="PieChart" description="暂无库存分布数据">
              <el-button @click="refreshAll()">刷新</el-button>
            </EmptyState>
          </div>
          <div v-else ref="distributionEl" class="dashboard__chart"></div>
        </div>
      </PageCard>

      <PageCard
        title="周转率"
        subtitle="按站点统计（口径：完成归还确认订单数 ÷ 在循环袋数均值）"
        class="dashboard__chart-full"
      >
        <div class="dashboard__chart-box">
          <div v-if="turnoverState.status === 'loading'" class="dashboard__chart-skeleton">
            <PageSkeleton type="chart" />
          </div>
          <div v-else-if="turnoverState.status === 'error'" class="dashboard__block dashboard__chart-block">
            <EmptyState icon="Warning" :description="turnoverState.message">
              <el-button type="primary" @click="loadTurnover()">重试</el-button>
            </EmptyState>
          </div>
          <div v-else-if="!turnoverView.length" class="dashboard__chart-empty">
            <EmptyState icon="Histogram" description="暂无周转率数据">
              <el-button @click="refreshAll()">刷新</el-button>
            </EmptyState>
          </div>
          <div v-else ref="turnoverEl" class="dashboard__chart"></div>
        </div>
      </PageCard>
    </section>
  </div>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-5);
}

/* ---------------- 运营概览 ---------------- */
.dashboard__hero {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(260px, 1fr);
  gap: var(--sems-space-5);
  padding: var(--sems-space-6);
  overflow: hidden;
  color: var(--sems-text-inverse);
  background: var(--sems-bg-hero);
  border-radius: var(--sems-radius-card);
  box-shadow: var(--sems-shadow-card);
}
.dashboard__hero::after {
  position: absolute;
  width: 280px;
  height: 280px;
  right: -110px;
  top: -160px;
  border: 1px solid color-mix(in srgb, var(--sems-text-inverse) 14%, transparent);
  border-radius: 50%;
  content: '';
}
.dashboard__eyebrow { display: flex; align-items: center; gap: var(--sems-space-2); margin: 0 0 var(--sems-space-3); color: var(--sems-text-on-brand-soft); font-size: var(--sems-font-size-xs); letter-spacing: 0.15em; }
.dashboard__live-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--sems-text-on-brand-soft); }
.dashboard__hero h2 { margin: 0; font-size: var(--sems-font-size-kpi); font-weight: var(--sems-font-weight-bold); letter-spacing: -0.03em; }
.dashboard__hero-copy > p:last-child { margin: var(--sems-space-3) 0 0; color: var(--sems-text-on-brand-soft); line-height: 1.8; }
.dashboard__hero-cycle { display: flex; align-items: center; justify-content: flex-end; gap: var(--sems-space-4); z-index: 1; }
.dashboard__hero-cycle > span { display: inline-flex; align-items: center; justify-content: center; width: 68px; height: 68px; border: 1px solid color-mix(in srgb, var(--sems-text-inverse) 20%, transparent); border-radius: 50%; background: color-mix(in srgb, var(--sems-text-inverse) 8%, transparent); }
.dashboard__hero-cycle div { display: flex; flex-direction: column; gap: var(--sems-space-2); }
.dashboard__hero-cycle strong { font-weight: var(--sems-font-weight-medium); letter-spacing: 0.08em; }
.dashboard__hero-cycle small { color: var(--sems-text-on-brand-soft); font-size: var(--sems-font-size-xs); }
.dashboard__toolbar { grid-column: 1 / -1; display: flex; align-items: center; justify-content: space-between; gap: var(--sems-space-3); padding-top: var(--sems-space-4); border-top: 1px solid color-mix(in srgb, var(--sems-text-inverse) 14%, transparent); }
.dashboard__toolbar > span { display: flex; align-items: center; gap: var(--sems-space-2); color: var(--sems-text-on-brand-soft); font-size: var(--sems-font-size-xs); }
.dashboard__time { font-variant-numeric: tabular-nums; }
.dashboard__refresh.el-button { min-height: 34px; color: var(--sems-text-inverse); border-color: color-mix(in srgb, var(--sems-text-inverse) 24%, transparent); background: color-mix(in srgb, var(--sems-text-inverse) 10%, transparent); box-shadow: none; }
.dashboard__refresh.el-button:hover { background: color-mix(in srgb, var(--sems-text-inverse) 16%, transparent); }

/* ---------------- 告警位 ---------------- */
.dashboard__alerts {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
}
.dashboard__alert {
  cursor: pointer;
}
.dashboard__alert-action {
  margin-left: var(--sems-space-2);
  font-weight: var(--sems-font-weight-medium);
  text-decoration: underline;
}

/* ---------------- KPI ---------------- */
.dashboard__kpi {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sems-space-4);
}
.dashboard__kpi-skeleton,
.dashboard__kpi-error {
  grid-column: 1 / -1;
}

/* ---------------- 图表栅格 ---------------- */
.dashboard__charts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sems-space-5);
}
.dashboard__chart-wide {
  grid-column: span 2;
}
.dashboard__chart-full {
  grid-column: 1 / -1;
}

/* 占位盒与真实图表同高（设计书 10.5：占位尺寸与真实内容一致，不得跳版） */
.dashboard__chart-box {
  position: relative;
  height: 320px;
}
.dashboard__chart {
  width: 100%;
  height: 100%;
}
/* 骨架屏用与图表等高的占位盒裁剪：加载完成时不跳版 */
.dashboard__chart-skeleton {
  width: 100%;
  height: 100%;
  overflow: hidden;
}
.dashboard__chart-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

/* 错误/空态兜底块 */
.dashboard__block {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 160px;
  padding: var(--sems-space-5);
  background-color: var(--sems-bg-card);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-card);
}
.dashboard__chart-block {
  height: 100%;
  min-height: 0;
}

/* 768–1200px：KPI 两列，趋势通栏，饼图与柱状各占一列 */
@media (max-width: 1199px) {
  .dashboard__kpi {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .dashboard__charts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .dashboard__chart-wide {
    grid-column: 1 / -1;
  }
  .dashboard__chart-full {
    grid-column: auto;
  }
}

/* 768px 以下：全站单列 */
@media (max-width: 767px) {
  .dashboard__hero { grid-template-columns: 1fr; padding: var(--sems-space-5); }
  .dashboard__hero h2 { font-size: var(--sems-font-size-xl); }
  .dashboard__hero-cycle { justify-content: flex-start; }
  .dashboard__hero-cycle > span { width: 48px; height: 48px; }
  .dashboard__kpi { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--sems-space-3); }
  .dashboard__charts { grid-template-columns: 1fr; }
  .dashboard__chart-wide,
  .dashboard__chart-full {
    grid-column: auto;
  }
  .dashboard__chart-box {
    height: 260px;
  }
}
</style>
