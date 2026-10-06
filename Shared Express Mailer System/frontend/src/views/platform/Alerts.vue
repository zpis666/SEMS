<script setup>
/**
 * 平台端 · 异常与风控告警（设计书 4.4 平台端、5.6 告警七类）。
 *
 * 七类告警：①②③④ 由定时任务扫描产生（订单侧），⑤⑥ 为系统指标（审计队列丢弃、库存对账差异），
 * ⑦ 为高频确认风控线索。按类型 / 时间区间筛选，清单项可直接跳转处置：
 *  ①取消后未回收 ②回收在途超时 ④寄件超时 → 订单管理（带状态或超时页签过滤）
 *  ③调拨未核收 → 调拨管理；⑤⑥ → 数据看板；⑦ → 操作审计（带动作过滤）。
 *
 * 顶部四条 KPI 卡显示前四类数量：后端返回汇总字段则用之，否则按当前页统计并注明「当前页」。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DataPager from '@/components/DataPager.vue'
import StatCard from '@/components/StatCard.vue'
import { ALERT_TYPE_META, DEFAULT_PAGE_SIZE } from '@/constants'
import { listAlerts } from '@/api/stats'

const route = useRoute()
const router = useRouter()

const query = reactive({ pageNum: 1, pageSize: DEFAULT_PAGE_SIZE, type: '' })
const timeRange = ref([])
const state = reactive({ loading: false, error: '', records: [], total: 0, summary: null })

/** 时间区间默认补齐为「当天 00:00:00 至 23:59:59」 */
const defaultTime = [new Date(2000, 1, 1, 0, 0, 0), new Date(2000, 1, 1, 23, 59, 59)]

const typeOptions = computed(() =>
  Object.entries(ALERT_TYPE_META).map(([value, meta]) => ({
    value,
    label: meta.label,
    desc: meta.desc,
  })),
)

/* ------------------------------ 数据加载 ------------------------------ */

function buildParams() {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.type) params.type = query.type
  const [begin, end] = timeRange.value || []
  if (begin) params.beginTime = begin
  if (end) params.endTime = end
  return params
}

async function load() {
  state.loading = true
  state.error = ''
  try {
    const res = await listAlerts(buildParams())
    state.records = res?.records || []
    state.total = res?.total || 0
    // 后端若返回汇总字段则用于 KPI；未返回时 KPI 退化为当前页统计
    state.summary = res?.summary || res?.typeCounts || res?.counts || null
  } catch (error) {
    state.records = []
    state.total = 0
    state.error = error?.message || '告警清单加载失败'
  } finally {
    state.loading = false
  }
}

function search() {
  query.pageNum = 1
  load()
}

function reset() {
  query.type = ''
  timeRange.value = []
  search()
}

/* ------------------------------ KPI 与展示辅助 ------------------------------ */

/** 顶部四张 KPI 卡对应前四类（定时任务扫描的订单侧异常） */
const KPI_CODES = ['CANCELLED_NOT_RECOVERED', 'RETURNING_TIMEOUT', 'ALLOCATION_TIMEOUT', 'SHIP_TIMEOUT']

function camelCase(code) {
  return code.toLowerCase().replace(/_([a-z])/g, (match, letter) => letter.toUpperCase())
}

function summaryValue(code) {
  const summary = state.summary
  if (!summary || typeof summary !== 'object') return null
  const value = summary[code] ?? summary[camelCase(code)]
  return typeof value === 'number' ? value : null
}

const usingSummary = computed(() => KPI_CODES.some((code) => summaryValue(code) !== null))

function pageCount(code) {
  return state.records.filter((row) => alertTypeOf(row) === code).length
}

const kpiItems = computed(() =>
  KPI_CODES.map((code) => {
    const fromSummary = summaryValue(code)
    const meta = ALERT_TYPE_META[code] || { label: code }
    return {
      code,
      count: fromSummary !== null ? fromSummary : pageCount(code),
      label: usingSummary.value ? meta.label : `${meta.label}（当前页）`,
    }
  }),
)

/** ⑤⑥⑦ 当前页统计，写入顶部说明（这三类由看板与审计页承接处置） */
const tailCountsText = computed(() => {
  const parts = ['AUDIT_DROP', 'STOCK_RECONCILE_DIFF', 'RISK_CONFIRM_FREQUENCY'].map((code) => {
    const label = ALERT_TYPE_META[code]?.label || code
    const fromSummary = summaryValue(code)
    const count = fromSummary !== null ? fromSummary : pageCount(code)
    return `${label} ${count} 条`
  })
  return parts.join('；')
})

