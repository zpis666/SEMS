<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listAllocations, dispatchAllocation, receiveAllocation } from '@/api/allocation'
import { ALLOCATION_STATUS_META, DEFAULT_PAGE_SIZE } from '@/constants'
import { BAG_CODE_REGEX, toUpperCase } from '@/utils/validators'
import { extractBatch } from '@/utils/batch'
import { useUserStore } from '@/stores/user'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DataPager from '@/components/DataPager.vue'
import StatusTag from '@/components/StatusTag.vue'
import BagCodeInput from '@/components/BagCodeInput.vue'
import BatchResultDialog from '@/components/BatchResultDialog.vue'

/**
 * 站点端 · 调拨出库 / 核收（设计书 4.3 / 5.3）。
 *
 * 1) 数据范围：相关站点管理员的列表由后端**强制过滤**为「源站或目标站＝本站」，
 *    因此本页不提供站点筛选下拉（站点筛选是平台端 AllocationManage 的能力）；
 * 2) 行操作：本站＝源站且在待出库/在途 → 扫码出库（dispatchAllocation）；
 *    本站＝目标站且在途 → 扫码核收（receiveAllocation）；
 * 3) 在途满 48 小时的行标注「已超时，待平台裁决」（设计书 3.5：调拨出库满 48h 未核收，
 *    是否按遗失处置由平台管理员裁决，本站不在此页处理）。
 */

const userStore = useUserStore()

/** 3001 为字段级校验错误，request.js 不弹提示，由页面兜底展示后端给出的原因 */
function notifyFormError(error) {
  if (error?.code !== 3001) return
  const detail = error?.data?.fieldErrors
  const text = Array.isArray(detail)
    ? detail
        .map((item) => (typeof item === 'string' ? item : item?.message))
        .filter(Boolean)
        .join('；')
    : detail && typeof detail === 'object'
      ? Object.values(detail).filter(Boolean).join('；')
      : ''
  ElMessage.error(text || error?.message || '提交内容有误，请检查后重试')
}

/* ------------------------------ 列表 ------------------------------ */

const query = reactive({ pageNum: 1, pageSize: DEFAULT_PAGE_SIZE, status: '' })
const list = ref([])
const total = ref(0)
const loading = ref(true)
const error = ref(false)

/** 在途超时阈值：48 小时；仅用于前端提示，权威判定与裁决在平台端 */
const ALLOCATION_TIMEOUT_MS = 48 * 60 * 60 * 1000

async function load() {
  loading.value = true
  error.value = false
  try {
    const data = await listAllocations({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      status: query.status || undefined,
    })
    list.value = data?.records || []
    total.value = Number(data?.total || 0)
  } catch {
    // 具体错误文案由 request.js 统一提示，这里只落到「错误态 + 重试」三态
    list.value = []
    total.value = 0
    error.value = true
  } finally {
    loading.value = false
  }
}

function handleFilterChange() {
  query.pageNum = 1
  load()
}

/* ------------------------------ 行能力判定 ------------------------------ */

function isSourceStation(row) {
  return userStore.stationId != null && String(row.fromStationId) === String(userStore.stationId)
}

function isTargetStation(row) {
  return userStore.stationId != null && String(row.toStationId) === String(userStore.stationId)
}

/** 源站：待出库可开始出库，在途可继续分批出库（是否允许由后端状态机与明细校验兜底） */
function canDispatch(row) {
  return isSourceStation(row) && ['PENDING_DISPATCH', 'IN_TRANSIT'].includes(row.status)
}

/** 目标站：单据在途才可核收入库 */
function canReceive(row) {
  return isTargetStation(row) && row.status === 'IN_TRANSIT'
}

function isTimeout(row) {
  if (row.status !== 'IN_TRANSIT' || !row.dispatchAt) return false
  const dispatchTime = new Date(row.dispatchAt).getTime()
  return Number.isFinite(dispatchTime) && Date.now() - dispatchTime > ALLOCATION_TIMEOUT_MS
}

/**
 * 明细进度（已核收 / 总数）。
 * 主口径为 receivedCount / totalCount，兼容后端 VO 的常见别名，字段缺失时显示「—」。
 */
function readCount(row, keys) {
  for (const key of keys) {
    const value = row?.[key]
    if (value !== undefined && value !== null && value !== '') return Number(value)
  }
  return null
}

