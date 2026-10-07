<script setup>
/**
 * 平台端 · 快递袋台账（见《系统设计说明书》4.4「平台端」、5.4「快递袋管理」，FR-M4-01/03/05/15/16）。
 *
 * 覆盖功能：
 *  1) 全站袋分页查询：状态（8 态）/ 站点 / 编码关键字筛选；
 *  2) 新建袋档案（初始状态=空闲，写登记站点归属）；提示「建档后自动计入登记站点可用库存」；
 *  3) 批量生成（≤500 只/批，整批原子）：响应是 ZIP blob，由本页触发下载并解析文件名；
 *  4) 行内入口：查看详情（跳详情页）、查看二维码、状态修正（#21，审计重点核查项）、遗失找回（#20，仅已遗失）。
 *
 * 接口一律调用 src/api/*.js 已封装函数（契约 3 节），页面不拼路径、不写死错误文案。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  BAG_STATUS,
  BAG_STATUS_META,
  BATCH_MAX_SIZE,
  DEFAULT_PAGE_SIZE,
  STATION_STATUS_META,
} from '@/constants'
import {
  batchGenerate,
  createBag,
  getBagQrcode,
  listBags,
  lostRecovery,
  statusCorrect,
} from '@/api/bag'
import { listStations } from '@/api/station'
import { toUpperCase } from '@/utils/validators'
import StatusTag from '@/components/StatusTag.vue'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DataPager from '@/components/DataPager.vue'

const router = useRouter()

/* ============================== 列表查询 ============================== */

/** 分页查询条件：与 GET /bags 入参一致（pageNum / pageSize / status / stationId / bagCode） */
const query = reactive({
  pageNum: 1,
  pageSize: DEFAULT_PAGE_SIZE,
  status: '',
  stationId: null,
  keyword: '',
})

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')

const hasFilter = computed(() => !!(query.status || query.stationId || query.keyword.trim()))

/** 状态下拉：11 项全部取自 BAG_STATUS_META，页面不另抄文案 */
const statusOptions = computed(() =>
  Object.entries(BAG_STATUS_META).map(([value, meta]) => ({ value, label: meta.label })),
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
    // 站点下拉加载失败不阻断台账主流程；request.js 已统一提示
    stationOptions.value = []
  }
}

/** 站点选项文案：停用站追加状态标注，避免误选 */
function stationLabel(station) {
  const suffix = station?.status === 'DISABLED' ? `（${STATION_STATUS_META.DISABLED.label}）` : ''
  return `${station?.name || `站点 ID ${station?.id ?? ''}`}${suffix}`
}

/* ------------------------------ 加载与筛选 ------------------------------ */

function buildQuery() {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.status) params.status = query.status
  if (query.stationId) params.stationId = query.stationId
  // 后端 BagQuery 的检索字段名是 bagCode（设计书 5.4「按状态/站点/编码筛选」）；
  // 传 keyword 会被 Spring 静默忽略、筛选失效，界面上的「编码关键字」对应袋码包含匹配
  if (query.keyword.trim()) params.bagCode = query.keyword.trim()
  return params
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await listBags(buildQuery())
    rows.value = data?.records || []
    total.value = data?.total || 0
  } catch (error) {
    // 失败时 request.js 已统一提示；页面负责错误态与重试入口
    errorMessage.value = error?.message || '袋台账加载失败'
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
  query.stationId = null
  query.keyword = ''
  search()
}

/* ============================== 展示辅助 ============================== */

function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

function specText(row) {
  const spec = row?.spec || '—'
  const color = row?.color || '—'
  const capacity = row?.capacity || '—'
  return `${spec} · ${color} · ${capacity}`
}

/** 状态修正（#21）：任意非终态可修正；报废为终态，任何转移都会被拒（4102） */
function canCorrect(row) {
  return row?.status !== BAG_STATUS.SCRAPPED
}

function goDetail(row) {
  router.push(`/platform/bags/${row.bagCode}`)
}

