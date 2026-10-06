<script setup>
/**
 * 平台端 · 调拨管理（见《系统设计说明书》4.4「平台端」、5.3 与 3.4.3，FR-M3-11~16）。
 *
 * 覆盖功能：
 *  1) 调拨单分页查询：状态 / 源站 / 目标站 / 时间区间筛选；
 *  2) 创建调拨单：源站、目标站（不得相同）+ 明细袋码清单（扫码累加 + 手输批量粘贴）；
 *     **建单即锁定明细袋**，被锁定的袋不能再被申领/转维修/报废/修正；
 *  3) 详情抽屉：明细逐袋核收进度；在途满 48 小时给 warning 提示；
 *  4) 操作：取消（仅「待出库」，明细解锁回空闲）、剔除明细（仅「待出库/在途」且该明细未出库）、
 *     在途遗失确认（仅「在途」）、异常后终止。
 *
 * 数据范围：后端对相关站点管理员默认强制过滤为「源站或目标站=操作人站点」（5.3），
 * 平台管理员看全量；前端不自行传限权参数。
 */
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ALLOCATION_DETAIL_STATUS_META,
  ALLOCATION_STATUS_META,
  BATCH_MAX_SIZE,
  DEFAULT_PAGE_SIZE,
} from '@/constants'
import {
  cancelAllocation,
  confirmAllocationLost,
  createAllocation,
  getAllocation,
  listAllocations,
  removeAllocationDetail,
} from '@/api/allocation'
import { listStations } from '@/api/station'
import { BAG_CODE_REGEX, toUpperCase } from '@/utils/validators'
import StatusTag from '@/components/StatusTag.vue'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DataPager from '@/components/DataPager.vue'
import BagCodeInput from '@/components/BagCodeInput.vue'

/* ============================== 列表查询 ============================== */

const query = reactive({
  pageNum: 1,
  pageSize: DEFAULT_PAGE_SIZE,
  status: '',
  fromStationId: null,
  toStationId: null,
})

/** 时间区间（值格式 yyyy-MM-dd HH:mm:ss，与后端 beginTime/endTime 约定一致） */
const timeRange = ref([])
const defaultTime = [new Date(2000, 1, 1, 0, 0, 0), new Date(2000, 1, 1, 23, 59, 59)]

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')

const hasFilter = computed(
  () =>
    !!(
      query.status ||
      query.fromStationId ||
      query.toStationId ||
      (timeRange.value && timeRange.value.length)
    ),
)

/** 状态下拉：6 项取自 ALLOCATION_STATUS_META */
const statusOptions = computed(() =>
  Object.entries(ALLOCATION_STATUS_META).map(([value, meta]) => ({ value, label: meta.label })),
)

/** <768px 表格转卡片列表（10.5：禁止横向滚动） */
const isMobile = ref(false)
function syncLayout() {
  isMobile.value = window.innerWidth < 768
}

/* ------------------------------ 站点下拉 ------------------------------ */

const stationOptions = ref([])

async function loadStations() {
  try {
    const res = await listStations({ pageNum: 1, pageSize: 100 })
    stationOptions.value = (Array.isArray(res) ? res : res?.records) || []
  } catch {
    // 站点下拉加载失败不阻断调拨单列表；request.js 已统一提示
    stationOptions.value = []
  }
}

function stationLabel(station) {
  return station?.name || station?.stationName || `站点 ID ${station?.id ?? ''}`
}

/* ------------------------------ 加载与筛选 ------------------------------ */

function buildQuery() {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.status) params.status = query.status
  if (query.fromStationId) params.fromStationId = query.fromStationId
  if (query.toStationId) params.toStationId = query.toStationId
  const [begin, end] = timeRange.value || []
  if (begin) params.beginTime = begin
  if (end) params.endTime = end
  return params
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await listAllocations(buildQuery())
    rows.value = data?.records || []
    total.value = data?.total || 0
  } catch (error) {
    // 失败时 request.js 已统一提示；页面负责错误态与重试入口
    errorMessage.value = error?.message || '调拨单列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search() {
  query.pageNum = 1
  load()
}

function resetQuery() {
  query.status = ''
  query.fromStationId = null
  query.toStationId = null
  timeRange.value = []
  search()
}

/* ============================== 展示辅助 ============================== */

/** 在途超时口径：出库满 48 小时未核收（《系统设计说明书》3.5 时限表） */
const TRANSIT_TIMEOUT_MS = 48 * 60 * 60 * 1000

function over48h(time) {
  if (!time) return false
  const parsed = new Date(String(time).replace(' ', 'T')).getTime()
  return !Number.isNaN(parsed) && Date.now() - parsed >= TRANSIT_TIMEOUT_MS
}

function isRowTimeout(row) {
  if (row?.status !== 'IN_TRANSIT') return false
  if (row?.timeoutFlag || row?.timeout) return true
  return over48h(row?.dispatchAt)
}

function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

function stationText(row) {
  const from = row?.fromStationName || row?.fromStationId || '—'
  const to = row?.toStationName || row?.toStationId || '—'
  return `${from} → ${to}`
}