function progressText(row) {
  const received = readCount(row, ['receivedCount', 'received', 'receivedNum'])
  const totalCount = readCount(row, ['totalCount', 'detailCount', 'total'])
  if (received === null && totalCount === null) return '—'
  return `已核收 ${received ?? 0} / ${totalCount ?? '—'}`
}

function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

/** 站点展示：优先取名称，缺名称回退站点 ID */
function stationText(row, prefix) {
  return row?.[`${prefix}StationName`] || row?.[`${prefix}StationId`] || '—'
}

/* ------------------------------ 扫码抽屉 ------------------------------ */

const drawer = reactive({
  visible: false,
  mode: 'dispatch',
  row: null,
  code: '',
  codes: [],
  submitting: false,
})

const batchVisible = ref(false)
const batchRows = ref([])
/** 后端给的批次总数与失败数：items 可能被批量上限截断，计数以后端为准 */
const batchTotal = ref(0)
const batchFailed = ref(0)

const viewportWidth = ref(window.innerWidth)
function syncViewport() {
  viewportWidth.value = window.innerWidth
}
/** 移动端抽屉占满屏宽，桌面端固定 420px */
const drawerSize = computed(() => (viewportWidth.value < 768 ? '100%' : '420px'))

onMounted(() => {
  syncViewport()
  window.addEventListener('resize', syncViewport)
  load()
})
onUnmounted(() => window.removeEventListener('resize', syncViewport))

function openDrawer(row, mode) {
  drawer.mode = mode
  drawer.row = row
  drawer.code = ''
  drawer.codes = []
  drawer.visible = true
}

/** 扫码一次即累加一只；格式与重复先在前端拦一道，后端仍是权威校验 */
function handleScan(code) {
  const value = toUpperCase(code || drawer.code)
  drawer.code = ''
  if (!value) return
  if (!BAG_CODE_REGEX.test(value)) {
    ElMessage.warning('袋码格式不正确（形如 SJB-L-2609-00001-4），请核对后重试')
    return
  }
  if (drawer.codes.includes(value)) {
    ElMessage.warning('该袋码已在本次清单中')
    return
  }
  drawer.codes.push(value)
}

function removeCode(index) {
  drawer.codes.splice(index, 1)
}

function clearCodes() {
  drawer.codes = []
}

async function submitDrawer() {
  if (!drawer.codes.length) {
    ElMessage.warning('请先扫码录入袋码')
    return
  }
  drawer.submitting = true
  try {
    // 逐袋批量动作（出库 / 核收）的入参形态是 bags: [{ bagCode, remark }]，
    // 与上架入库、出库、消毒登记、回收确认一致；注意它与「创建调拨单」的 bagCodes: [String]
    // 不是一回事——后者是单据创建时的袋码清单，前者是逐袋执行动作。
    const payload = { bags: drawer.codes.map((bagCode) => ({ bagCode })) }
    const data =
      drawer.mode === 'dispatch'
        ? await dispatchAllocation(drawer.row.id, payload)
        : await receiveAllocation(drawer.row.id, payload)
    // 调拨出库/核收是逐袋批量操作，统一返回 BatchResult 信封（data.items 为逐袋明细）。
    // 用 utils/batch.js 的唯一入口取值，不要再猜 results/records/details 等形态——
    // 猜形态会让契约漂移无从发现，之前回收确认页就是这么静默显示「成功 0 / 失败 0」的。
    const { items, total, failed } = extractBatch(data)
    if (items.length) {
      batchRows.value = items
      batchTotal.value = total
      batchFailed.value = failed
      batchVisible.value = true
    } else {
      ElMessage.success(drawer.mode === 'dispatch' ? '出库已提交' : '核收已提交')
    }
    drawer.visible = false
    load()
  } catch (error) {
    // 失败时保持抽屉打开，便于修正袋码后重试；其余错误文案由 request.js 统一提示
    notifyFormError(error)
  } finally {
    drawer.submitting = false
  }
}
</script>