function alertTypeOf(row) {
  return row?.type || row?.alertType || ''
}

function typeMetaOf(row) {
  const type = alertTypeOf(row)
  return ALERT_TYPE_META[type] || { label: type || '未知类型', desc: '' }
}

const LEVEL_META = {
  HIGH: { label: '高', type: 'danger' },
  MEDIUM: { label: '中', type: 'warning' },
  LOW: { label: '低', type: 'info' },
  高: { label: '高', type: 'danger' },
  中: { label: '中', type: 'warning' },
  低: { label: '低', type: 'info' },
}

function levelMetaOf(row) {
  return LEVEL_META[row?.level] || { label: row?.level || '—', type: 'info' }
}

function formatTime(value) {
  if (!value) return '—'
  const text = String(value).replace('T', ' ')
  return text.length > 19 ? text.slice(0, 19) : text
}

function occurredAt(row) {
  return row?.occurredAt || row?.createdAt || row?.alertTime || ''
}

function titleOf(row) {
  return row?.title || typeMetaOf(row).label || '—'
}

function objectText(row) {
  if (row?.orderNo) return `订单 ${row.orderNo}`
  if (row?.bagCode) return `袋 ${row.bagCode}`
  if (row?.stationName) return `站点 ${row.stationName}`
  if (row?.stationId != null) return `站点 ID ${row.stationId}`
  if (row?.objectNo) return String(row.objectNo)
  return '—'
}

/* ------------------------------ 去处置 ------------------------------ */

function goHandle(row) {
  const type = alertTypeOf(row)
  if (type === 'CANCELLED_NOT_RECOVERED') {
    // 订单取消后袋未回收：直达「已取消」订单列表
    router.push({ path: '/platform/orders', query: { status: 'CANCELLED' } })
    return
  }
  if (type === 'RETURNING_TIMEOUT') {
    // 回收在途超时：对应订单的「待回收确认」状态
    router.push({ path: '/platform/orders', query: { status: 'PENDING_CONFIRM' } })
    return
  }
  if (type === 'SHIP_TIMEOUT') {
    // 寄件超时未归还：直达订单管理的「超时未归还」页签
    router.push({ path: '/platform/orders', query: { tab: 'timeout' } })
    return
  }
  if (type === 'ALLOCATION_TIMEOUT') {
    router.push('/platform/allocations')
    return
  }
  if (type === 'AUDIT_DROP' || type === 'STOCK_RECONCILE_DIFF') {
    // 系统级指标：看板有实时卡片与对账差异告警位
    router.push('/platform/dashboard')
    return
  }
  if (type === 'RISK_CONFIRM_FREQUENCY') {
    // 风控线索：带动作（必要时带操作人）过滤到操作审计
    const auditQuery = { action: row?.action || '回收确认（跨站）' }
    if (row?.operatorId != null) auditQuery.operatorId = String(row.operatorId)
    router.push({ path: '/platform/audit-logs', query: auditQuery })
    return
  }
  ElMessage.info('该告警类型暂无跳转处置入口，请在本页按类型筛选查看')
}

onMounted(() => {
  if (typeof route.query.type === 'string' && route.query.type) query.type = route.query.type
  load()
})
</script>

