<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getStationStock, getRecycleBin, listStockWarningStations } from '@/api/station'
import { BAG_STATUS_META } from '@/constants'
import { useUserStore } from '@/stores/user'
import { useWebSocketStore } from '@/stores/websocket'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatCard from '@/components/StatCard.vue'
import StatusTag from '@/components/StatusTag.vue'

/**
 * 站点端 · 工作台（设计书 4.3 / 10.5）。
 *
 * 1) 顶部四张 KPI 卡取本站空闲/使用中/待消毒/可复用四个状态数（getStationStock）；
 * 2) 下方是全部 11 态明细（状态一律走 StatusTag 的「色 + 文案」双通道）；
 * 3) 预警条：可用库存低于站点预警阈值时置顶提示。阈值优先取 stock 接口返回的
 *    warnThreshold；接口未返回时回退查「预警站点列表」判断本站是否在列——
 *    该接口仅平台管理员可调（设计书 5.3），站点管理员直接跳过，避免无谓的 403；
 * 4) 待办区取回收箱待确认袋数，点击进入回收确认页；
 * 5) 订阅 station/{id}/stock 与 station/{id}/recycle，收到推送即重新拉数据。
 */

const router = useRouter()
const userStore = useUserStore()
const wsStore = useWebSocketStore()

const stationId = computed(() => userStore.stationId)

/* ------------------------------ 页面状态 ------------------------------ */

const loading = ref(true)
const refreshing = ref(false)
const stockLoaded = ref(false)
const errorText = ref('')
const updatedAt = ref(null)

const statusCounts = ref(emptyCounts())
const availableStock = ref(0)
const warnThreshold = ref(0)
const warned = ref(false)
const totalCount = ref(0)

const recycleCount = ref(0)
const recycleError = ref('')

/** 顶部四张 KPI 卡：任务书口径的四个核心状态 */
const KPI_CARDS = [
  { status: 'IDLE', label: '空闲（可领用）' },
  { status: 'IN_USE', label: '使用中' },
  { status: 'TO_DISINFECT', label: '待消毒' },
]

const detailRows = computed(() =>
  Object.keys(BAG_STATUS_META).map((code) => ({ code, count: statusCounts.value[code] || 0 })),
)

const updatedText = computed(() =>
  updatedAt.value ? `数据更新于 ${formatClock(updatedAt.value)}，实时推送自动刷新` : '正在加载…',
)

const warningText = computed(() =>
  warnThreshold.value > 0
    ? `库存预警：本站可用库存 ${availableStock.value} 只，已低于预警阈值 ${warnThreshold.value} 只，请及时补充或申请调拨。`
    : `库存预警：本站可用库存 ${availableStock.value} 只，已低于预警阈值，请及时补充或申请调拨。`,
)

/* ------------------------------ 取值与归一 ------------------------------ */

function emptyCounts() {
  return Object.fromEntries(Object.keys(BAG_STATUS_META).map((code) => [code, 0]))
}

function toCount(value) {
  const num = Number(value)
  return Number.isFinite(num) && num > 0 ? Math.floor(num) : 0
}

/**
 * 各状态库存：优先读 statusCounts / byStatus / counts 子对象，兼容后端把各状态数平铺在顶层；
 * 状态键同时接受大写下划线（IDLE）与驼峰（inUse）两种写法。
 */
function normalizeCounts(data) {
  const source = data?.statusCounts || data?.byStatus || data?.counts || data || {}
  const result = emptyCounts()
  for (const code of Object.keys(result)) {
    const camel = code.toLowerCase().replace(/_([a-z])/g, (_match, ch) => ch.toUpperCase())
    result[code] = toCount(source[code] ?? source[camel])
  }
  return result
}

/** 可用库存：接口给 availableStock/available 就直接用，否则回退为空闲数 */
function readAvailableStock(data, counts) {
  const raw = data?.availableStock ?? data?.available
  if (raw === undefined || raw === null || raw === '') return counts.IDLE
  const num = Number(raw)
  return Number.isFinite(num) && num >= 0 ? Math.floor(num) : counts.IDLE
}

/** 预警阈值：0 表示站点未配置预警（设计书 4.2.4：0＝不预警） */
function readWarnThreshold(data) {
  const raw = data?.warnThreshold ?? data?.station?.warnThreshold ?? data?.threshold
  const num = Number(raw)
  return Number.isFinite(num) && num > 0 ? Math.floor(num) : 0
}

/* ------------------------------ 数据加载 ------------------------------ */

