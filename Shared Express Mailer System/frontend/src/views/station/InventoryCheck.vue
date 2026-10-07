<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DataPager from '@/components/DataPager.vue'
import StatusTag from '@/components/StatusTag.vue'
import { createInventoryCheck, listInventoryChecks, voidInventoryCheck } from '@/api/bag'
import { getStationStock } from '@/api/station'
import { useUserStore } from '@/stores/user'
import { BAG_STATUS_META, DEFAULT_PAGE_SIZE, INVENTORY_CHECK_STATUS_META } from '@/constants'

/**
 * 库存盘点（站点端，见《系统设计说明书》4.3 / 5.4 与需求 FR-M4-17）。
 *
 * - 上方「发起盘点」：getStationStock 取本站各状态账面数，逐状态录入实盘数，
 *   差异 = 实盘 − 账面 自动计算并色标（负数盘亏 / 正数盘盈）。
 *   提交字段：expectJson / actualJson / diffBags / diffCount（+ 备注）。
 *   diffBags 按约定提交空数组——袋级差异清单由后端按袋清单比对生成；
 *   diffCount 前端先按状态级差异绝对值合计提交，最终以后端差异清单条数为准。
 * - 下方盘点单列表：分页 + 按状态/时间筛选；展开行看袋级差异清单（diff_bags_json）。
 * - 盘点单不可删除，仅可作废重盘（voidInventoryCheck，作废原因必填）。
 */

const userStore = useUserStore()

/* ---------------------------------------------------------------- 响应式：<768px 表格转卡片 */

const isMobile = ref(window.innerWidth < 768)
function syncMobile() {
  isMobile.value = window.innerWidth < 768
}

/* ---------------------------------------------------------------- 公共：格式化与错误提示 */

function fmtTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

/** 3001 字段级错误（request.js 不在拦截器里弹，留给表单侧展示） */
function fieldErrorText(error) {
  const detail = error?.data?.fieldErrors
  if (!detail) return ''
  if (Array.isArray(detail)) {
    return detail
      .map((item) => (typeof item === 'string' ? item : item?.message))
      .filter(Boolean)
      .join('；')
  }
  if (typeof detail === 'object') return Object.values(detail).filter(Boolean).join('；')
  return typeof detail === 'string' ? detail : ''
}

/* ---------------------------------------------------------------- 上方：发起盘点 */

const stockCardRef = ref()
const stockRows = ref([])
const stockLoading = ref(false)
const stockError = ref(false)
const remark = ref('')
const submitting = ref(false)