/** 核收进度：列表行可能只带汇总数（字段名兼容后端出参的几种形态） */
function progressText(row) {
  const totalCount = row?.totalCount ?? row?.bagCount ?? row?.detailCount
  const received = row?.receivedCount ?? row?.received
  if (totalCount === null || totalCount === undefined) return '—'
  return `${received ?? 0} / ${totalCount}`
}

/* ============================== 提交错误提示 ============================== */

/** 3001 的字段级明细由表单侧展示（request.js 对 3001 不弹提示，见 6.6）；其余错误码已由 request.js 提示 */
function reportSubmitError(error) {
  if (error?.code !== 3001) return
  const fieldErrors = error.data?.fieldErrors || {}
  const first = Object.values(fieldErrors)[0]
  const tip = Array.isArray(first) ? first[0] : first
  ElMessage.warning(tip || error.message || '提交内容有误，请检查后重试')
}

/* ============================== 创建调拨单 ============================== */

const createVisible = ref(false)
const createSubmitting = ref(false)
const createFormRef = ref(null)
const createForm = reactive({ fromStationId: null, toStationId: null })

/** 源站与目标站不得相同（两字段共用同一校验器，任一变化都会触发校验） */
function notSameStation(_rule, _value, callback) {
  if (createForm.fromStationId && createForm.fromStationId === createForm.toStationId) {
    return callback(new Error('源站点与目标站点不能相同'))
  }
  return callback()
}

const createRules = {
  fromStationId: [
    { required: true, message: '请选择源站点（调出站）', trigger: 'change' },
    { validator: notSameStation, trigger: 'change' },
  ],
  toStationId: [
    { required: true, message: '请选择目标站点（调入站）', trigger: 'change' },
    { validator: notSameStation, trigger: 'change' },
  ],
}

/** 明细袋码清单：扫码累加 + 粘贴批量追加，逐行一个袋码 */
const codes = ref([])
const codeInput = ref('')
const pasteText = ref('')

/** 仅做格式提示与提交前拦截；「不存在/非空闲/非源站」由后端权威判定并指名失败袋码 */
const invalidCodes = computed(() => codes.value.filter((code) => !BAG_CODE_REGEX.test(code)))

function addCode(raw) {
  const code = toUpperCase(raw || '')
  if (!code) return
  if (codes.value.includes(code)) {
    ElMessage.warning(`袋码 ${code} 已在清单中`)
    return
  }
  if (codes.value.length >= BATCH_MAX_SIZE) {
    ElMessage.warning(`单张调拨单明细不能超过 ${BATCH_MAX_SIZE} 只`)
    return
  }
  codes.value.push(code)
}

/** 扫码枪回车或摄像头识别成功：累加进清单并清空输入框，便于连续扫码 */
function onScan(code) {
  addCode(code || codeInput.value)
  codeInput.value = ''
}

function mergePaste() {
  const parsed = pasteText.value
    .split(/[\s,，;；]+/)
    .map((item) => toUpperCase(item))
    .filter(Boolean)
  if (!parsed.length) {
    ElMessage.warning('请先粘贴袋码清单')
    return
  }
  let added = 0
  let duplicated = 0
  parsed.forEach((code) => {
    if (codes.value.includes(code)) {
      duplicated += 1
      return
    }
    if (codes.value.length >= BATCH_MAX_SIZE) return
    codes.value.push(code)
    added += 1
  })
  pasteText.value = ''
  if (added) ElMessage.success(`已加入 ${added} 个袋码`)
  if (duplicated) ElMessage.warning(`已跳过 ${duplicated} 个重复袋码`)
}

function removeCode(code) {
  codes.value = codes.value.filter((item) => item !== code)
}

function clearCodes() {
  codes.value = []
}

function openCreate() {
  createForm.fromStationId = null
  createForm.toStationId = null
  codes.value = []
  codeInput.value = ''
  pasteText.value = ''
  createVisible.value = true
  nextTick(() => createFormRef.value?.clearValidate())
}

async function submitCreate() {
  const valid = await createFormRef.value?.validate().catch(() => false)
  if (!valid) return
  if (!codes.value.length) {
    ElMessage.warning('请扫码或粘贴至少 1 个明细袋码')
    return
  }
  if (invalidCodes.value.length) {
    const preview = invalidCodes.value.slice(0, 3).join('、')
    const more = invalidCodes.value.length > 3 ? ' 等' : ''
    ElMessage.error(`以下袋码格式不正确：${preview}${more}`)
    return
  }
  createSubmitting.value = true
  try {
    const data = await createAllocation({
      fromStationId: createForm.fromStationId,
      toStationId: createForm.toStationId,
      bagCodes: [...codes.value],
    })
    const no = data?.allocationNo
    ElMessage.success(
      no
        ? `调拨单 ${no} 已创建（待出库），明细袋已锁定`
        : '调拨单已创建（待出库），明细袋已锁定',
    )
    createVisible.value = false
    search()
  } catch (error) {
    reportSubmitError(error)
  } finally {
    createSubmitting.value = false
  }
}