<template>
  <PageCard
    title="调拨出库 / 核收"
    subtitle="仅包含与本站相关的调拨单（源站或目标站＝本站，范围由后端强制过滤）"
  >
    <template #extra>
      <div class="allocation__filters">
        <el-select
          v-model="query.status"
          class="allocation__status-select"
          placeholder="全部状态"
          clearable
          @change="handleFilterChange"
        >
          <el-option
            v-for="(meta, code) in ALLOCATION_STATUS_META"
            :key="code"
            :label="meta.label"
            :value="code"
          />
        </el-select>
        <el-button @click="load">
          <el-icon><Refresh /></el-icon>
          <span>刷新</span>
        </el-button>
      </div>
    </template>

    <p class="allocation__scope-note sems-text-muted">
      <el-icon><InfoFilled /></el-icon>
      <span>
        列表只显示与本站相关的调拨单（源站或目标站＝本站），站点范围由后端强制过滤；全量调拨单请在平台端「调拨管理」查询。
        出库由源站扫码、核收入库由目标站扫码，逐袋办理。
      </span>
    </p>

    <PageSkeleton v-if="loading" type="table" :rows="6" />

    <div v-else-if="error" class="allocation__error">
      <el-icon class="allocation__error-icon" :size="32"><WarningFilled /></el-icon>
      <p class="allocation__error-text">调拨单加载失败，请检查网络后重试</p>
      <el-button type="primary" @click="load">重新加载</el-button>
    </div>

    <EmptyState v-else-if="!list.length" description="暂无与本站相关的调拨单">
      <el-button @click="load">刷新</el-button>
    </EmptyState>

    <template v-else>
      <!-- 桌面端：表格 -->
      <div class="sems-desktop-only">
        <el-table :data="list" row-key="id">
          <el-table-column label="调拨单号" min-width="180">
            <template #default="{ row }">
              <span class="sems-mono">{{ row.allocationNo || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="源站" min-width="130">
            <template #default="{ row }">{{ stationText(row, 'from') }}</template>
          </el-table-column>
          <el-table-column label="目标站" min-width="130">
            <template #default="{ row }">{{ stationText(row, 'to') }}</template>
          </el-table-column>
          <el-table-column label="状态" min-width="180">
            <template #default="{ row }">
              <div class="allocation__status-cell">
                <StatusTag kind="allocation" :value="row.status" size="small" />
                <el-tag v-if="isTimeout(row)" type="warning" size="small" effect="plain">
                  已超时，待平台裁决
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="明细进度" min-width="130">
            <template #default="{ row }">
              <span class="sems-num">{{ progressText(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="150">
            <template #default="{ row }">
              <span class="sems-num">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="canDispatch(row)"
                type="primary"
                link
                @click="openDrawer(row, 'dispatch')"
              >
                扫码出库
              </el-button>
              <el-button
                v-else-if="canReceive(row)"
                type="primary"
                link
                @click="openDrawer(row, 'receive')"
              >
                扫码核收
              </el-button>
              <span v-else class="sems-text-muted">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 移动端：卡片列表（<768px 不做横向滚动） -->
      <div class="sems-mobile-only allocation__cards">
        <div v-for="row in list" :key="row.id" class="allocation__card">
          <div class="allocation__card-head">
            <span class="sems-mono allocation__card-no">{{ row.allocationNo || '—' }}</span>
            <StatusTag kind="allocation" :value="row.status" size="small" />
          </div>
          <div class="allocation__card-line">源站：{{ stationText(row, 'from') }}</div>
          <div class="allocation__card-line">目标站：{{ stationText(row, 'to') }}</div>
          <div class="allocation__card-line">明细进度：{{ progressText(row) }}</div>
          <div class="allocation__card-line">创建时间：{{ formatTime(row.createdAt) }}</div>
          <el-tag v-if="isTimeout(row)" type="warning" size="small" effect="plain">
            已超时，待平台裁决
          </el-tag>
          <div v-if="canDispatch(row) || canReceive(row)" class="allocation__card-actions">
            <el-button
              v-if="canDispatch(row)"
              type="primary"
              @click="openDrawer(row, 'dispatch')"
            >
              扫码出库
            </el-button>
            <el-button
              v-else
              type="primary"
              @click="openDrawer(row, 'receive')"
            >
              扫码核收
            </el-button>
          </div>
        </div>
      </div>

      <DataPager
        v-model:page="query.pageNum"
        v-model:size="query.pageSize"
        :total="total"
        @change="load"
      />
    </template>
  </PageCard>

  <!-- 扫码抽屉：出库 / 核收共用，逐只累加袋码后一次性提交 -->
  <el-drawer
    v-model="drawer.visible"
    :title="drawer.mode === 'dispatch' ? '扫码出库' : '扫码核收'"
    :size="drawerSize"
    :close-on-click-modal="false"
  >
    <div v-if="drawer.row" class="scan-drawer">
      <div class="scan-drawer__summary">
        <div class="scan-drawer__no sems-mono">{{ drawer.row.allocationNo || '—' }}</div>
        <div class="sems-text-muted">
          {{ stationText(drawer.row, 'from') }} → {{ stationText(drawer.row, 'to') }}
        </div>
        <div class="sems-text-muted">当前进度：{{ progressText(drawer.row) }}</div>
      </div>

      <BagCodeInput v-model="drawer.code" @scan="handleScan" />

      <p class="scan-drawer__hint sems-text-muted">
        连续扫码或手输袋码回车，逐只累加到下方清单；提交后由后端逐袋校验并流转，部分失败会给出逐袋明细。
      </p>

      <div class="scan-drawer__list">
        <div v-if="!drawer.codes.length" class="scan-drawer__empty sems-text-muted">
          尚未录入袋码
        </div>
        <div v-for="(code, index) in drawer.codes" :key="code" class="scan-drawer__item">
          <span class="sems-mono">{{ code }}</span>
          <el-button link type="danger" @click="removeCode(index)">移除</el-button>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="scan-drawer__footer">
        <el-button :disabled="drawer.submitting" @click="clearCodes">清空</el-button>
        <el-button type="primary" :loading="drawer.submitting" @click="submitDrawer">
          {{ drawer.mode === 'dispatch' ? '提交出库' : '提交核收' }}（{{ drawer.codes.length }}）
        </el-button>
      </div>
    </template>
  </el-drawer>

  <BatchResultDialog
    v-model="batchVisible"
    :results="batchRows"
    :total="batchTotal"
    :failed="batchFailed"
  />
</template>

<style scoped>
.allocation__filters {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
}
.allocation__status-select {
  width: 160px;
}

.allocation__scope-note {
  display: flex;
  align-items: flex-start;
  gap: var(--sems-space-2);
  margin: 0 0 var(--sems-space-4);
  line-height: 1.6;
}
.allocation__scope-note .el-icon {
  margin-top: 3px;
  flex: none;
}

.allocation__status-cell {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  flex-wrap: wrap;
}

.allocation__error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-3);
  padding: var(--sems-space-6) 0;
  color: var(--sems-text-secondary);
}
.allocation__error-icon {
  color: var(--sems-color-warning);
}
.allocation__error-text {
  margin: 0;
}

/* ---------------- 移动端卡片 ---------------- */
.allocation__cards {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
}
.allocation__card {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
  padding: var(--sems-space-4);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-card);
  background-color: var(--sems-bg-card);
}
.allocation__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  flex-wrap: wrap;
}
.allocation__card-no {
  font-weight: var(--sems-font-weight-medium);
  color: var(--sems-text-primary);
}
.allocation__card-line {
  color: var(--sems-text-regular);
}
.allocation__card-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--sems-space-1);
}

/* ---------------- 扫码抽屉 ---------------- */
.scan-drawer {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-4);
  height: 100%;
}
.scan-drawer__summary {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-1);
  padding: var(--sems-space-3);
  border-radius: var(--sems-radius-base);
  background-color: var(--sems-bg-page);
}
.scan-drawer__no {
  font-weight: var(--sems-font-weight-bold);
  color: var(--sems-text-primary);
}
.scan-drawer__hint {
  margin: 0;
  line-height: 1.6;
}
.scan-drawer__list {
  flex: 1;
  min-height: 120px;
  overflow-y: auto;
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-base);
}
.scan-drawer__empty {
  padding: var(--sems-space-5);
  text-align: center;
}
.scan-drawer__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--sems-space-2) var(--sems-space-3);
  border-bottom: 1px solid var(--sems-border);
}
.scan-drawer__item:last-child {
  border-bottom: none;
}
.scan-drawer__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--sems-space-2);
}
</style>