function handleRowCommand(command, row) {
  if (command === 'qrcode') openQrcode(row)
  else if (command === 'correct') openCorrect(row)
  else if (command === 'recovery') openRecovery(row)
}

/* ============================== 对话框：提交错误提示 ============================== */

/** 3001 的字段级明细由表单侧展示（request.js 对 3001 不弹提示，见 6.6）；其余错误码已由 request.js 提示 */
function reportSubmitError(error) {
  if (error?.code !== 3001) return
  // 后端 3001 的 data 直接就是「字段 → 消息」映射（GlobalExceptionHandler 以 fieldErrors 作为 data），
  // 兼容带 fieldErrors 包装的形态，否则明细取不到、只剩通用文案
  const raw = error.data?.fieldErrors || error.data
  const fieldErrors = raw && typeof raw === 'object' ? raw : {}
  const first = Object.values(fieldErrors)[0]
  const tip = Array.isArray(first) ? first[0] : first
  ElMessage.warning(tip || error.message || '提交内容有误，请检查后重试')
}

/* ============================== 新建袋档案 ============================== */

const createVisible = ref(false)
const createSubmitting = ref(false)
const createFormRef = ref(null)
const createForm = reactive({ spec: '', color: '', capacity: '', stationId: null })

const createRules = {
  spec: [
    { required: true, message: '请输入规格码', trigger: 'blur' },
    { pattern: /^[A-Z]$/, message: '规格为 1 位字母码，如 L / M / S', trigger: 'blur' },
  ],
  color: [
    { required: true, message: '请输入颜色', trigger: 'blur' },
    { max: 20, message: '颜色不超过 20 个字符', trigger: 'blur' },
  ],
  capacity: [
    { required: true, message: '请输入容量', trigger: 'blur' },
    { max: 50, message: '容量不超过 50 个字符', trigger: 'blur' },
  ],
  stationId: [{ required: true, message: '请选择登记站点', trigger: 'change' }],
}

function openCreate() {
  createForm.spec = ''
  createForm.color = ''
  createForm.capacity = ''
  createForm.stationId = null
  createVisible.value = true
  nextTick(() => createFormRef.value?.clearValidate())
}

async function submitCreate() {
  const valid = await createFormRef.value?.validate().catch(() => false)
  if (!valid) return
  createSubmitting.value = true
  try {
    const data = await createBag({
      spec: createForm.spec.trim().toUpperCase(),
      color: createForm.color.trim(),
      capacity: createForm.capacity.trim(),
      stationId: createForm.stationId,
    })
    const code = data?.bagCode
    ElMessage.success(code ? `袋档案已创建：${code}（空闲）` : '袋档案已创建（空闲）')
    createVisible.value = false
    search()
  } catch (error) {
    reportSubmitError(error)
  } finally {
    createSubmitting.value = false
  }
}

/* ============================== 批量生成（ZIP 下载） ============================== */

const batchVisible = ref(false)
const batchSubmitting = ref(false)
const batchFormRef = ref(null)
const batchForm = reactive({ count: 100, spec: '', color: '', capacity: '', stationId: null })

const batchRules = {
  count: [
    { required: true, message: '请输入生成数量', trigger: 'change' },
    {
      validator: (_rule, value, callback) => {
        const num = Number(value)
        if (!Number.isInteger(num) || num < 1) {
          return callback(new Error('数量须为正整数'))
        }
        if (num > BATCH_MAX_SIZE) {
          return callback(new Error(`单批最多 ${BATCH_MAX_SIZE} 只`))
        }
        return callback()
      },
      trigger: 'change',
    },
  ],
  spec: createRules.spec,
  color: createRules.color,
  capacity: createRules.capacity,
  stationId: [{ required: true, message: '请选择登记站点', trigger: 'change' }],
}

function openBatch() {
  batchForm.count = 100
  batchForm.spec = ''
  batchForm.color = ''
  batchForm.capacity = ''
  batchForm.stationId = null
  batchVisible.value = true
  nextTick(() => batchFormRef.value?.clearValidate())
}