/* ============================== 详情抽屉 ============================== */

const drawerVisible = ref(false)
const drawerLoading = ref(false)
const drawerError = ref(false)
const detail = ref(null)
const currentId = ref(null)

async function openDetail(row) {
  currentId.value = row?.id
  drawerVisible.value = true
  await loadDetail()
}

async function loadDetail() {
  if (currentId.value === null || currentId.value === undefined) return
  drawerLoading.value = true
  drawerError.value = false
  try {
    detail.value = await getAllocation(currentId.value)
  } catch {
    drawerError.value = true
    detail.value = null
  } finally {
    drawerLoading.value = false
  }
}

function onDrawerClosed() {
  detail.value = null
  currentId.value = null
}

const docStatus = computed(() => detail.value?.status || '')

const detailRows = computed(() => {
  const value = detail.value
  if (!value) return []
  if (Array.isArray(value.details)) return value.details
  if (Array.isArray(value.items)) return value.items
  return []
})

const progress = computed(() => {
  const list = detailRows.value
  const totalCount = list.length
  const received = list.filter((item) => item?.status === 'RECEIVED').length
  const dispatched = list.filter(
    (item) => item?.status === 'DISPATCHED' || item?.status === 'RECEIVED',
  ).length
  return {
    total: totalCount,
    received,
    dispatched,
    percent: totalCount ? Math.round((received / totalCount) * 100) : 0,
  }
})

/** 明细状态分布（文案取自 ALLOCATION_DETAIL_STATUS_META）：只列出现的状态 */
const detailStatusCounts = computed(() => {
  const counts = {}
  detailRows.value.forEach((item) => {
    counts[item?.status] = (counts[item?.status] || 0) + 1
  })
  return Object.entries(ALLOCATION_DETAIL_STATUS_META)
    .map(([value, meta]) => ({ value, label: meta.label, count: counts[value] || 0 }))
    .filter((item) => item.count > 0)
})

/** 在途满 48 小时未核收：抽屉顶部给 warning 提示（3.5 / FR-M3-12） */
const detailTimeout = computed(
  () =>
    detail.value?.status === 'IN_TRANSIT' &&
    (detail.value?.timeoutFlag || detail.value?.timeout || over48h(detail.value?.dispatchAt)),
)

/** 取消：仅「待出库」可整单取消（5.3） */
const canCancel = computed(() => docStatus.value === 'PENDING_DISPATCH')
/** 在途遗失确认：仅「在途」（出库满 48 小时的判定以后端为准，失败时透出中文 message） */
const canConfirmLost = computed(() => docStatus.value === 'IN_TRANSIT')
/** 异常后终止：仅「异常」单据（3.4.3） */
const canTerminate = computed(() => docStatus.value === 'ABNORMAL')

/** 剔除明细：单据在「待出库/在途」且该明细仍为「待出库」（未出库）时开放 */
function canRemoveDetail(row) {
  if (!['PENDING_DISPATCH', 'IN_TRANSIT'].includes(docStatus.value)) return false
  return row?.status === 'PENDING_DISPATCH'
}

/* ------------------------------ 取消调拨单 ------------------------------ */

const cancelVisible = ref(false)
const cancelSubmitting = ref(false)
const cancelFormRef = ref(null)
const cancelForm = reactive({ reason: '' })

const cancelRules = {
  reason: [
    { required: true, message: '请填写取消原因', trigger: 'blur' },
    { max: 255, message: '取消原因不超过 255 个字符', trigger: 'blur' },
  ],
}

function openCancel() {
  cancelForm.reason = ''
  cancelVisible.value = true
  nextTick(() => cancelFormRef.value?.clearValidate())
}

async function submitCancel() {
  const valid = await cancelFormRef.value?.validate().catch(() => false)
  if (!valid) return
  cancelSubmitting.value = true
  try {
    await cancelAllocation(currentId.value, { reason: cancelForm.reason.trim() })
    ElMessage.success('调拨单已取消，明细袋将解锁回空闲')
    cancelVisible.value = false
    await loadDetail()
    load()
  } catch (error) {
    reportSubmitError(error)
  } finally {
    cancelSubmitting.value = false
  }
}

/* ------------------------------ 剔除明细 ------------------------------ */

const removeVisible = ref(false)
const removeSubmitting = ref(false)
const removeFormRef = ref(null)
const removeForm = reactive({ bagCode: '', reason: '' })

const removeRules = {
  reason: [
    { required: true, message: '请填写剔除原因', trigger: 'blur' },
    { max: 255, message: '剔除原因不超过 255 个字符', trigger: 'blur' },
  ],
}

function openRemove(row) {
  removeForm.bagCode = row?.bagCode || ''
  removeForm.reason = ''
  removeVisible.value = true
  nextTick(() => removeFormRef.value?.clearValidate())
}