<template>
  <PageCard title="异常与风控" subtitle="定时任务扫描的异常、系统指标与高频确认风控线索；清单项可直接跳转对应单据处置">
    <template #extra>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </template>

    <div class="kpi-grid">
      <StatCard v-for="item in kpiItems" :key="item.code" :label="item.label" :value="item.count" />
    </div>
    <p v-if="!usingSummary" class="sems-text-muted kpi-note">
      后端未返回汇总字段，以上数量按当前页数据统计并已注明「当前页」。
    </p>

    <el-alert
      class="system-tip"
      type="info"
      :closable="false"
      show-icon
      title="系统类与风控类告警的处置入口"
      :description="`审计队列丢弃、库存对账差异为系统级指标，请前往数据看板查看实时卡片与对账差异告警位；高频确认风控线索请前往操作审计按动作核查。本页统计：${tailCountsText}`"
    />

    <el-form class="filter-bar" inline @submit.prevent>
      <el-form-item label="类型">
        <el-select v-model="query.type" clearable placeholder="全部类型" style="width: 220px" @change="search">
          <el-option v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value">
            <el-tooltip :content="item.desc" placement="right" :show-after="300">
              <span>{{ item.label }}</span>
            </el-tooltip>
          </el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="时间区间">
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          :default-time="defaultTime"
          value-format="YYYY-MM-DD HH:mm:ss"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 360px"
          @change="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <PageSkeleton v-if="state.loading" type="table" :rows="6" />

    <div v-else-if="state.error" class="state-block">
      <p class="state-block__title">告警清单加载失败</p>
      <p class="sems-text-muted">{{ state.error }}</p>
      <el-button type="primary" @click="load">重试</el-button>
    </div>

    <EmptyState v-else-if="!state.records.length" description="暂无符合条件的告警记录">
      <el-button type="primary" @click="reset">重置筛选条件</el-button>
    </EmptyState>

    <template v-else>
      <div class="sems-desktop-only">
        <el-table :data="state.records" stripe>
          <el-table-column label="类型" min-width="160">
            <template #default="{ row }">
              <el-tooltip :content="typeMetaOf(row).desc || '暂无说明'" placement="top" :show-after="300">
                <el-tag size="small" effect="plain">{{ typeMetaOf(row).label }}</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="标题" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ titleOf(row) }}</template>
          </el-table-column>
          <el-table-column label="对象" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="sems-mono">{{ objectText(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="发生时间" min-width="160">
            <template #default="{ row }">
              <span class="sems-num">{{ formatTime(occurredAt(row)) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="级别" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="levelMetaOf(row).type" effect="plain">{{ levelMetaOf(row).label }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="goHandle(row)">去处置</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="sems-mobile-only">
        <article v-for="row in state.records" :key="row.id || `${alertTypeOf(row)}-${occurredAt(row)}`" class="list-card">
          <header class="list-card__head">
            <el-tag size="small" effect="plain">{{ typeMetaOf(row).label }}</el-tag>
            <el-tag size="small" :type="levelMetaOf(row).type" effect="plain">{{ levelMetaOf(row).label }}</el-tag>
          </header>
          <p class="list-card__title">{{ titleOf(row) }}</p>
          <p class="list-card__line"><span class="sems-text-muted">对象</span><span class="sems-mono">{{ objectText(row) }}</span></p>
          <p class="list-card__line"><span class="sems-text-muted">发生时间</span><span>{{ formatTime(occurredAt(row)) }}</span></p>
          <div class="list-card__actions">
            <el-button link type="primary" size="small" @click="goHandle(row)">去处置</el-button>
          </div>
        </article>
      </div>

      <DataPager
        v-model:page="query.pageNum"
        v-model:size="query.pageSize"
        :total="state.total"
        :disabled="state.loading"
        @change="load"
      />
    </template>
  </PageCard>
</template>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--sems-space-4);
}
.kpi-note {
  margin: var(--sems-space-2) 0 0;
}

.system-tip {
  margin: var(--sems-space-4) 0 var(--sems-space-3);
}

.filter-bar {
  margin-bottom: var(--sems-space-3);
}

.state-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-2);
  padding: var(--sems-space-6) 0;
}
.state-block__title {
  margin: 0;
  font-size: var(--sems-font-size-base);
  color: var(--sems-text-primary);
}

.list-card {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-1);
  padding: var(--sems-space-3);
  margin-bottom: var(--sems-space-3);
  background-color: var(--sems-bg-card);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-card);
}
.list-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  padding-bottom: var(--sems-space-2);
  border-bottom: 1px solid var(--sems-border);
}
.list-card__title {
  margin: 0;
  font-size: var(--sems-font-size-base);
  color: var(--sems-text-primary);
  font-weight: var(--sems-font-weight-medium);
}
.list-card__line {
  display: flex;
  justify-content: space-between;
  gap: var(--sems-space-3);
  margin: 0;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-regular);
}
.list-card__actions {
  display: flex;
  justify-content: flex-end;
  padding-top: var(--sems-space-1);
}

@media (max-width: 1200px) {
  .kpi-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 767px) {
  .kpi-grid {
    grid-template-columns: 1fr;
  }
}
</style>