/** 从 Content-Disposition 解析文件名（中文名一般在 RFC 5987 的 filename*=UTF-8'' 里） */
function parseFilename(disposition) {
  if (!disposition) return ''
  const utf8Match = /filename\*=(?:UTF-8'')?([^;]+)/i.exec(disposition)
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1].replace(/^"|"$/g, '').trim())
    } catch {
      return utf8Match[1].trim()
    }
  }
  const plainMatch = /filename="?([^";]+)"?/i.exec(disposition)
  if (!plainMatch?.[1]) return ''
  try {
    return decodeURIComponent(plainMatch[1].trim())
  } catch {
    return plainMatch[1].trim()
  }
}

/** 默认下载名：二维码_yyyyMMdd.zip（与后端命名形态一致） */
function defaultZipName() {
  const now = new Date()
  const pad = (value) => String(value).padStart(2, '0')
  return `二维码_${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}.zip`
}

/**
 * 批量生成走 blob 下载（契约 6 节声明的唯一例外）。
 * 后端若以 JSON 返回业务错误（blob 里其实是 JSON 文本），这里解析出来抛出，
 * 避免用户下载到一个装着错误信息的「ZIP」。
 */
async function resolveZipBlob(res) {
  const blob = res instanceof Blob ? res : res?.data
  if (!(blob instanceof Blob)) throw new Error('批量生成响应异常，请稍后重试')
  const isJson = (blob.type || '').includes('application/json')
  if (isJson) {
    let message = '批量生成失败，请稍后重试'
    try {
      const body = JSON.parse(await blob.text())
      if (body?.message) message = body.message
    } catch {
      // 解析失败按默认文案处理
    }
    const error = new Error(message)
    error.isBlobError = true
    throw error
  }
  return blob
}

function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

async function submitBatch() {
  const valid = await batchFormRef.value?.validate().catch(() => false)
  if (!valid) return
  batchSubmitting.value = true
  try {
    const res = await batchGenerate({
      count: Number(batchForm.count),
      spec: batchForm.spec.trim().toUpperCase(),
      color: batchForm.color.trim(),
      capacity: batchForm.capacity.trim(),
      stationId: batchForm.stationId,
    })
    const blob = await resolveZipBlob(res)
    const disposition = res?.headers?.['content-disposition'] || ''
    saveBlob(blob, parseFilename(disposition) || defaultZipName())
    ElMessage.success('整批原子生成，请下载打印贴标')
    batchVisible.value = false
    search()
  } catch (error) {
    if (error?.isBlobError) ElMessage.error(error.message)
    else reportSubmitError(error)
  } finally {
    batchSubmitting.value = false
  }
}

/* ============================== 查看二维码 ============================== */

const qrVisible = ref(false)
const qrBagCode = ref('')
const qrUrl = ref('')
const qrBlob = ref(null)
const qrLoading = ref(false)
const qrError = ref(false)

function releaseQrUrl() {
  if (qrUrl.value) {
    URL.revokeObjectURL(qrUrl.value)
    qrUrl.value = ''
  }
  qrBlob.value = null
}

async function fetchQrcode(code) {
  qrLoading.value = true
  qrError.value = false
  try {
    const res = await getBagQrcode(code)
    const blob = res instanceof Blob ? res : res?.data
    if (!(blob instanceof Blob)) throw new Error('二维码数据异常')
    releaseQrUrl()
    qrBlob.value = blob
    qrUrl.value = URL.createObjectURL(blob)
  } catch {
    qrError.value = true
  } finally {
    qrLoading.value = false
  }
}

function openQrcode(row) {
  qrBagCode.value = row.bagCode
  qrVisible.value = true
  fetchQrcode(row.bagCode)
}

function onQrClosed() {
  releaseQrUrl()
  qrBagCode.value = ''
}

function downloadQrcode() {
  if (!qrBlob.value) return
  saveBlob(qrBlob.value, `${qrBagCode.value}_二维码.png`)
}

onBeforeUnmount(releaseQrUrl)