async function submitRemove() {
  const valid = await removeFormRef.value?.validate().catch(() => false)
  if (!valid) return
  removeSubmitting.value = true
  try {
    await removeAllocationDetail(currentId.value, {
      bagCode: removeForm.bagCode,
      reason: removeForm.reason.trim(),
    })
    ElMessage.success('明细袋已剔除并解锁回空闲')
    removeVisible.value = false
    await loadDetail()
    load()
  } catch (error) {
    reportSubmitError(error)
  } finally {
    removeSubmitting.value = false
  }
}

/* ------------------------------ 在途遗失确认 ------------------------------ */

const lostVisible = ref(false)
const lostSubmitting = ref(false)
const lostFormRef = ref(null)
const lostForm = reactive({ conclusion: '', responsibleStationId: null })

const lostRules = {
  conclusion: [
    { required: true, message: '请填写处置结论', trigger: 'blur' },
    { max: 255, message: '处置结论不超过 255 个字符', trigger: 'blur' },
  ],
  responsibleStationId: [{ required: true, message: '请选择责任站点', trigger: 'change' }],
}

function openLost() {
  lostForm.conclusion = ''
  // 责任主体默认为源站（3.2 #19：在途遗失责任主体=源站），可改选
  lostForm.responsibleStationId = detail.value?.fromStationId ?? null
  lostVisible.value = true
  nextTick(() => lostFormRef.value?.clearValidate())
}