/** 列表空态的主操作：回到上方盘点卡并刷新账面数 */
function focusStock() {
  loadStock()
  const el = stockCardRef.value?.$el
  if (el && typeof el.scrollIntoView === 'function') {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

/** 状态键归一：兼容 IDLE / idle / inUse / in_use / 中文标签 等形态 */
function toStatusKey(raw) {
  if (raw == null) return ''
  const text = String(raw).trim()
  const key = text.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase()
  if (BAG_STATUS_META[key]) return key
  return Object.keys(BAG_STATUS_META).find((item) => BAG_STATUS_META[item].label === text) || ''
}

/** 账面数归一：兼容数组明细与对象映射两种返回形态 */
function normalizeStock(payload) {
  const counts = {}
  const put = (rawKey, rawValue) => {
    const key = toStatusKey(rawKey)
    if (!key) return
    let value = rawValue
    if (value && typeof value === 'object') {
      value = value.count ?? value.stock ?? value.num ?? value.quantity
    }
    const num = Number(value)
    counts[key] = Number.isFinite(num) ? num : 0
  }
  if (Array.isArray(payload)) {
    payload.forEach((item) =>
      put(
        item?.status ?? item?.bagStatus ?? item?.code ?? item?.name,
        item?.count ?? item?.stock ?? item?.num ?? item?.quantity ?? item?.value,
      ),
    )
  } else if (payload && typeof payload === 'object') {
    Object.entries(payload).forEach(([key, value]) => put(key, value))
  }
  return counts
}

async function loadStock() {
  if (!userStore.stationId) {
    stockRows.value = []
    return
  }
  stockLoading.value = true
  stockError.value = false
  try {
    const data = await getStationStock(userStore.stationId)
    const counts = normalizeStock(data)
    stockRows.value = Object.keys(BAG_STATUS_META).map((status) => ({
      status,
      book: counts[status] || 0,
      actual: counts[status] || 0,
    }))
  } catch {
    // 具体错误已由 request.js 统一提示，这里只切错误态
    stockError.value = true
    stockRows.value = []
  } finally {
    stockLoading.value = false
  }
}

function diffOf(row) {
  return (Number(row.actual) || 0) - (Number(row.book) || 0)
}

function diffText(row) {
  const diff = diffOf(row)
  return diff > 0 ? `+${diff}` : `${diff}`
}

function diffClass(row) {
  const diff = diffOf(row)
  if (diff < 0) return 'inventory-page__diff--danger'
  if (diff > 0) return 'inventory-page__diff--warning'
  return 'inventory-page__diff--none'
}

const diffStatusCount = computed(() => stockRows.value.filter((row) => diffOf(row) !== 0).length)
const diffAbsTotal = computed(() => stockRows.value.reduce((sum, row) => sum + Math.abs(diffOf(row)), 0))

async function submitCheck() {
  if (!stockRows.value.length) {
    ElMessage.warning('账面数尚未加载，无法提交盘点')
    return
  }

  const expectJson = {}
  const actualJson = {}
  stockRows.value.forEach((row) => {
    expectJson[row.status] = Number(row.book) || 0
    actualJson[row.status] = Number(row.actual) || 0
  })

  const confirmText = diffStatusCount.value
    ? `有 ${diffStatusCount.value} 个状态账实不一致（差异合计 ${diffAbsTotal.value} 只）。提交后盘点单不可删除，仅可作废重盘，确认提交？`
    : '各状态账实一致，提交后盘点单将直接闭环归档。确认提交？'

  try {
    await ElMessageBox.confirm(confirmText, '提交盘点单', {
      confirmButtonText: '确认提交',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  submitting.value = true
  try {
    const payload = {
      expectJson,
      actualJson,
      // 袋级差异清单由后端按袋清单比对生成，前端按约定提交空数组
      diffBags: [],
      diffCount: diffAbsTotal.value,
    }
    if (remark.value.trim()) payload.remark = remark.value.trim()

    const data = await createInventoryCheck(payload)
    ElMessage.success(`盘点单已提交${data?.checkNo ? `：${data.checkNo}` : ''}`)
    remark.value = ''
    // 提交后账面口径可能已变化，重新拉账面数与盘点单列表
    await Promise.all([loadStock(), loadList()])
  } catch (error) {
    if (error?.code === 3001) {
      ElMessage.error(fieldErrorText(error) || error.message || '请检查盘点数据后重试')
    }
  } finally {
    submitting.value = false
  }
}

/* ---------------------------------------------------------------- 下方：盘点单列表 */

const list = ref([])
const total = ref(0)
const loading = ref(false)
const loadError = ref(false)
const dateRange = ref([])
const expandedKey = ref('')
const query = reactive({
  pageNum: 1,
  pageSize: DEFAULT_PAGE_SIZE,
  status: '',
  beginTime: '',
  endTime: '',
})

const statusOptions = Object.entries(INVENTORY_CHECK_STATUS_META).map(([value, meta]) => ({
  value,
  label: meta.label,
}))

async function loadList() {
  loading.value = true
  loadError.value = false
  try {
    const params = { pageNum: query.pageNum, pageSize: query.pageSize }
    if (query.status) params.status = query.status
    if (query.beginTime) params.beginTime = query.beginTime
    if (query.endTime) params.endTime = query.endTime
    if (userStore.stationId) params.stationId = userStore.stationId
    const data = await listInventoryChecks(params)
    list.value = Array.isArray(data?.records) ? data.records : []
    total.value = Number(data?.total) || 0
  } catch {
    loadError.value = true
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  query.pageNum = 1
  loadList()
}

function onDateChange(value) {
  query.beginTime = value?.[0] || ''
  query.endTime = value?.[1] || ''
  onFilterChange()
}

/* ---------------------------------------------------------------- 差异清单解析 */

function parseJsonSafe(value) {
  if (value == null || value === '') return null
  if (typeof value === 'object') return value
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}

/** 账面/实盘快照：JSON 对象 → [{ key, label, count }]，只展示非 0 项 */
function snapshotPairs(jsonValue) {
  const parsed = parseJsonSafe(jsonValue)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return []
  return Object.entries(parsed)
    .map(([key, value]) => {
      const status = toStatusKey(key)
      return {
        key,
        label: status ? BAG_STATUS_META[status].label : key,
        count: Number(value) || 0,
      }
    })
    .filter((pair) => pair.count !== 0)
}

/** 袋级差异清单：diff_bags_json → [{ bagCode, diffType }] */
function diffList(row) {
  const parsed = parseJsonSafe(row?.diffBagsJson ?? row?.diffBags)
  const items = Array.isArray(parsed) ? parsed : []
  return items.map((item) => ({
    bagCode: item?.bagCode || item?.bag_code || '—',
    diffType: item?.diffType || item?.diffDesc || item?.type || '—',
  }))
}

function rowKey(row) {
  return row.id || row.checkNo
}

function isExpanded(row) {
  return expandedKey.value === rowKey(row)
}

function toggleExpand(row) {
  const key = rowKey(row)
  expandedKey.value = expandedKey.value === key ? '' : key
}

/* ---------------------------------------------------------------- 作废盘点单 */

async function openVoid(row) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      '盘点单不可删除，仅可作废重盘。请填写作废原因（重盘说明）：',
      `作废盘点单 ${row.checkNo || ''}`,
      {
        confirmButtonText: '确认作废',
        cancelButtonText: '取消',
        type: 'warning',
        inputType: 'textarea',
        inputPlaceholder: '例如：实盘录入有误，需重新盘点',
        inputValidator: (text) => (text && text.trim() ? true : '作废原因必填'),
      },
    )
    reason = (value || '').trim()
  } catch {
    return
  }

  try {
    await voidInventoryCheck(row.id, { voidReason: reason })
    ElMessage.success('盘点单已作废，可重新发起盘点')
    await loadList()
  } catch (error) {
    if (error?.code === 3001) {
      ElMessage.error(fieldErrorText(error) || error.message || '作废失败，请检查填写内容')
    }
  }
}

/* ---------------------------------------------------------------- 生命周期 */

onMounted(() => {
  window.addEventListener('resize', syncMobile)
  loadStock()
  loadList()
})

onUnmounted(() => window.removeEventListener('resize', syncMobile))
</script>

<template>
  <div class="inventory-page">
    <!-- 上方：发起盘点 -->
    <PageCard ref="stockCardRef" title="发起盘点" subtitle="核对各状态账面数与实盘数，差异自动计算">
      <template v-slot:extra>
        <el-button v-if="userStore.stationId" :loading="stockLoading" @click="loadStock">刷新账面数</el-button>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="盘点单不可删除，仅可作废重盘；作废后原单保留可查。"
        class="inventory-page__note"
      />

      <PageSkeleton v-if="stockLoading" :rows="8" type="table" />

      <EmptyState
        v-else-if="!userStore.stationId"
        icon="WarningFilled"
        description="当前账号未绑定站点，无法发起本站盘点"
      />

      <EmptyState v-else-if="stockError" icon="WarningFilled" description="账面数加载失败，请检查网络后重试">
        <el-button type="primary" @click="loadStock">重试</el-button>
      </EmptyState>

      <template v-else>
        <el-table v-if="!isMobile" :data="stockRows" size="small">
          <el-table-column label="状态" min-width="130">
            <template v-slot:default="scope">
              <StatusTag kind="bag" :value="scope.row.status" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="账面数（只）" width="120">
            <template v-slot:default="scope">
              <span class="sems-num">{{ scope.row.book }}</span>
            </template>
          </el-table-column>
          <el-table-column label="实盘数（只）" width="170">
            <template v-slot:default="scope">
              <el-input-number
                v-model="scope.row.actual"
                :min="0"
                :step="1"
                size="small"
                controls-position="right"
              />
            </template>
          </el-table-column>
          <el-table-column label="差异（实盘 − 账面）" width="170">
            <template v-slot:default="scope">
              <span class="sems-num inventory-page__diff" :class="diffClass(scope.row)">
                {{ diffText(scope.row) }}
              </span>
            </template>
          </el-table-column>
        </el-table>

        <div v-else class="inventory-stock-cards">
          <div v-for="row in stockRows" :key="row.status" class="inventory-stock-card">
            <div class="inventory-stock-card__head">
              <StatusTag kind="bag" :value="row.status" size="small" />
              <span class="sems-num inventory-page__diff" :class="diffClass(row)">差异 {{ diffText(row) }}</span>
            </div>
            <div class="inventory-stock-card__body">
              <span class="sems-text-muted">账面 <span class="sems-num">{{ row.book }}</span> 只</span>
              <el-input-number v-model="row.actual" :min="0" :step="1" size="small" controls-position="right" />
            </div>
          </div>
        </div>

        <div class="sems-text-muted inventory-page__diff-tip">
          差异 = 实盘 − 账面：负数（红）为盘亏，正数（橙）为盘盈。实盘数默认按账面数填入，不一致处请手动修改。
        </div>

        <div class="inventory-page__submit">
          <el-input
            v-model="remark"
            maxlength="255"
            show-word-limit
            placeholder="备注（选填）"
            class="inventory-page__remark"
          />
          <el-button
            type="primary"
            class="inventory-page__submit-btn"
            :loading="submitting"
            @click="submitCheck"
          >
            提交盘点单
          </el-button>
        </div>
        <div class="sems-text-muted inventory-page__summary">
          当前 {{ diffStatusCount }} 个状态不一致，差异合计 {{ diffAbsTotal }} 只；袋级差异清单由后端按袋清单比对生成。
        </div>
      </template>
    </PageCard>

    <!-- 下方：盘点单列表 -->
    <PageCard title="盘点单" subtitle="盘点单不可删除，仅可作废重盘；展开行查看差异候选，须核对实物后处置">
      <div class="inventory-filters">
        <el-select
          v-model="query.status"
          placeholder="全部状态"
          clearable
          class="inventory-filters__status"
          @change="onFilterChange"
        >
          <el-option v-for="opt in statusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          value-format="YYYY-MM-DD HH:mm:ss"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          class="inventory-filters__date"
          @change="onDateChange"
        />
        <el-button @click="onFilterChange">刷新</el-button>
      </div>

      <PageSkeleton v-if="loading" :rows="6" type="table" />

      <EmptyState
        v-else-if="loadError"
        icon="WarningFilled"
        description="盘点单列表加载失败，请检查网络后重试"
      >
        <el-button type="primary" @click="loadList">重试</el-button>
      </EmptyState>

      <EmptyState v-else-if="!list.length" icon="DocumentChecked" description="暂无盘点单记录">
        <el-button type="primary" @click="focusStock">发起盘点</el-button>
      </EmptyState>

      <template v-else>
        <!-- 桌面：展开行看袋级差异清单 -->
        <el-table v-if="!isMobile" :data="list" size="small">
          <el-table-column type="expand">
            <template v-slot:default="scope">
              <div class="inventory-detail">
                <div class="inventory-detail__snapshot">
                  <span class="sems-text-muted">账面快照</span>
                  <span v-if="snapshotPairs(scope.row.expectJson).length" class="inventory-detail__chips">
                    <span v-for="pair in snapshotPairs(scope.row.expectJson)" :key="pair.key" class="inventory-detail__chip">
                      {{ pair.label }} <span class="sems-num">{{ pair.count }}</span>
                    </span>
                  </span>
                  <span v-else class="sems-text-muted">全部状态均为 0</span>
                </div>
                <div class="inventory-detail__snapshot">
                  <span class="sems-text-muted">实盘快照</span>
                  <span v-if="snapshotPairs(scope.row.actualJson).length" class="inventory-detail__chips">
                    <span v-for="pair in snapshotPairs(scope.row.actualJson)" :key="pair.key" class="inventory-detail__chip">
                      {{ pair.label }} <span class="sems-num">{{ pair.count }}</span>
                    </span>
                  </span>
                  <span v-else class="sems-text-muted">全部状态均为 0</span>
                </div>

                <div class="inventory-detail__title sems-section-title">袋级差异清单</div>
                <el-table v-if="diffList(scope.row).length" :data="diffList(scope.row)" size="small">
                  <el-table-column label="袋码" min-width="160">
                    <template v-slot:default="inner">
                      <span class="sems-mono">{{ inner.row.bagCode }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="diffType" label="差异类型" min-width="120" />
                </el-table>
                <div v-else class="sems-text-muted">差异清单为空：账实一致，或差异明细由后端按袋清单比对生成后回填。</div>

                <div v-if="scope.row.status === 'VOID'" class="sems-text-muted inventory-detail__void">
                  作废原因：{{ scope.row.voidReason || '—' }}（{{ fmtTime(scope.row.voidedAt) }}）
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="盘点单号" min-width="180">
            <template v-slot:default="scope">
              <span class="sems-mono">{{ scope.row.checkNo || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="盘点时间" width="150">
            <template v-slot:default="scope">{{ fmtTime(scope.row.checkTime || scope.row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template v-slot:default="scope">
              <StatusTag kind="inventoryCheck" :value="scope.row.status" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="差异候选数" width="104">
            <template v-slot:default="scope">
              <span
                class="sems-num"
                :class="Number(scope.row.diffCount) > 0 ? 'inventory-page__diff--danger' : 'inventory-page__diff--none'"
              >
                {{ scope.row.diffCount ?? 0 }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="88" fixed="right">
            <template v-slot:default="scope">
              <el-button
                v-if="scope.row.status === 'PENDING_FIX'"
                link
                type="danger"
                size="small"
                @click="openVoid(scope.row)"
              >
                作废
              </el-button>
              <span v-else class="sems-text-muted">—</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 移动端：卡片列表 + 折叠差异 -->
        <div v-else class="inventory-checks">
          <div v-for="row in list" :key="rowKey(row)" class="inventory-check-card">
            <div class="inventory-check-card__head">
              <span class="sems-mono">{{ row.checkNo || '—' }}</span>
              <StatusTag kind="inventoryCheck" :value="row.status" size="small" />
            </div>
            <div class="inventory-check-card__row">
              <span class="sems-text-muted">盘点时间</span>
              <span>{{ fmtTime(row.checkTime || row.createdAt) }}</span>
            </div>
            <div class="inventory-check-card__row">
              <span class="sems-text-muted">差异袋数</span>
              <span
                class="sems-num"
                :class="Number(row.diffCount) > 0 ? 'inventory-page__diff--danger' : 'inventory-page__diff--none'"
              >
                {{ row.diffCount ?? 0 }}
              </span>
            </div>
            <div class="inventory-check-card__foot">
              <el-button size="small" @click="toggleExpand(row)">
                {{ isExpanded(row) ? '收起差异' : '查看差异' }}
              </el-button>
              <el-button
                v-if="row.status === 'PENDING_FIX'"
                size="small"
                type="danger"
                plain
                @click="openVoid(row)"
              >
                作废
              </el-button>
            </div>

            <div v-if="isExpanded(row)" class="inventory-detail">
              <div class="inventory-detail__snapshot">
                <span class="sems-text-muted">账面快照</span>
                <span v-if="snapshotPairs(row.expectJson).length" class="inventory-detail__chips">
                  <span v-for="pair in snapshotPairs(row.expectJson)" :key="pair.key" class="inventory-detail__chip">
                    {{ pair.label }} <span class="sems-num">{{ pair.count }}</span>
                  </span>
                </span>
                <span v-else class="sems-text-muted">全部状态均为 0</span>
              </div>
              <div class="inventory-detail__snapshot">
                <span class="sems-text-muted">实盘快照</span>
                <span v-if="snapshotPairs(row.actualJson).length" class="inventory-detail__chips">
                  <span v-for="pair in snapshotPairs(row.actualJson)" :key="pair.key" class="inventory-detail__chip">
                    {{ pair.label }} <span class="sems-num">{{ pair.count }}</span>
                  </span>
                </span>
                <span v-else class="sems-text-muted">全部状态均为 0</span>
              </div>

              <div class="inventory-detail__title sems-section-title">袋级差异清单</div>
              <ul v-if="diffList(row).length" class="inventory-detail__diffs">
                <li v-for="(item, index) in diffList(row)" :key="index" class="inventory-detail__diff-item">
                  <span class="sems-mono">{{ item.bagCode }}</span>
                  <span>{{ item.diffType }}</span>
                </li>
              </ul>
              <div v-else class="sems-text-muted">差异清单为空：账实一致，或差异明细由后端按袋清单比对生成后回填。</div>

              <div v-if="row.status === 'VOID'" class="sems-text-muted inventory-detail__void">
                作废原因：{{ row.voidReason || '—' }}（{{ fmtTime(row.voidedAt) }}）
              </div>
            </div>
          </div>
        </div>

        <div class="inventory-page__pager">
          <DataPager
            v-model:page="query.pageNum"
            v-model:size="query.pageSize"
            :total="total"
            @change="loadList"
          />
        </div>
      </template>
    </PageCard>
  </div>
</template>

<style scoped>
.inventory-page {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-5);
}

.inventory-page__note {
  margin-bottom: var(--sems-space-4);
}

/* 差异色标：负数盘亏红、正数盘盈橙、0 次要色（文案同时带正负号，不靠颜色单独承载语义） */
.inventory-page__diff {
  font-weight: var(--sems-font-weight-medium);
}

.inventory-page__diff--danger {
  color: var(--sems-color-danger);
  font-weight: var(--sems-font-weight-medium);
}

.inventory-page__diff--warning {
  color: var(--sems-color-warning);
  font-weight: var(--sems-font-weight-medium);
}

.inventory-page__diff--none {
  color: var(--sems-text-secondary);
}

.inventory-page__diff-tip {
  margin-top: var(--sems-space-3);
}

.inventory-page__submit {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sems-space-3);
  margin-top: var(--sems-space-4);
}

/* 站点端手机：主操作按钮 48px 大字（设计书 10.5） */
@media (max-width: 767px) {
  .inventory-page__submit-btn {
    width: 100%;
    min-height: var(--sems-btn-height-touch);
    font-size: var(--sems-font-size-lg);
  }
}

.inventory-page__remark {
  flex: 1 1 260px;
  max-width: 420px;
}

.inventory-page__summary {
  margin-top: var(--sems-space-2);
}

/* ------------------------------ 移动端账面数卡片 ------------------------------ */

.inventory-stock-cards {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
}

.inventory-stock-card {
  padding: var(--sems-space-3);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-base);
}

.inventory-stock-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--sems-space-2);
}

.inventory-stock-card__body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
}

/* ------------------------------ 筛选与列表 ------------------------------ */

.inventory-filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-4);
}

.inventory-filters__status {
  width: 150px;
}

.inventory-filters__date {
  width: 340px;
  max-width: 100%;
}

.inventory-checks {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
}

.inventory-check-card {
  padding: var(--sems-space-3);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-base);
}

.inventory-check-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-2);
}

.inventory-check-card__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  padding: var(--sems-space-1) 0;
  font-size: var(--sems-font-size-sm);
}

.inventory-check-card__foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--sems-space-2);
  margin-top: var(--sems-space-2);
}

/* ------------------------------ 差异明细（展开区） ------------------------------ */

.inventory-detail {
  padding: var(--sems-space-2) 0;
}

.inventory-detail__snapshot {
  display: flex;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-2);
  font-size: var(--sems-font-size-sm);
}

.inventory-detail__chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sems-space-1);
}

.inventory-detail__chip {
  padding: 0 var(--sems-space-2);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-tag);
  color: var(--sems-text-regular);
  line-height: 20px;
}

.inventory-detail__title {
  margin: var(--sems-space-3) 0 var(--sems-space-2);
}

.inventory-detail__diffs {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-1);
  margin: 0;
  padding: 0;
  list-style: none;
}

.inventory-detail__diff-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  padding: var(--sems-space-2);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-tag);
  font-size: var(--sems-font-size-sm);
}

.inventory-detail__void {
  margin-top: var(--sems-space-2);
}

.inventory-page__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--sems-space-4);
}
</style>