async function loadStock() {
  if (!stationId.value) return
  if (!stockLoaded.value) loading.value = true
  try {
    const data = await getStationStock(stationId.value)
    statusCounts.value = normalizeCounts(data)
    totalCount.value = Object.values(statusCounts.value).reduce((sum, n) => sum + n, 0)
    availableStock.value = readAvailableStock(data, statusCounts.value)
    warnThreshold.value = readWarnThreshold(data)
    await evaluateWarning()
    updatedAt.value = new Date()
    stockLoaded.value = true
    errorText.value = ''
  } catch (error) {
    // 首屏失败才切错误态；后台实时刷新失败时保留上一次数据，不打断操作
    if (!stockLoaded.value) {
      errorText.value = error?.message || '库存数据加载失败，请检查网络后重试'
    }
  } finally {
    loading.value = false
  }
}

async function evaluateWarning() {
  const threshold = warnThreshold.value
  if (threshold > 0) {
    warned.value = availableStock.value < threshold
    return
  }
  warned.value = false
  // 接口未返回阈值：平台管理员用预警站点列表兜底判断；站点管理员无权限调该接口，跳过
  if (!userStore.isPlatformAdmin || !stationId.value) return
  try {
    const data = await listStockWarningStations()
    const list = Array.isArray(data) ? data : data?.records || []
    const hit = list.find((item) => Number(item?.id ?? item?.stationId) === Number(stationId.value))
    if (hit) {
      warned.value = true
      const hitThreshold = Number(hit.warnThreshold)
      if (Number.isFinite(hitThreshold) && hitThreshold > 0) {
        warnThreshold.value = Math.floor(hitThreshold)
      }
    }
  } catch {
    // 预警接口不可用不影响工作台主数据展示，静默降级
  }
}

async function loadRecycle() {
  if (!stationId.value) return
  try {
    // 只要数量：总量由 total 给出；后端若返回纯数组则退化为数组长度
    const data = await getRecycleBin(stationId.value, { pageNum: 1, pageSize: 100 })
    const list = Array.isArray(data) ? data : data?.records || []
    recycleCount.value = Number(data?.total ?? list.length) || 0
    recycleError.value = ''
  } catch (error) {
    recycleError.value = error?.message || '待办数量加载失败'
  }
}

async function reloadAll() {
  if (!stationId.value) return
  refreshing.value = true
  await Promise.allSettled([loadStock(), loadRecycle()])
  refreshing.value = false
}

/* ------------------------------ 实时推送订阅 ------------------------------ */

const topics = computed(() =>
  stationId.value ? [`station/${stationId.value}/stock`, `station/${stationId.value}/recycle`] : [],
)

/** 本次页面已订阅的主题，卸载时按它逐一退订 */
let subscribedTopics = []

function subscribeTopics() {
  if (!topics.value.length) return
  subscribedTopics = [...topics.value]
  for (const topic of subscribedTopics) wsStore.subscribe(topic)
}

/**
 * 强制重发订阅：store 在断线时会清空订阅集合，且 subscribe() 按集合去重，
 * 因此「连接建立/重新鉴权成功」与「进页面时连接尚未就绪」两种情形都要退订再订阅才发得出去。
 */
function resendTopics() {
  if (!topics.value.length) return
  for (const topic of topics.value) {
    wsStore.unsubscribe(topic)
    wsStore.subscribe(topic)
  }
}

function handleMessage(message) {
  if (!message?.topic || !stationId.value) return
  if (message.topic === `station/${stationId.value}/stock`) {
    loadStock()
  } else if (message.topic === `station/${stationId.value}/recycle`) {
    loadRecycle()
  }
}

onMounted(() => {
  subscribeTopics()
  reloadAll()
})

onUnmounted(() => {
  for (const topic of subscribedTopics) wsStore.unsubscribe(topic)
})

watch(
  () => wsStore.authed,
  (authed) => {
    if (authed) resendTopics()
  },
)

watch(
  () => wsStore.lastMessage,
  (message) => handleMessage(message),
)

// 进页面时用户信息可能尚未回填（stationId 由会话拉取后才可得），拿到后补订阅并拉数据
watch(stationId, (id) => {
  if (!id) return
  subscribeTopics()
  reloadAll()
})

/* ------------------------------ 交互 ------------------------------ */

function kpiStyle(status) {
  return { '--kpi-color': `var(${BAG_STATUS_META[status].token})` }
}

