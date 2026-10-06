<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DataPager from '@/components/DataPager.vue'
import BagCodeInput from '@/components/BagCodeInput.vue'
import BatchResultDialog from '@/components/BatchResultDialog.vue'
import StatusTag from '@/components/StatusTag.vue'
import { createDisinfection, listDisinfections } from '@/api/bag'
import { useUserStore } from '@/stores/user'
import {
  BATCH_MAX_SIZE,
  DEFAULT_PAGE_SIZE,
  DISINFECT_BATCH_MAX,
  DISINFECT_METHODS,
  DISINFECT_RESULT_META,
} from '@/constants'
import { extractBatch } from '@/utils/batch'
import { BAG_CODE_REGEX } from '@/utils/validators'

/**
 * 消毒登记（站点端，见《系统设计说明书》4.3 / 5.4）。
 *
 * - 左栏登记新批次：消毒方式 + 起止时间 + 备注 + 袋码明细。
 *   单批上限 200 只（DISINFECT_BATCH_MAX，是默认批量上限 500 只的特例，超出前端先拦，
 *   后端 3001 才是权威）；每袋结果「合格/不合格」，不合格必须填缺陷说明（非空校验）。
 * - 右栏批次列表：分页 + 按结果/时间筛选；点开单批查看逐袋结果。
 * - 提交结果按逐袋明细用 BatchResultDialog 展示；失败袋码保留在明细中便于重试。
 */

const userStore = useUserStore()

/* ---------------------------------------------------------------- 响应式：<768px 表格转卡片 */

const isMobile = ref(window.innerWidth < 768)
function syncMobile() {
  isMobile.value = window.innerWidth < 768
}

/* ---------------------------------------------------------------- 左栏：登记新批次 */

const formRef = ref()
const form = reactive({ method: '', startTime: '', endTime: '', remark: '' })
const rows = ref([])
const inputCode = ref('')
const submitting = ref(false)

/** 每袋结果只允许「合格/不合格」（「部分不合格」是批次汇总值，由后端按明细汇总） */
const PER_BAG_RESULTS = Object.keys(DISINFECT_RESULT_META).filter((key) => key !== '部分不合格')
/** 批次列表的筛选档位（含汇总值「部分不合格」） */
const resultOptions = Object.entries(DISINFECT_RESULT_META).map(([value, meta]) => ({ value, label: meta.label }))

function pad2(n) {
  return String(n).padStart(2, '0')
}

function nowStr() {
  const d = new Date()
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
}

const rules = {
  method: [{ required: true, message: '请选择消毒方式', trigger: 'change' }],
  startTime: [{ required: true, message: '请选择消毒开始时间', trigger: 'change' }],
  endTime: [
    { required: true, message: '请选择消毒结束时间', trigger: 'change' },
    {
      validator: (_rule, value, callback) => {
        if (!value || !form.startTime || value >= form.startTime) return callback()
        return callback(new Error('结束时间不能早于开始时间'))
      },
      trigger: 'change',
    },
  ],
}

function resetFormFill() {
  form.startTime = nowStr()
  form.endTime = nowStr()
}

/** 袋码累加：格式校验 + 批次内去重 + 200 只上限（特例值）拦截；payload 为空时取输入框当前值 */
function addBagCode(payload) {
  const fromScan = typeof payload === 'string' ? payload : ''
  const code = (fromScan || inputCode.value || '').trim().toUpperCase()
  if (!code) {
    ElMessage.warning('请输入或扫描袋码')
    return
  }
  if (!BAG_CODE_REGEX.test(code)) {
    ElMessage.warning('袋码格式形如 SJB-L-2609-00001-4，请核对后重试')
    return
  }
  if (rows.value.some((item) => item.bagCode === code)) {
    ElMessage.warning(`${code} 已在本批次明细中，请勿重复录入`)
    return
  }
  if (rows.value.length >= DISINFECT_BATCH_MAX) {
    ElMessage.warning(
      `单批消毒上限 ${DISINFECT_BATCH_MAX} 只（批量上限 ${BATCH_MAX_SIZE} 只的特例），请先提交本批再登记下一批`,
    )
    return
  }
  rows.value.push({ bagCode: code, result: '合格', defectDesc: '', defectError: false })
  inputCode.value = ''
}

/** 结果切回「合格」时清掉缺陷说明的错误标记 */
function onResultChange(row) {
  if (row.result !== '不合格') row.defectError = false
}

function clearRows() {
  rows.value = []
}