/* ============================== 状态修正（#21，审计重点核查项） ============================== */

/** 目标态白名单（3.2 #21）：只允许这四态，不允许页面自行扩大 */
const CORRECT_TARGET_OPTIONS = [
  { value: BAG_STATUS.IDLE, label: BAG_STATUS_META.IDLE.label },
  { value: BAG_STATUS.REPAIRING, label: BAG_STATUS_META.REPAIRING.label },
  { value: BAG_STATUS.LOST, label: BAG_STATUS_META.LOST.label },
  { value: BAG_STATUS.SCRAPPED, label: BAG_STATUS_META.SCRAPPED.label },
]

const correctVisible = ref(false)
const correctSubmitting = ref(false)
const correctFormRef = ref(null)
const correctForm = reactive({
  bagCode: '',
  reason: '',
  targetStatus: '',
  crossing: false,
  targetStationId: null,
})

const correctRules = computed(() => ({
  reason: [
    { required: true, message: '请输入修正原因', trigger: 'blur' },
    { max: 255, message: '修正原因不超过 255 个字符', trigger: 'blur' },
  ],
  targetStatus: [{ required: true, message: '请选择目标状态', trigger: 'change' }],
  targetStationId: correctForm.crossing
    ? [{ required: true, message: '跨站修正必须选择目标站点', trigger: 'change' }]
    : [],
}))

function openCorrect(row) {
  correctForm.bagCode = row.bagCode
  correctForm.reason = ''
  correctForm.targetStatus = ''
  correctForm.crossing = false
  correctForm.targetStationId = null
  correctVisible.value = true
  nextTick(() => correctFormRef.value?.clearValidate())
}