function formatClock(date) {
  const pad = (value) => String(value).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function goRecycle() {
  router.push('/station/recycle')
}

function goHome() {
  router.push(userStore.homePath || '/m/home')
}
</script>

<template>
  <div class="workbench">
    <!-- 未绑定站点（例如平台管理员误入本站点页）：给出明确指引，不发无谓请求 -->
    <PageCard v-if="!stationId" title="站点工作台">
      <EmptyState
        icon="WarningFilled"
        description="当前账号未绑定站点，无法查看本站库存。请使用站点管理员账号登录，或联系平台管理员绑定站点。"
      >
        <el-button type="primary" @click="goHome">返回首页</el-button>
      </EmptyState>
    </PageCard>

    <!-- 加载态：骨架屏（占位与真实内容一致，不用整页转圈） -->
    <template v-else-if="loading">
      <PageSkeleton type="card" :rows="4" />
      <div class="workbench__skeleton-gap">
        <PageSkeleton type="table" :rows="5" />
      </div>
    </template>

    <!-- 错误态：重试 -->
    <PageCard v-else-if="errorText" title="站点工作台">
      <EmptyState icon="WarningFilled" :description="errorText">
        <el-button type="primary" @click="reloadAll">重试</el-button>
      </EmptyState>
    </PageCard>

    <template v-else>
      <section class="workbench__welcome">
        <div>
          <p class="sems-eyebrow">STATION OPERATIONS</p>
          <h2>今天，也让每一只袋高效流转。</h2>
          <p>实时掌握库存状态，从回收到消毒，完成站点内的每一次绿色接力。</p>
        </div>
        <button class="workbench__scan-entry" @click="goRecycle"><el-icon :size="22"><FullScreen /></el-icon><span>扫码回收<small>快速确认投回袋</small></span><el-icon><ArrowRight /></el-icon></button>
      </section>
      <!-- 预警区：可用库存低于阈值时置顶提示 -->
      <el-alert
        v-if="warned"
        class="workbench__alert"
        type="warning"
        show-icon
        :closable="false"
        :title="warningText"
      />

      <!-- 顶部四张 KPI 卡：数字由 StatCard 内部按 sems-num 对齐 -->
      <div class="workbench__kpis">
        <div
          v-for="card in KPI_CARDS"
          :key="card.status"
          class="workbench__kpi"
          :style="kpiStyle(card.status)"
        >
          <StatCard :label="card.label" :value="statusCounts[card.status]" unit="只" :icon="card.status === 'IDLE' ? 'Box' : card.status === 'IN_USE' ? 'Van' : 'MagicStick'" hint="本站实时库存" />
        </div>
      </div>

      <div class="workbench__grid">
        <PageCard title="各状态库存明细" :subtitle="updatedText">
          <template #extra>
            <el-button :loading="refreshing" @click="reloadAll">
              <el-icon v-if="!refreshing"><Refresh /></el-icon>
              <span>刷新</span>
            </el-button>
          </template>

          <EmptyState v-if="totalCount === 0" description="本站暂无快递袋库存" />

          <template v-else>
            <!-- 桌面端：表格 -->
            <div class="sems-desktop-only">
              <el-table :data="detailRows">
                <el-table-column label="状态" min-width="150">
                  <template #default="{ row }">
                    <StatusTag kind="bag" :value="row.code" size="small" />
                  </template>
                </el-table-column>
                <el-table-column label="数量（只）" align="right" min-width="110">
                  <template #default="{ row }">
                    <span class="sems-num">{{ row.count }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <!-- 移动端：卡片列表（<768px 不做横向滚动） -->
            <ul class="sems-mobile-only workbench__list">
              <li v-for="row in detailRows" :key="row.code" class="workbench__list-item">
                <StatusTag kind="bag" :value="row.code" size="small" />
                <span class="sems-num workbench__list-count">{{ row.count }} 只</span>
              </li>
            </ul>
          </template>
        </PageCard>

        <PageCard title="待办事项">
          <div
            class="workbench__todo"
            role="link"
            tabindex="0"
            @click="goRecycle"
            @keydown.enter="goRecycle"
            @keydown.space.prevent="goRecycle"
          >
            <el-icon class="workbench__todo-icon" :size="22"><RefreshLeft /></el-icon>
            <span class="workbench__todo-body">
              <span class="workbench__todo-title">回收箱待确认</span>
              <span class="sems-text-muted">扫码确认用户投回的快递袋</span>
            </span>
            <span class="workbench__todo-count sems-num">{{ recycleError ? '—' : recycleCount }}</span>
            <el-icon class="workbench__todo-arrow"><ArrowRight /></el-icon>
          </div>

          <p v-if="recycleError" class="workbench__todo-note sems-text-muted">
            {{ recycleError }}
            <el-button link type="primary" @click="loadRecycle">重试</el-button>
          </p>
          <p v-else class="workbench__todo-note sems-text-muted">
            点击进入「回收确认」，逐只扫码办理并登记「曾寄出」标注。
          </p>
        </PageCard>
      </div>
    </template>
  </div>
</template>

<style scoped>
.workbench {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-4);
}

.workbench__skeleton-gap {
  margin-top: var(--sems-space-4);
}

.workbench__alert { flex: none; }
.workbench__welcome { display: flex; align-items: center; justify-content: space-between; gap: var(--sems-space-5); padding: var(--sems-space-5) var(--sems-space-6); background: var(--sems-bg-soft); border: 1px solid var(--sems-border); border-radius: var(--sems-radius-card); }
.workbench__welcome h2 { margin: var(--sems-space-2) 0; color: var(--sems-text-primary); font-size: var(--sems-font-size-xl); font-weight: var(--sems-font-weight-bold); }
.workbench__welcome p:last-child { margin: 0; color: var(--sems-text-secondary); line-height: 1.7; }
.workbench__scan-entry { display: flex; align-items: center; gap: var(--sems-space-3); flex: none; min-height: 64px; padding: var(--sems-space-3) var(--sems-space-5); color: var(--sems-text-inverse); background: var(--sems-bg-button); border: none; border-radius: var(--sems-radius-base); box-shadow: var(--sems-shadow-button); cursor: pointer; text-align: left; }
.workbench__scan-entry > span { display: flex; flex-direction: column; gap: var(--sems-space-1); font-weight: var(--sems-font-weight-bold); }
.workbench__scan-entry small { color: var(--sems-text-on-brand-soft); font-size: var(--sems-font-size-xs); font-weight: normal; }

/* ---------------- KPI 四卡 ---------------- */
.workbench__kpis {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sems-space-4);
}
.workbench__kpi {
  /* 顶部 3px 状态色条：颜色来自 --sems-bag-* 令牌，卡片标签同时给出中文状态名，不靠颜色单独承载语义 */
  border-top: 3px solid var(--kpi-color);
  border-radius: var(--sems-radius-card);
  overflow: hidden;
  background-color: var(--sems-bg-card);
  box-shadow: var(--sems-shadow-card);
}
.workbench__kpi :deep(.stat-card) {
  border-radius: 0;
  box-shadow: none;
}