async function submitLost() {
  const valid = await lostFormRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    await ElMessageBox.confirm(
      '确认执行在途遗失确认？确认后相关袋将置为「已遗失」，调拨单转为「异常」，并写入责任记录（责任主体为源站）。',
      '在途遗失确认',
      { type: 'warning', confirmButtonText: '确认遗失', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  lostSubmitting.value = true
  try {
    await confirmAllocationLost(currentId.value, {
      conclusion: lostForm.conclusion.trim(),
      responsibleStationId: lostForm.responsibleStationId,
    })
    ElMessage.success('在途遗失确认已提交，单据已转异常并登记责任记录')
    lostVisible.value = false
    await loadDetail()
    load()
  } catch (error) {
    reportSubmitError(error)
  } finally {
    lostSubmitting.value = false
  }
}

/* ------------------------------ 异常后终止 ------------------------------ */

const terminateVisible = ref(false)
const terminateSubmitting = ref(false)
const terminateFormRef = ref(null)
const terminateForm = reactive({ reason: '' })

const terminateRules = {
  reason: [
    { required: true, message: '请填写终止原因', trigger: 'blur' },
    { max: 255, message: '终止原因不超过 255 个字符', trigger: 'blur' },
  ],
}

function openTerminate() {
  terminateForm.reason = ''
  terminateVisible.value = true
  nextTick(() => terminateFormRef.value?.clearValidate())
}

async function submitTerminate() {
  const valid = await terminateFormRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    await ElMessageBox.confirm(
      '确认终止该调拨单？终止后未出库明细将解锁回空闲，已出库明细保留差异记录，操作写入审计日志。',
      '异常后终止',
      { type: 'warning', confirmButtonText: '确认终止', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  terminateSubmitting.value = true
  try {
    // 设计书 3.4.3：「异常」单据由平台管理员终止（未出库明细解锁回空闲）。
    // 数据库以 cancelled_at / cancel_reason / terminated_by 统一承载「取消或异常后终止」两类语义
    //（《数据库设计说明书》4.x），前端契约（第 3 节）未另设终止函数，故复用 cancelAllocation，
    // 由后端按单据当前状态区分「待出库=取消」「异常=终止」。
    await cancelAllocation(currentId.value, { reason: terminateForm.reason.trim() })
    ElMessage.success('调拨单已终止，未出库明细已解锁回空闲')
    terminateVisible.value = false
    await loadDetail()
    load()
  } catch (error) {
    reportSubmitError(error)
  } finally {
    terminateSubmitting.value = false
  }
}

/* ============================== 生命周期 ============================== */

onMounted(() => {
  syncLayout()
  window.addEventListener('resize', syncLayout)
  load()
  loadStations()
})

onUnmounted(() => {
  window.removeEventListener('resize', syncLayout)
})
</script>

<template>
  <div class="allocation-manage">
    <PageCard title="调拨管理" subtitle="站点间库存调拨单的创建、跟踪与异常处置">
      <template #extra>
        <el-button type="primary" @click="openCreate">创建调拨单</el-button>
      </template>

      <!-- 锁定口径常驻提示（3.4.3 / FR-M3-11） -->
      <el-alert
        class="allocation-manage__notice"
        type="warning"
        :closable="false"
        show-icon
        title="建单即锁定明细袋：被锁定的袋不能再被申领、转维修、报废或状态修正"
        description="明细袋须为源站空闲袋；如需释放，请在「待出库」时整单取消，或在单据未出库前剔除对应明细。"
      />

      <!-- 筛选：状态 / 源站 / 目标站 / 时间区间 -->
      <el-form class="allocation-manage__filter" :inline="true" @submit.prevent>
        <el-form-item label="状态">
          <el-select
            v-model="query.status"
            class="allocation-manage__filter-select"
            placeholder="全部状态"
            clearable
            @change="search"
          >
            <el-option
              v-for="item in statusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="源站">
          <el-select
            v-model="query.fromStationId"
            class="allocation-manage__filter-station"
            placeholder="全部源站"
            clearable
            filterable
            @change="search"
          >
            <el-option
              v-for="station in stationOptions"
              :key="station.id"
              :label="stationLabel(station)"
              :value="station.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标站">
          <el-select
            v-model="query.toStationId"
            class="allocation-manage__filter-station"
            placeholder="全部目标站"
            clearable
            filterable
            @change="search"
          >
            <el-option
              v-for="station in stationOptions"
              :key="station.id"
              :label="stationLabel(station)"
              :value="station.id"
            />
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
            class="allocation-manage__filter-time"
            @change="search"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 加载态：骨架屏 -->
      <PageSkeleton v-if="loading" type="table" :rows="6" />

      <!-- 错误态：给出原因与重试 -->
      <div v-else-if="errorMessage" class="allocation-manage__error">
        <el-icon class="allocation-manage__error-icon"><WarningFilled /></el-icon>
        <p class="allocation-manage__error-text">{{ errorMessage }}</p>
        <el-button type="primary" @click="load">重试</el-button>
      </div>

      <!-- 空态：一句话 + 主操作 -->
      <EmptyState
        v-else-if="!rows.length"
        :description="hasFilter ? '没有符合筛选条件的调拨单' : '暂时还没有调拨单'"
      >
        <el-button v-if="hasFilter" type="primary" @click="resetQuery">清空筛选条件</el-button>
        <el-button v-else type="primary" @click="openCreate">创建调拨单</el-button>
      </EmptyState>

      <template v-else>
        <!-- 移动端（<768px）：卡片列表，不出现横向滚动 -->
        <div v-if="isMobile" class="allocation-manage__cards">
          <article v-for="row in rows" :key="row.id" class="allocation-manage__card">
            <header class="allocation-manage__card-head">
              <span class="sems-mono allocation-manage__card-no">
                {{ row.allocationNo || `#${row.id}` }}
              </span>
              <StatusTag kind="allocation" :value="row.status" size="small" />
            </header>
            <p class="allocation-manage__card-meta">{{ stationText(row) }}</p>
            <p class="allocation-manage__card-meta sems-text-muted">
              核收进度：<span class="sems-num">{{ progressText(row) }}</span>
              · 出库：{{ formatTime(row.dispatchAt) }}
            </p>
            <footer class="allocation-manage__card-actions">
              <el-button link type="primary" @click="openDetail(row)">查看详情</el-button>
            </footer>
          </article>
        </div>

        <!-- 桌面端：表格（行高由全局样式统一 48px） -->
        <el-table v-else :data="rows" row-key="id" class="allocation-manage__table">
          <el-table-column label="调拨单号" min-width="180">
            <template #default="{ row }">
              <span class="sems-mono">{{ row.allocationNo || `#${row.id}` }}</span>
            </template>
          </el-table-column>
          <el-table-column label="调拨方向" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ stationText(row) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }">
              <div class="allocation-manage__status">
                <StatusTag kind="allocation" :value="row.status" size="small" />
                <el-tag v-if="isRowTimeout(row)" type="warning" size="small" effect="plain">
                  超时
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="核收进度" width="120" align="right">
            <template #default="{ row }">
              <span class="sems-num">{{ progressText(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="出库时间" width="150">
            <template #default="{ row }">
              <span class="sems-num">{{ formatTime(row.dispatchAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="150">
            <template #default="{ row }">
              <span class="sems-num">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>

        <DataPager
          v-if="total > 0"
          v-model:page="query.pageNum"
          v-model:size="query.pageSize"
          :total="total"
          :disabled="loading"
          @change="load"
        />
      </template>
    </PageCard>

    <!-- ============================ 创建调拨单 ============================ -->
    <el-dialog
      v-model="createVisible"
      title="创建调拨单"
      width="min(600px, 92vw)"
      :close-on-click-modal="false"
    >
      <el-alert
        class="allocation-manage__dialog-alert"
        type="warning"
        :closable="false"
        show-icon
        title="建单即锁定明细袋：被锁定的袋不能再被申领、转维修、报废或状态修正"
        description="明细袋须为源站空闲袋；提交后单据为「待出库」，由源站管理员扫码出库、目标站管理员扫码核收。"
      />
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="88px">
        <el-form-item label="源站点" prop="fromStationId">
          <el-select
            v-model="createForm.fromStationId"
            class="allocation-manage__dialog-select"
            placeholder="请选择源站点（调出站）"
            filterable
          >
            <el-option
              v-for="station in stationOptions"
              :key="station.id"
              :label="stationLabel(station)"
              :value="station.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标站点" prop="toStationId">
          <el-select
            v-model="createForm.toStationId"
            class="allocation-manage__dialog-select"
            placeholder="请选择目标站点（调入站，不能与源站点相同）"
            filterable
          >
            <el-option
              v-for="station in stationOptions"
              :key="station.id"
              :label="stationLabel(station)"
              :value="station.id"
            />
          </el-select>
        </el-form-item>
      </el-form>

      <h4 class="sems-section-title allocation-manage__codes-title">
        明细袋码清单（{{ codes.length }} / {{ BATCH_MAX_SIZE }}）
      </h4>
      <div class="allocation-manage__codes">
        <BagCodeInput v-model="codeInput" placeholder="扫码枪或摄像头录入袋码，回车累加" @scan="onScan" />
        <el-input
          v-model="pasteText"
          type="textarea"
          :rows="3"
          placeholder="支持手输批量粘贴，每行一个袋码（也支持空格/逗号分隔）"
          class="allocation-manage__codes-paste"
        />
        <div class="allocation-manage__codes-actions">
          <el-button size="small" @click="mergePaste">加入粘贴的袋码</el-button>
          <el-button size="small" :disabled="!codes.length" @click="clearCodes">清空清单</el-button>
        </div>
        <div v-if="codes.length" class="allocation-manage__codes-list">
          <el-tag
            v-for="code in codes"
            :key="code"
            class="sems-mono allocation-manage__codes-tag"
            closable
            @close="removeCode(code)"
          >
            {{ code }}
          </el-tag>
        </div>
        <p v-else class="sems-text-muted allocation-manage__codes-empty">
          尚未添加袋码：可用扫码枪/摄像头连续扫码，或粘贴袋码清单
        </p>
        <p v-if="invalidCodes.length" class="allocation-manage__codes-invalid">
          以下袋码格式不正确：{{ invalidCodes.join('、') }}
        </p>
      </div>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="submitCreate">
          创建并锁定明细袋
        </el-button>
      </template>
    </el-dialog>

    <!-- ============================ 详情抽屉 ============================ -->
    <el-drawer
      v-model="drawerVisible"
      title="调拨单详情"
      size="min(760px, 96vw)"
      @closed="onDrawerClosed"
    >
      <PageSkeleton v-if="drawerLoading" type="card" :rows="4" />

      <div v-else-if="drawerError" class="allocation-manage__error">
        <el-icon class="allocation-manage__error-icon"><WarningFilled /></el-icon>
        <p class="allocation-manage__error-text">调拨单详情加载失败</p>
        <el-button type="primary" @click="loadDetail">重试</el-button>
      </div>

      <template v-else-if="detail">
        <!-- 在途满 48 小时：warning 提示（3.5） -->
        <el-alert
          v-if="detailTimeout"
          class="allocation-manage__drawer-alert"
          type="warning"
          :closable="false"
          show-icon
          title="该调拨单在途已满 48 小时未完成核收"
          description="请核实物流情况；如确认遗失，可执行「在途遗失确认」将相关袋置为已遗失并登记责任。"
        />

        <el-descriptions :column="2" border>
          <el-descriptions-item label="调拨单号">
            <span class="sems-mono">{{ detail.allocationNo || `#${detail.id}` }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusTag kind="allocation" :value="detail.status" />
          </el-descriptions-item>
          <el-descriptions-item label="源站点">
            {{ detail.fromStationName || detail.fromStationId || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="目标站点">
            {{ detail.toStationName || detail.toStationId || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="出库时间">
            <span class="sems-num">{{ formatTime(detail.dispatchAt) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            <span class="sems-num">{{ formatTime(detail.createdAt) }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.cancelReason" label="取消 / 终止原因" :span="2">
            {{ detail.cancelReason }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="allocation-manage__progress">
          <span class="sems-text-muted">核收进度</span>
          <el-progress
            class="allocation-manage__progress-bar"
            :percentage="progress.percent"
            :stroke-width="10"
          />
          <span class="sems-text-muted sems-num">
            已出库 {{ progress.dispatched }} / 已核收 {{ progress.received }} / 共 {{ progress.total }}
          </span>
        </div>

        <!-- 明细状态分布：色 + 文案 + 数量，不靠颜色单独承载语义 -->
        <div v-if="detailStatusCounts.length" class="allocation-manage__detail-summary">
          <span
            v-for="item in detailStatusCounts"
            :key="item.value"
            class="allocation-manage__summary-item"
          >
            <StatusTag kind="allocationDetail" :value="item.value" size="small" />
            <span class="sems-num">×{{ item.count }}</span>
          </span>
        </div>

        <h4 class="sems-section-title allocation-manage__detail-title">明细逐袋</h4>

        <!-- 移动端：明细卡片列表 -->
        <div v-if="isMobile" class="allocation-manage__detail-cards">
          <article v-for="item in detailRows" :key="item.id || item.bagCode" class="allocation-manage__detail-card">
            <header class="allocation-manage__detail-card-head">
              <span class="sems-mono">{{ item.bagCode }}</span>
              <StatusTag kind="allocationDetail" :value="item.status" size="small" />
            </header>
            <p class="sems-text-muted allocation-manage__detail-card-meta">
              出库：{{ formatTime(item.dispatchedAt || item.dispatchAt) }}
              · 核收：{{ formatTime(item.receivedAt) }}
            </p>
            <el-button v-if="canRemoveDetail(item)" link type="primary" @click="openRemove(item)">
              剔除明细
            </el-button>
          </article>
        </div>

        <!-- 桌面端：明细表格 -->
        <el-table v-else :data="detailRows" size="small" class="allocation-manage__detail-table">
          <el-table-column label="袋码" min-width="190">
            <template #default="{ row }">
              <span class="sems-mono">{{ row.bagCode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="明细状态" width="110">
            <template #default="{ row }">
              <StatusTag kind="allocationDetail" :value="row.status" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="出库时间" width="150">
            <template #default="{ row }">
              <span class="sems-num">{{ formatTime(row.dispatchedAt || row.dispatchAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="核收时间" width="150">
            <template #default="{ row }">
              <span class="sems-num">{{ formatTime(row.receivedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button v-if="canRemoveDetail(row)" link type="primary" size="small" @click="openRemove(row)">
                剔除
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <EmptyState v-if="!detailRows.length" description="该调拨单没有明细袋" />
      </template>

      <template #footer>
        <div class="allocation-manage__drawer-footer">
          <el-button @click="drawerVisible = false">关闭</el-button>
          <el-button :disabled="drawerLoading" @click="loadDetail">刷新</el-button>
          <el-button v-if="canCancel" type="danger" plain @click="openCancel">取消调拨单</el-button>
          <el-button v-if="canConfirmLost" type="danger" @click="openLost">在途遗失确认</el-button>
          <el-button v-if="canTerminate" type="danger" @click="openTerminate">终止调拨单</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- ============================ 取消调拨单（仅待出库） ============================ -->
    <el-dialog
      v-model="cancelVisible"
      title="取消调拨单"
      width="min(480px, 92vw)"
      :close-on-click-modal="false"
    >
      <el-alert
        class="allocation-manage__dialog-alert"
        type="info"
        :closable="false"
        show-icon
        title="取消后明细袋将解锁回空闲，可被重新申领"
        description="仅「待出库」状态的调拨单可整单取消；已出库单据请走核收或异常处置流程。"
      />
      <el-form ref="cancelFormRef" :model="cancelForm" :rules="cancelRules" label-width="88px">
        <el-form-item label="取消原因" prop="reason">
          <el-input
            v-model="cancelForm.reason"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            placeholder="请说明取消原因（写入单据与审计日志）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cancelVisible = false">再想想</el-button>
        <el-button type="danger" :loading="cancelSubmitting" @click="submitCancel">
          确认取消
        </el-button>
      </template>
    </el-dialog>

    <!-- ============================ 剔除明细 ============================ -->
    <el-dialog
      v-model="removeVisible"
      title="剔除明细袋"
      width="min(480px, 92vw)"
      :close-on-click-modal="false"
    >
      <el-alert
        class="allocation-manage__dialog-alert"
        type="warning"
        :closable="false"
        show-icon
        title="剔除仅对仍空闲的袋生效；袋已非空闲时须先终止调拨单"
        description="剔除后该袋解锁回空闲，其余明细继续出库。"
      />
      <p class="allocation-manage__dialog-desc">
        袋码：<span class="sems-mono">{{ removeForm.bagCode }}</span>
      </p>
      <el-form ref="removeFormRef" :model="removeForm" :rules="removeRules" label-width="88px">
        <el-form-item label="剔除原因" prop="reason">
          <el-input
            v-model="removeForm.reason"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            placeholder="如：实物缺失 / 袋码破损无法出库"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="removeVisible = false">取消</el-button>
        <el-button type="primary" :loading="removeSubmitting" @click="submitRemove">
          确认剔除
        </el-button>
      </template>
    </el-dialog>

    <!-- ============================ 在途遗失确认（二次确认） ============================ -->
    <el-dialog
      v-model="lostVisible"
      title="在途遗失确认"
      width="min(520px, 92vw)"
      :close-on-click-modal="false"
    >
      <el-alert
        class="allocation-manage__dialog-alert"
        type="warning"
        :closable="false"
        show-icon
        title="确认后相关袋将置为「已遗失」，调拨单转「异常」，并写入责任记录"
        description="责任主体默认为源站（可在此调整），处置结论写入责任记录留档。"
      />
      <el-form ref="lostFormRef" :model="lostForm" :rules="lostRules" label-width="88px">
        <el-form-item label="处置结论" prop="conclusion">
          <el-input
            v-model="lostForm.conclusion"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            placeholder="如：运输途中丢失，已与承运方确认"
          />
        </el-form-item>
        <el-form-item label="责任站点" prop="responsibleStationId">
          <el-select
            v-model="lostForm.responsibleStationId"
            class="allocation-manage__dialog-select"
            placeholder="默认源站"
            filterable
          >
            <el-option
              v-for="station in stationOptions"
              :key="station.id"
              :label="stationLabel(station)"
              :value="station.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lostVisible = false">取消</el-button>
        <el-button type="danger" :loading="lostSubmitting" @click="submitLost">
          确认遗失
        </el-button>
      </template>
    </el-dialog>

    <!-- ============================ 异常后终止（二次确认） ============================ -->
    <el-dialog
      v-model="terminateVisible"
      title="终止调拨单"
      width="min(480px, 92vw)"
      :close-on-click-modal="false"
    >
      <el-alert
        class="allocation-manage__dialog-alert"
        type="warning"
        :closable="false"
        show-icon
        title="终止后未出库明细将解锁回空闲，已出库明细保留差异记录"
        description="仅「异常」状态的调拨单可终止；终止原因与操作人写入单据与审计日志。"
      />
      <el-form
        ref="terminateFormRef"
        :model="terminateForm"
        :rules="terminateRules"
        label-width="88px"
      >
        <el-form-item label="终止原因" prop="reason">
          <el-input
            v-model="terminateForm.reason"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            placeholder="请说明终止原因（写入单据与审计日志）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="terminateVisible = false">再想想</el-button>
        <el-button type="danger" :loading="terminateSubmitting" @click="submitTerminate">
          确认终止
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.allocation-manage__notice {
  margin-bottom: var(--sems-space-4);
}
.allocation-manage__filter {
  margin-bottom: var(--sems-space-2);
}
.allocation-manage__filter-select {
  width: 140px;
}
.allocation-manage__filter-station {
  width: 170px;
}
.allocation-manage__filter-time {
  width: 360px;
}

/* 三态：错误态 */
.allocation-manage__error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-3);
  padding: var(--sems-space-6) 0;
}
.allocation-manage__error-icon {
  font-size: var(--sems-font-size-kpi);
  color: var(--sems-color-warning);
}
.allocation-manage__error-text {
  margin: 0;
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-base);
}

/* 表格内的状态 + 超时标记 */
.allocation-manage__status {
  display: flex;
  align-items: center;
  gap: var(--sems-space-1);
}

/* 移动端卡片列表 */
.allocation-manage__cards {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
}
.allocation-manage__card {
  padding: var(--sems-space-4);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-card);
  background-color: var(--sems-bg-card);
}
.allocation-manage__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-2);
}
.allocation-manage__card-no {
  font-size: var(--sems-font-size-base);
  font-weight: var(--sems-font-weight-medium);
  color: var(--sems-text-primary);
  word-break: break-all;
}
.allocation-manage__card-meta {
  margin: 0 0 var(--sems-space-1);
  color: var(--sems-text-regular);
  font-size: var(--sems-font-size-sm);
}
.allocation-manage__card-actions {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  margin-top: var(--sems-space-2);
  padding-top: var(--sems-space-2);
  border-top: 1px solid var(--sems-border);
}

/* 对话框 */
.allocation-manage__dialog-alert {
  margin-bottom: var(--sems-space-4);
}
.allocation-manage__dialog-desc {
  margin: 0 0 var(--sems-space-3);
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-base);
}
.allocation-manage__dialog-select {
  width: 100%;
}

/* 创建对话框的袋码清单 */
.allocation-manage__codes-title {
  margin: var(--sems-space-2) 0 var(--sems-space-3);
}
.allocation-manage__codes {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
}
.allocation-manage__codes-actions {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
}
.allocation-manage__codes-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sems-space-1);
  max-height: 140px;
  overflow-y: auto;
}
.allocation-manage__codes-tag {
  max-width: 100%;
}
.allocation-manage__codes-empty {
  margin: 0;
}
.allocation-manage__codes-invalid {
  margin: 0;
  color: var(--sems-color-danger);
  font-size: var(--sems-font-size-sm);
}

/* 抽屉 */
.allocation-manage__drawer-alert {
  margin-bottom: var(--sems-space-4);
}
.allocation-manage__progress {
  display: flex;
  align-items: center;
  gap: var(--sems-space-3);
  margin: var(--sems-space-4) 0;
  flex-wrap: wrap;
}
.allocation-manage__progress-bar {
  flex: 1;
  min-width: 160px;
}
.allocation-manage__detail-title {
  margin: 0 0 var(--sems-space-3);
}
.allocation-manage__detail-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sems-space-2) var(--sems-space-4);
  margin-bottom: var(--sems-space-4);
}
.allocation-manage__summary-item {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-1);
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-sm);
}
.allocation-manage__detail-cards {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
}
.allocation-manage__detail-card {
  padding: var(--sems-space-3);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-base);
  background-color: var(--sems-bg-card);
}
.allocation-manage__detail-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-1);
}
.allocation-manage__detail-card-meta {
  margin: 0 0 var(--sems-space-1);
}
.allocation-manage__drawer-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: var(--sems-space-2);
}

@media (max-width: 767px) {
  .allocation-manage__filter-select,
  .allocation-manage__filter-station,
  .allocation-manage__filter-time {
    width: 100%;
  }
}
</style>