async function submitCorrect() {
  const valid = await correctFormRef.value?.validate().catch(() => false)
  if (!valid) return
  const targetLabel =
    BAG_STATUS_META[correctForm.targetStatus]?.label || correctForm.targetStatus
  try {
    await ElMessageBox.confirm(
      `确认将袋 ${correctForm.bagCode} 的状态修正为「${targetLabel}」？状态修正为审计重点核查项，将写入流转日志与审计日志。`,
      '状态修正确认',
      { type: 'warning', confirmButtonText: '确认修正', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  correctSubmitting.value = true
  try {
    const payload = {
      remark: correctForm.reason.trim(),
      targetStatus: correctForm.targetStatus,
    }
    // 仅跨站修正时才带目标站点（同站修正不得改归属）
    if (correctForm.crossing) payload.targetStationId = correctForm.targetStationId
    await statusCorrect(correctForm.bagCode, payload)
    ElMessage.success('状态修正已提交，流转日志与审计日志已记录')
    correctVisible.value = false
    search()
  } catch (error) {
    reportSubmitError(error)
  } finally {
    correctSubmitting.value = false
  }
}

/* ============================== 遗失找回（#20） ============================== */

const recoveryVisible = ref(false)
const recoverySubmitting = ref(false)
const recoveryFormRef = ref(null)
const recoveryForm = reactive({ bagCode: '', remark: '', stationId: null })

const recoveryRules = {
  remark: [
    { required: true, message: '请输入找回说明', trigger: 'blur' },
    { max: 255, message: '找回说明不超过 255 个字符', trigger: 'blur' },
  ],
  stationId: [{ required: true, message: '请选择找回站点', trigger: 'change' }],
}

function openRecovery(row) {
  recoveryForm.bagCode = row.bagCode
  recoveryForm.remark = ''
  recoveryForm.stationId = null
  recoveryVisible.value = true
  nextTick(() => recoveryFormRef.value?.clearValidate())
}

async function submitRecovery() {
  const valid = await recoveryFormRef.value?.validate().catch(() => false)
  if (!valid) return
  recoverySubmitting.value = true
  try {
    await lostRecovery(recoveryForm.bagCode, {
      remark: recoveryForm.remark.trim(),
      stationId: recoveryForm.stationId,
    })
    ElMessage.success('找回已登记，该袋转入待消毒，合格回流后可复用')
    recoveryVisible.value = false
    search()
  } catch (error) {
    reportSubmitError(error)
  } finally {
    recoverySubmitting.value = false
  }
}

/* ============================== 生命周期 ============================== */

onMounted(() => {
  syncLayout()
  window.addEventListener('resize', syncLayout)
  load()
  loadStations()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncLayout)
})
</script>

<template>
  <div class="bag-ledger">
    <PageCard title="快递袋台账" subtitle="全站袋档案；新建与批量生成产出的是「空闲」档案，建档后自动计入登记站点可用库存">
      <template #extra>
        <el-button @click="openCreate">新建袋档案</el-button>
        <el-button type="primary" @click="openBatch">批量生成</el-button>
      </template>

      <!-- 筛选：状态（8 态）/ 站点 / 编码关键字 -->
      <el-form class="bag-ledger__filter" :inline="true" @submit.prevent>
        <el-form-item label="状态">
          <el-select
            v-model="query.status"
            class="bag-ledger__filter-select"
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
        <el-form-item label="站点">
          <el-select
            v-model="query.stationId"
            class="bag-ledger__filter-station"
            placeholder="全部站点"
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
        <el-form-item label="编码">
          <el-input
            v-model="query.keyword"
            class="bag-ledger__filter-input"
            placeholder="袋码关键字，如 SJB-L-2609"
            clearable
            maxlength="40"
            @keyup.enter="search"
            @clear="search"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 加载态：骨架屏（占位与真实表格行高一致） -->
      <PageSkeleton v-if="loading" type="table" :rows="6" />

      <!-- 错误态：给出原因与重试 -->
      <div v-else-if="errorMessage" class="bag-ledger__error">
        <el-icon class="bag-ledger__error-icon"><WarningFilled /></el-icon>
        <p class="bag-ledger__error-text">{{ errorMessage }}</p>
        <el-button type="primary" @click="load">重试</el-button>
      </div>

      <!-- 空态：一句话 + 主操作 -->
      <EmptyState
        v-else-if="!rows.length"
        :description="hasFilter ? '没有符合筛选条件的袋档案' : '暂时还没有袋档案，先新建一批吧'"
      >
        <el-button v-if="hasFilter" type="primary" @click="resetQuery">清空筛选条件</el-button>
        <el-button v-else type="primary" @click="openCreate">新建袋档案</el-button>
      </EmptyState>

      <template v-else>
        <!-- 移动端（<768px）：卡片列表，不出现横向滚动 -->
        <div v-if="isMobile" class="bag-ledger__cards">
          <article v-for="row in rows" :key="row.id || row.bagCode" class="bag-ledger__card">
            <header class="bag-ledger__card-head">
              <span class="sems-mono bag-ledger__card-code">{{ row.bagCode }}</span>
              <StatusTag kind="bag" :value="row.status" size="small" />
            </header>
            <p class="bag-ledger__card-meta">{{ specText(row) }}</p>
            <p class="bag-ledger__card-meta sems-text-muted">
              所在站点：{{ row.stationName || row.stationId || '—' }}
            </p>
            <p class="bag-ledger__card-meta sems-text-muted">
              复用次数：<span class="sems-num">{{ row.useCount ?? 0 }} / {{ row.maxUseCount ?? '—' }}</span>
              · 创建：{{ formatTime(row.createdAt) }}
            </p>
            <footer class="bag-ledger__card-actions">
              <el-button link type="primary" @click="goDetail(row)">查看详情</el-button>
              <el-button v-if="canCorrect(row)" link type="primary" @click="openCorrect(row)">
                状态修正
              </el-button>
              <el-button
                v-if="row.status === BAG_STATUS.LOST"
                link
                type="primary"
                @click="openRecovery(row)"
              >
                遗失找回
              </el-button>
            </footer>
          </article>
        </div>

        <!-- 桌面端：表格（行高由全局样式统一 48px）；行内 2 个操作 -->
        <el-table v-else :data="rows" row-key="bagCode" class="bag-ledger__table">
          <el-table-column label="袋码" min-width="200">
            <template #default="{ row }">
              <span class="sems-mono">{{ row.bagCode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="规格 / 颜色 / 容量" min-width="170">
            <template #default="{ row }">{{ specText(row) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <StatusTag kind="bag" :value="row.status" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="所在站点" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.stationName || row.stationId || '—' }}</template>
          </el-table-column>
          <el-table-column label="复用次数 / 上限" width="140" align="right">
            <template #default="{ row }">
              <span class="sems-num">{{ row.useCount ?? 0 }} / {{ row.maxUseCount ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="150">
            <template #default="{ row }">
              <span class="sems-num">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <div class="bag-ledger__row-actions">
                <el-button link type="primary" size="small" @click="goDetail(row)">查看详情</el-button>
                <el-dropdown trigger="click" @command="(command) => handleRowCommand(command, row)">
                  <el-button link type="primary" size="small">
                    更多<el-icon><ArrowDown /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="qrcode">查看二维码</el-dropdown-item>
                      <el-dropdown-item v-if="canCorrect(row)" command="correct">
                        状态修正
                      </el-dropdown-item>
                      <el-dropdown-item v-if="row.status === BAG_STATUS.LOST" command="recovery">
                        遗失找回
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
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

    <!-- ============================ 新建袋档案 ============================ -->
    <el-dialog
      v-model="createVisible"
      title="新建袋档案"
      width="min(520px, 92vw)"
      :close-on-click-modal="false"
    >
      <el-alert
        class="bag-ledger__dialog-alert"
        type="info"
        :closable="false"
        show-icon
        title="新建后状态为空闲，建档后自动计入登记站点可用库存"
        description="袋码由系统自动生成，初始归属写入所选登记站点。"
      />
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="88px">
        <el-form-item label="规格" prop="spec">
          <el-input
            v-model="createForm.spec"
            maxlength="1"
            placeholder="1 位码，如 L / M / S"
            @input="createForm.spec = toUpperCase(createForm.spec).slice(0, 1)"
          />
        </el-form-item>
        <el-form-item label="颜色" prop="color">
          <el-input v-model="createForm.color" maxlength="20" placeholder="如 蓝色 / 白色" />
        </el-form-item>
        <el-form-item label="容量" prop="capacity">
          <el-input v-model="createForm.capacity" maxlength="50" placeholder="如 30cm×40cm" />
        </el-form-item>
        <el-form-item label="登记站点" prop="stationId">
          <el-select
            v-model="createForm.stationId"
            class="bag-ledger__dialog-select"
            placeholder="请选择登记站点"
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
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- ============================ 批量生成 ============================ -->
    <el-dialog
      v-model="batchVisible"
      title="批量生成袋档案"
      width="min(520px, 92vw)"
      :close-on-click-modal="false"
    >
      <el-alert
        class="bag-ledger__dialog-alert"
        type="info"
        :closable="false"
        show-icon
        :title="`单批最多 ${BATCH_MAX_SIZE} 只，整批原子生成（任一只失败则整批不落库）`"
        description="提交成功后自动下载二维码 PNG 压缩包，用于打印贴标；生成后状态为空闲。"
      />
      <el-form ref="batchFormRef" :model="batchForm" :rules="batchRules" label-width="88px">
        <el-form-item label="数量" prop="count">
          <el-input-number
            v-model="batchForm.count"
            :min="1"
            :max="BATCH_MAX_SIZE"
            :step="10"
            :precision="0"
            controls-position="right"
          />
          <span class="bag-ledger__field-tip">只/批（≤{{ BATCH_MAX_SIZE }}）</span>
        </el-form-item>
        <el-form-item label="规格" prop="spec">
          <el-input
            v-model="batchForm.spec"
            maxlength="1"
            placeholder="1 位码，如 L / M / S"
            @input="batchForm.spec = toUpperCase(batchForm.spec).slice(0, 1)"
          />
        </el-form-item>
        <el-form-item label="颜色" prop="color">
          <el-input v-model="batchForm.color" maxlength="20" placeholder="如 蓝色 / 白色" />
        </el-form-item>
        <el-form-item label="容量" prop="capacity">
          <el-input v-model="batchForm.capacity" maxlength="50" placeholder="如 30cm×40cm" />
        </el-form-item>
        <el-form-item label="登记站点" prop="stationId">
          <el-select
            v-model="batchForm.stationId"
            class="bag-ledger__dialog-select"
            placeholder="请选择登记站点"
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
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchSubmitting" @click="submitBatch">
          生成并下载
        </el-button>
      </template>
    </el-dialog>

    <!-- ============================ 查看二维码 ============================ -->
    <el-dialog
      v-model="qrVisible"
      title="快递袋二维码"
      width="min(360px, 92vw)"
      align-center
      @closed="onQrClosed"
    >
      <div v-loading="qrLoading" class="bag-ledger__qr">
        <img v-if="qrUrl" class="bag-ledger__qr-img" :src="qrUrl" alt="快递袋二维码" />
        <div v-else class="bag-ledger__qr-placeholder">
          <template v-if="qrError">
            <p class="bag-ledger__qr-text sems-text-muted">二维码加载失败</p>
            <el-button size="small" @click="fetchQrcode(qrBagCode)">重试</el-button>
          </template>
          <p v-else class="bag-ledger__qr-text sems-text-muted">
            {{ qrLoading ? '二维码加载中…' : '二维码待加载' }}
          </p>
        </div>
      </div>
      <p class="bag-ledger__qr-code sems-mono">{{ qrBagCode }}</p>
      <p class="bag-ledger__qr-hint sems-text-muted">二维码内容指向该袋的用户端详情页，可用于标签补打与人工核对</p>
      <template #footer>
        <el-button @click="qrVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!qrBlob" @click="downloadQrcode">保存图片</el-button>
      </template>
    </el-dialog>

    <!-- ============================ 状态修正（审计重点核查项） ============================ -->
    <el-dialog
      v-model="correctVisible"
      title="状态修正"
      width="min(520px, 92vw)"
      :close-on-click-modal="false"
    >
      <el-alert
        class="bag-ledger__dialog-alert"
        type="warning"
        :closable="false"
        show-icon
        title="状态修正为审计重点核查项，将写入流转日志与审计日志"
        description="仅盘点差异等场景可用；目标状态限白名单，跨站修正须指定目标站点。"
      />
      <p class="bag-ledger__dialog-desc">
        袋码：<span class="sems-mono">{{ correctForm.bagCode }}</span>
      </p>
      <el-form
        ref="correctFormRef"
        :model="correctForm"
        :rules="correctRules"
        label-width="100px"
      >
        <el-form-item label="修正原因" prop="reason">
          <el-input
            v-model="correctForm.reason"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            placeholder="如：盘点发现实物缺失、袋体破损与实物不符"
          />
        </el-form-item>
        <el-form-item label="目标状态" prop="targetStatus">
          <el-select
            v-model="correctForm.targetStatus"
            class="bag-ledger__dialog-select"
            placeholder="请选择目标状态（白名单）"
          >
            <el-option
              v-for="item in CORRECT_TARGET_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="跨站修正">
          <el-checkbox v-model="correctForm.crossing">修正同时变更归属站点</el-checkbox>
        </el-form-item>
        <el-form-item v-if="correctForm.crossing" label="目标站点" prop="targetStationId">
          <el-select
            v-model="correctForm.targetStationId"
            class="bag-ledger__dialog-select"
            placeholder="请选择修正后的归属站点"
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
        <el-button @click="correctVisible = false">取消</el-button>
        <el-button type="danger" :loading="correctSubmitting" @click="submitCorrect">
          提交修正
        </el-button>
      </template>
    </el-dialog>

    <!-- ============================ 遗失找回 ============================ -->
    <el-dialog
      v-model="recoveryVisible"
      title="遗失找回"
      width="min(520px, 92vw)"
      :close-on-click-modal="false"
    >
      <el-alert
        class="bag-ledger__dialog-alert"
        type="info"
        :closable="false"
        show-icon
        title="找回后该袋转入待消毒，消毒合格后回流复用，不重复计复用次数"
      />
      <p class="bag-ledger__dialog-desc">
        袋码：<span class="sems-mono">{{ recoveryForm.bagCode }}</span>
      </p>
      <el-form
        ref="recoveryFormRef"
        :model="recoveryForm"
        :rules="recoveryRules"
        label-width="100px"
      >
        <el-form-item label="找回说明" prop="remark">
          <el-input
            v-model="recoveryForm.remark"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            placeholder="如：在 XX 站点角落找回，外观完好"
          />
        </el-form-item>
        <el-form-item label="找回站点" prop="stationId">
          <el-select
            v-model="recoveryForm.stationId"
            class="bag-ledger__dialog-select"
            placeholder="请选择找回站点（归属将改为该站）"
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
        <el-button @click="recoveryVisible = false">取消</el-button>
        <el-button type="primary" :loading="recoverySubmitting" @click="submitRecovery">
          确认找回
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bag-ledger__filter {
  margin-bottom: var(--sems-space-2);
}
.bag-ledger__filter-select {
  width: 140px;
}
.bag-ledger__filter-station {
  width: 200px;
}
.bag-ledger__filter-input {
  width: 220px;
}

/* 三态：错误态 */
.bag-ledger__error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-3);
  padding: var(--sems-space-6) 0;
}
.bag-ledger__error-icon {
  font-size: var(--sems-font-size-kpi);
  color: var(--sems-color-warning);
}
.bag-ledger__error-text {
  margin: 0;
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-base);
}

/* 移动端卡片列表 */
.bag-ledger__cards {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
}
.bag-ledger__card {
  padding: var(--sems-space-4);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-card);
  background-color: var(--sems-bg-card);
}
.bag-ledger__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-2);
}
.bag-ledger__card-code {
  font-size: var(--sems-font-size-base);
  font-weight: var(--sems-font-weight-medium);
  color: var(--sems-text-primary);
  word-break: break-all;
}
.bag-ledger__card-meta {
  margin: 0 0 var(--sems-space-1);
  color: var(--sems-text-regular);
  font-size: var(--sems-font-size-sm);
}
.bag-ledger__card-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sems-space-2);
  margin-top: var(--sems-space-2);
  padding-top: var(--sems-space-2);
  border-top: 1px solid var(--sems-border);
}
.bag-ledger__row-actions {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: var(--sems-space-2);
  white-space: nowrap;
}
.bag-ledger__row-actions :deep(.el-button) {
  margin-left: 0;
}

/* 对话框 */
.bag-ledger__dialog-alert {
  margin-bottom: var(--sems-space-4);
}
.bag-ledger__dialog-desc {
  margin: 0 0 var(--sems-space-3);
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-base);
}
.bag-ledger__dialog-select {
  width: 100%;
}
.bag-ledger__field-tip {
  margin-left: var(--sems-space-3);
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-sm);
}

/* 二维码对话框 */
.bag-ledger__qr {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
}
.bag-ledger__qr-img {
  display: block;
  width: 200px;
  height: 200px;
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-base);
}
.bag-ledger__qr-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sems-space-2);
  min-height: 200px;
  text-align: center;
}
.bag-ledger__qr-text {
  margin: 0;
}
.bag-ledger__qr-code {
  margin: var(--sems-space-3) 0 0;
  text-align: center;
  font-size: var(--sems-font-size-lg);
  color: var(--sems-text-primary);
}
.bag-ledger__qr-hint {
  margin: var(--sems-space-1) 0 0;
  text-align: center;
}

@media (max-width: 767px) {
  .bag-ledger__filter-select,
  .bag-ledger__filter-station,
  .bag-ledger__filter-input {
    width: 100%;
  }
}
</style>