/* ---------------- 明细与待办 ---------------- */
.workbench__grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  gap: var(--sems-space-4);
  align-items: start;
}

.workbench__list {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-1);
  margin: 0;
  padding: 0;
  list-style: none;
}
.workbench__list-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-3);
  min-height: var(--sems-row-height);
  padding: 0 var(--sems-space-2);
  border-bottom: 1px solid var(--sems-border);
}
.workbench__list-item:last-child {
  border-bottom: none;
}
.workbench__list-count {
  color: var(--sems-text-primary);
}

.workbench__todo {
  display: flex;
  align-items: center;
  gap: var(--sems-space-3);
  padding: var(--sems-space-4);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-base);
  cursor: pointer;
  transition: background-color var(--sems-duration-fast);
}
.workbench__todo:hover {
  background-color: var(--sems-bg-hover);
}
.workbench__todo:focus-visible {
  outline: 2px solid var(--sems-color-primary);
  outline-offset: 2px;
}
.workbench__todo-icon {
  flex: none;
  color: var(--sems-color-primary);
}
.workbench__todo-body {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-1);
  min-width: 0;
  flex: 1;
}
.workbench__todo-title {
  font-weight: var(--sems-font-weight-medium);
  color: var(--sems-text-primary);
}
.workbench__todo-count {
  font-size: var(--sems-font-size-kpi);
  font-weight: var(--sems-font-weight-bold);
  line-height: 1.2;
  color: var(--sems-text-primary);
}
.workbench__todo-arrow {
  flex: none;
  color: var(--sems-text-secondary);
}
.workbench__todo-note {
  margin: var(--sems-space-3) 0 0;
  line-height: 1.6;
}

/* ---------------- 响应式 ---------------- */
@media (max-width: 1000px) { .workbench__welcome { align-items: flex-start; flex-direction: column; } }
@media (max-width: 1199px) {
  .workbench__kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 767px) {
  .workbench__kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--sems-space-3);
  }
  .workbench__grid { grid-template-columns: minmax(0, 1fr); }
  .workbench__welcome { padding: var(--sems-space-5); }
  .workbench__welcome h2 { font-size: var(--sems-font-size-lg); }
  .workbench__scan-entry { width: 100%; }
  .workbench__scan-entry > span { flex: 1; }
}
</style>