function focusForm() {
  resetFormFill()
  const el = formRef.value?.$el
  if (el && typeof el.scrollIntoView === 'function') {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

/* 提交结果弹窗（逐袋明细）：items 来自后端统一信封（utils/batch.js 解析） */
const resultVisible = ref(false)
const resultItems = ref([])
/** 批次计数：来自后端信封（success/failed 表示「处理成功/失败」，不合格也算处理成功） */
const resultTotal = ref(0)
const resultFailed = ref(0)

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

async function submitBatch() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  if (!rows.value.length) {
    ElMessage.warning('请先录入本批次的袋码明细')
    return
  }
  const invalid = rows.value.find((item) => item.result === '不合格' && !item.defectDesc.trim())
  if (invalid) {
    invalid.defectError = true
    ElMessage.error(`袋码 ${invalid.bagCode} 结果为不合格，必须填写缺陷说明`)
    return
  }

  submitting.value = true
  try {
    const payload = {
      method: form.method,
      startTime: form.startTime,
      endTime: form.endTime,
      remark: form.remark.trim() || undefined,
      details: rows.value.map((item) => ({
        bagCode: item.bagCode,
        result: item.result,
        defectDesc: item.result === '不合格' ? item.defectDesc.trim() : '',
      })),
    }
    const data = await createDisinfection(payload)
    const { items, total: batchTotal, failed: batchFailed } = extractBatch(data)
    // 逐袋判定计数：合格/不合格以明细的 result 字段为准；信封的 success/failed 表示「处理成功/失败」
    // （不合格也是处理成功），两个口径不能混用；处理失败的袋 result 为空，不计入两档
    const passCount = items.filter((item) => item && item.result === '合格').length
    const failCount = items.filter((item) => item && item.result === '不合格').length

    if (items.length) {
      // 结果按逐袋明细展示（合格/不合格与原因一目了然）
      resultItems.value = items
      resultTotal.value = batchTotal
      resultFailed.value = batchFailed
      resultVisible.value = true
    }
    if (batchFailed > 0) {
      // 逐袋部分失败（处理异常）：失败袋码留在明细里，便于核对后重试
      const failedCodes = new Set(
        items.filter((item) => item && !item.success).map((item) => item.bagCode),
      )
      rows.value = rows.value.filter((item) => failedCodes.has(item.bagCode))
      ElMessage.warning(`${batchFailed} 只袋处理异常，请按明细核对后重试`)
    } else {
      // 全部处理成功：用批次号与逐袋判定计数做提示（合格 x / 不合格 y），并附批次汇总口径
      const batchNoText = data?.batchNo ? `批次 ${data.batchNo}` : '消毒批次'
      const summary = data?.result ? `（批次汇总：${data.result}）` : ''
      ElMessage.success(`${batchNoText}登记完成：合格 ${passCount} / 不合格 ${failCount}${summary}`)
      rows.value = []
      form.remark = ''
    }
    inputCode.value = ''
    await loadList()
  } catch (error) {
    if (error?.code === 3001) {
      ElMessage.error(fieldErrorText(error) || error.message || '请检查登记内容后重试')
    }
  } finally {
    submitting.value = false
  }
}

/* ---------------------------------------------------------------- 右栏：批次列表 */

const list = ref([])
const total = ref(0)
const loading = ref(false)
const loadError = ref(false)
const dateRange = ref([])
const query = reactive({
  pageNum: 1,
  pageSize: DEFAULT_PAGE_SIZE,
  result: '',
  beginTime: '',
  endTime: '',
})

async function loadList() {
  loading.value = true
  loadError.value = false
  try {
    const params = { pageNum: query.pageNum, pageSize: query.pageSize }
    if (query.result) params.result = query.result
    if (query.beginTime) params.beginTime = query.beginTime
    if (query.endTime) params.endTime = query.endTime
    if (userStore.stationId) params.stationId = userStore.stationId
    const data = await listDisinfections(params)
    list.value = Array.isArray(data?.records) ? data.records : []
    total.value = Number(data?.total) || 0
  } catch {
    // 具体错误已由 request.js 统一提示，这里只切错误态
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

/* ---------------------------------------------------------------- 批次明细抽屉 */

const detailVisible = ref(false)
const currentBatch = ref(null)
const detailTitle = computed(() =>
  currentBatch.value?.batchNo ? `消毒批次 ${currentBatch.value.batchNo}` : '消毒批次明细',
)

/** 批次留档明细：查询接口（DisinfectVo）的逐袋明细固定放在 details 键，元素为 bagCode / result / defectDesc */
function batchDetails(batch) {
  const source = batch?.details
  if (!Array.isArray(source)) return []
  return source.map((item) => ({
    bagCode: item.bagCode || '',
    result: item.result || '',
    defectDesc: item.defectDesc || '',
  }))
}

function openDetail(row) {
  currentBatch.value = row
  detailVisible.value = true
}

/* ---------------------------------------------------------------- 通用格式化 */

function fmtTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

/* ---------------------------------------------------------------- 生命周期 */

onMounted(() => {
  window.addEventListener('resize', syncMobile)
  resetFormFill()
  loadList()
})

onUnmounted(() => window.removeEventListener('resize', syncMobile))
</script>

<template>
  <div class="disinfect-page">
    <div class="disinfect-page__grid">
      <!-- 左栏：登记新批次 -->
      <PageCard title="登记新批次" subtitle="合格转「可复用」，不合格转「维修中」">
        <template v-slot:extra>
          <span class="sems-text-muted">已录入 {{ rows.length }} / {{ DISINFECT_BATCH_MAX }} 只</span>
        </template>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
          <el-form-item label="消毒方式" prop="method">
            <el-select v-model="form.method" placeholder="请选择消毒方式" style="width: 100%">
              <el-option v-for="method in DISINFECT_METHODS" :key="method" :label="method" :value="method" />
            </el-select>
          </el-form-item>

          <div class="disinfect-page__time-row">
            <el-form-item label="开始时间" prop="startTime">
              <el-date-picker
                v-model="form.startTime"
                type="datetime"
                value-format="YYYY-MM-DD HH:mm:ss"
                placeholder="选择开始时间"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="结束时间" prop="endTime">
              <el-date-picker
                v-model="form.endTime"
                type="datetime"
                value-format="YYYY-MM-DD HH:mm:ss"
                placeholder="选择结束时间"
                style="width: 100%"
              />
            </el-form-item>
          </div>

          <el-form-item label="备注" prop="remark">
            <el-input
              v-model="form.remark"
              maxlength="255"
              show-word-limit
              placeholder="选填：设备编号、批次说明等"
            />
          </el-form-item>
        </el-form>

        <div class="disinfect-page__codes-head">
          <span class="sems-section-title">袋码明细</span>
          <el-button v-if="rows.length" link type="danger" @click="clearRows">清空</el-button>
        </div>
        <div class="sems-text-muted disinfect-page__codes-tip">
          单批上限 {{ DISINFECT_BATCH_MAX }} 只（批量上限 {{ BATCH_MAX_SIZE }} 只的特例）；结果为不合格的袋必须填写缺陷说明。
        </div>

        <div class="disinfect-page__scan">
          <div class="disinfect-page__scan-input">
            <BagCodeInput v-model="inputCode" @scan="addBagCode" />
          </div>
          <el-button type="primary" plain @click="addBagCode()">加入明细</el-button>
        </div>

        <div v-if="!rows.length" class="disinfect-page__codes-empty">
          <el-icon :size="18"><Box /></el-icon>
          <span class="sems-text-muted">尚未录入袋码：扫描或手输袋码后点「加入明细」</span>
        </div>

        <el-table v-if="!isMobile && rows.length" :data="rows" size="small" class="disinfect-page__table">
          <el-table-column type="index" label="序号" width="60" />
          <el-table-column label="袋码" min-width="170">
            <template v-slot:default="scope">
              <span class="sems-mono">{{ scope.row.bagCode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="118">
            <template v-slot:default="scope">
              <el-select v-model="scope.row.result" size="small" @change="onResultChange(scope.row)">
                <el-option v-for="key in PER_BAG_RESULTS" :key="key" :label="key" :value="key" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="缺陷说明（不合格必填）" min-width="180">
            <template v-slot:default="scope">
              <el-input
                v-if="scope.row.result === '不合格'"
                v-model="scope.row.defectDesc"
                size="small"
                maxlength="255"
                placeholder="请描述缺陷情况"
                :class="{ 'is-error': scope.row.defectError }"
                @input="scope.row.defectError = false"
              />
              <span v-else class="sems-text-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="76">
            <template v-slot:default="scope">
              <el-button link type="danger" size="small" @click="rows.splice(scope.$index, 1)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="isMobile && rows.length" class="disinfect-rows">
          <div v-for="(row, index) in rows" :key="row.bagCode" class="disinfect-rows__item">
            <div class="disinfect-rows__head">
              <span class="sems-mono">{{ row.bagCode }}</span>
              <el-button link type="danger" size="small" @click="rows.splice(index, 1)">移除</el-button>
            </div>
            <div class="disinfect-rows__field">
              <span class="sems-text-muted">结果</span>
              <el-select v-model="row.result" size="small" style="width: 120px" @change="onResultChange(row)">
                <el-option v-for="key in PER_BAG_RESULTS" :key="key" :label="key" :value="key" />
              </el-select>
            </div>
            <div v-if="row.result === '不合格'" class="disinfect-rows__field disinfect-rows__field--column">
              <span class="sems-text-muted">缺陷说明（必填）</span>
              <el-input
                v-model="row.defectDesc"
                size="small"
                maxlength="255"
                placeholder="请描述缺陷情况"
                :class="{ 'is-error': row.defectError }"
                @input="row.defectError = false"
              />
            </div>
          </div>
        </div>

        <div class="disinfect-page__submit">
          <el-button
            type="primary"
            class="disinfect-page__submit-btn"
            :loading="submitting"
            @click="submitBatch"
          >
            提交消毒批次（{{ rows.length }} 只）
          </el-button>
          <span class="sems-text-muted">不合格袋转入维修中，请在「维修报废」页继续处置</span>
        </div>
      </PageCard>

      <!-- 右栏：批次列表 -->
      <PageCard title="消毒批次" subtitle="按批次留档，可查看逐袋结果">
        <div class="disinfect-filters">
          <el-select
            v-model="query.result"
            placeholder="全部结果"
            clearable
            class="disinfect-filters__result"
            @change="onFilterChange"
          >
            <el-option v-for="opt in resultOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
          <el-date-picker
            v-model="dateRange"
            type="datetimerange"
            value-format="YYYY-MM-DD HH:mm:ss"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            class="disinfect-filters__date"
            @change="onDateChange"
          />
          <el-button @click="onFilterChange">刷新</el-button>
        </div>

        <PageSkeleton v-if="loading" :rows="6" type="table" />

        <EmptyState
          v-else-if="loadError"
          icon="WarningFilled"
          description="批次列表加载失败，请检查网络后重试"
        >
          <el-button type="primary" @click="loadList">重试</el-button>
        </EmptyState>

        <EmptyState v-else-if="!list.length" icon="Box" description="暂无消毒批次记录">
          <el-button type="primary" @click="focusForm">登记新批次</el-button>
        </EmptyState>

        <template v-else>
          <el-table v-if="!isMobile" :data="list" size="small">
            <el-table-column label="批次号" min-width="170">
              <template v-slot:default="scope">
                <span class="sems-mono">{{ scope.row.batchNo || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="method" label="方式" width="82" />
            <el-table-column label="消毒时间" min-width="180">
              <template v-slot:default="scope">
                {{ fmtTime(scope.row.startTime) }} ~ {{ fmtTime(scope.row.endTime) }}
              </template>
            </el-table-column>
            <el-table-column label="结果" width="96">
              <template v-slot:default="scope">
                <StatusTag kind="disinfectResult" :value="scope.row.result" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="袋数（合格/不合格）" min-width="150">
              <template v-slot:default="scope">
                <span class="sems-num">{{ scope.row.bagCount ?? 0 }}</span> 只（
                <span class="sems-num">{{ scope.row.passCount ?? 0 }}</span> /
                <span class="sems-num">{{ scope.row.failCount ?? 0 }}</span>）
              </template>
            </el-table-column>
            <el-table-column label="操作" width="112" fixed="right">
              <template v-slot:default="scope">
                <el-button link type="primary" size="small" @click="openDetail(scope.row)">逐袋明细</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div v-else class="disinfect-batches">
            <div v-for="row in list" :key="row.id || row.batchNo" class="disinfect-batch-card">
              <div class="disinfect-batch-card__head">
                <span class="sems-mono">{{ row.batchNo || '—' }}</span>
                <StatusTag kind="disinfectResult" :value="row.result" size="small" />
              </div>
              <div class="disinfect-batch-card__row">
                <span class="sems-text-muted">消毒方式</span><span>{{ row.method || '—' }}</span>
              </div>
              <div class="disinfect-batch-card__row">
                <span class="sems-text-muted">消毒时间</span>
                <span>{{ fmtTime(row.startTime) }} ~ {{ fmtTime(row.endTime) }}</span>
              </div>
              <div class="disinfect-batch-card__row">
                <span class="sems-text-muted">袋数（合格/不合格）</span>
                <span class="sems-num">{{ row.bagCount ?? 0 }} 只（{{ row.passCount ?? 0 }} / {{ row.failCount ?? 0 }}）</span>
              </div>
              <div class="disinfect-batch-card__foot">
                <el-button size="small" @click="openDetail(row)">查看逐袋明细</el-button>
              </div>
            </div>
          </div>

          <div class="disinfect-page__pager">
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

    <!-- 批次明细抽屉 -->
    <el-drawer v-model="detailVisible" :size="isMobile ? '100%' : '560px'" :title="detailTitle">
      <div v-if="currentBatch" class="disinfect-detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="批次号">
            <span class="sems-mono">{{ currentBatch.batchNo || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="消毒方式">{{ currentBatch.method || '—' }}</el-descriptions-item>
          <el-descriptions-item label="消毒时间">
            {{ fmtTime(currentBatch.startTime) }} ~ {{ fmtTime(currentBatch.endTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="批次结果">
            <StatusTag kind="disinfectResult" :value="currentBatch.result" size="small" />
          </el-descriptions-item>
          <el-descriptions-item label="袋数统计">
            共 {{ currentBatch.bagCount ?? 0 }} 只（合格 {{ currentBatch.passCount ?? 0 }} / 不合格
            {{ currentBatch.failCount ?? 0 }}）
          </el-descriptions-item>
          <el-descriptions-item label="备注">{{ currentBatch.remark || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="disinfect-detail__title sems-section-title">逐袋结果</div>
        <el-table v-if="batchDetails(currentBatch).length" :data="batchDetails(currentBatch)" size="small">
          <el-table-column label="袋码" min-width="150">
            <template v-slot:default="scope">
              <span class="sems-mono">{{ scope.row.bagCode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="88">
            <template v-slot:default="scope">
              <StatusTag kind="disinfectResult" :value="scope.row.result" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="缺陷说明" min-width="140">
            <template v-slot:default="scope">{{ scope.row.defectDesc || '—' }}</template>
          </el-table-column>
        </el-table>
        <EmptyState v-else icon="Box" description="该批次未返回袋码明细" />
      </div>
    </el-drawer>

    <BatchResultDialog
      v-model="resultVisible"
      :results="resultItems"
      :total="resultTotal"
      :failed="resultFailed"
    />
  </div>
</template>

<style scoped>
.disinfect-page__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: var(--sems-space-5);
  align-items: start;
}

@media (max-width: 1023px) {
  .disinfect-page__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.disinfect-page__time-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sems-space-4);
}

@media (max-width: 480px) {
  .disinfect-page__time-row {
    grid-template-columns: 1fr;
    gap: 0;
  }
}

.disinfect-page__codes-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--sems-space-1);
}

.disinfect-page__codes-tip {
  margin-bottom: var(--sems-space-3);
}

.disinfect-page__scan {
  display: flex;
  align-items: flex-start;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-3);
}

.disinfect-page__scan-input {
  flex: 1 1 auto;
  min-width: 0;
}

.disinfect-page__codes-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--sems-space-2);
  padding: var(--sems-space-5) 0;
  border: 1px dashed var(--sems-border);
  border-radius: var(--sems-radius-base);
}

.disinfect-page__table {
  margin-bottom: var(--sems-space-3);
}

.disinfect-page__table :deep(.is-error .el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--sems-color-danger) inset;
}

.disinfect-page__submit {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sems-space-3);
  margin-top: var(--sems-space-4);
}

/* 站点端手机：消毒确认按全屏单任务页处理，主按钮 48px 大字（设计书 10.5） */
@media (max-width: 767px) {
  .disinfect-page__submit-btn {
    width: 100%;
    min-height: var(--sems-btn-height-touch);
    font-size: var(--sems-font-size-lg);
  }
}

/* ------------------------------ 移动端袋码明细 ------------------------------ */

.disinfect-rows {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
}

.disinfect-rows__item {
  padding: var(--sems-space-3);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-base);
}

.disinfect-rows__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--sems-space-2);
}

.disinfect-rows__field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  margin-top: var(--sems-space-2);
}

.disinfect-rows__field--column {
  flex-direction: column;
  align-items: stretch;
}

.disinfect-rows__field :deep(.is-error .el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--sems-color-danger) inset;
}

/* ------------------------------ 右栏筛选与列表 ------------------------------ */

.disinfect-filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-4);
}

.disinfect-filters__result {
  width: 140px;
}

.disinfect-filters__date {
  width: 340px;
  max-width: 100%;
}

.disinfect-batches {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
}

.disinfect-batch-card {
  padding: var(--sems-space-3);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-base);
}

.disinfect-batch-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-2);
}

.disinfect-batch-card__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  padding: var(--sems-space-1) 0;
  font-size: var(--sems-font-size-sm);
}

.disinfect-batch-card__foot {
  margin-top: var(--sems-space-2);
  text-align: right;
}

.disinfect-page__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--sems-space-4);
}

.disinfect-detail__title {
  margin: var(--sems-space-5) 0 var(--sems-space-3);
}
</style>
