<script setup>
/**
 * 平台端 · 袋详情与流转追溯（见《系统设计说明书》4.4「平台端」、5.4，FR-M4-06/15/16）。
 *
 * 1) 基本信息：用 listBags({ keyword: 袋码 }) 取台账记录后按袋码精确匹配——
 *    比 getBagInfo 的最小必要集更全（含站点、复用次数、创建时间），且未激活/报废/遗失袋也能查看；
 * 2) 二维码：getBagQrcode 返回 PNG blob，用 URL.createObjectURL 展示，切换/卸载时 revoke；
 * 3) 完整流转日志：getBagTrace + <BagTimeline>，逐节点展示「取—用—还—洗—调」闭环；
 * 4) 页内入口：状态修正（#21，审计重点核查项）、遗失找回（#20，仅「已遗失」可用），
 *    对话框逻辑与平台端袋台账一致。
 */
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { BAG_STATUS, BAG_STATUS_META, STATION_STATUS_META } from '@/constants'
import { getBagQrcode, getBagTrace, listBags, lostRecovery, statusCorrect } from '@/api/bag'
import { listStations } from '@/api/station'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusTag from '@/components/StatusTag.vue'
import BagTimeline from '@/components/BagTimeline.vue'

const route = useRoute()
const router = useRouter()

/** 路由参数袋码（路由变化时重新加载本页） */
const bagCode = computed(() => String(route.params.bagCode || ''))

const bag = ref(null)
const traceList = ref([])
const loading = ref(true)
const error = ref(false)
const notFound = ref(false)

/* ============================== 数据加载 ============================== */

async function load() {
  const code = bagCode.value
  if (!code) {
    notFound.value = true
    loading.value = false
    return
  }
  loading.value = true
  error.value = false
  notFound.value = false
  try {
    const [bagData, trace] = await Promise.all([
      listBags({ keyword: code, pageNum: 1, pageSize: 10 }),
      getBagTrace(code),
    ])
    const records = Array.isArray(bagData?.records) ? bagData.records : []
    // 关键字可能模糊命中多只，优先精确匹配
    bag.value =
      records.find((item) => item.bagCode === code) || (records.length === 1 ? records[0] : null)
    traceList.value = Array.isArray(trace) ? trace : trace?.records || []
    if (bag.value) loadQrcode()
    else notFound.value = true
  } catch {
    // 具体错误文案由 request.js 统一提示，这里只落到「错误态 + 重试」三态
    bag.value = null
    traceList.value = []
    error.value = true
  } finally {
    loading.value = false
  }
}

watch(bagCode, () => load(), { immediate: true })

/* ============================== 二维码（blob 需 revoke） ============================== */

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

async function loadQrcode() {
  if (!bagCode.value) return
  qrLoading.value = true
  qrError.value = false
  try {
    const res = await getBagQrcode(bagCode.value)
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

onUnmounted(releaseQrUrl)

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

function downloadQrcode() {
  if (!qrBlob.value) return
  saveBlob(qrBlob.value, `${bagCode.value}_二维码.png`)
}

/* ============================== 展示辅助 ============================== */

const statusCode = computed(() => bag.value?.status || '')
const statusLabel = computed(
  () => BAG_STATUS_META[statusCode.value]?.label || statusCode.value || '—',
)
const useCountText = computed(() => `${bag.value?.useCount ?? 0} / ${bag.value?.maxUseCount ?? '—'}`)

function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 19)
}

function goBack() {
  router.push('/platform/bags')
}

async function copyCode() {
  try {
    await navigator.clipboard.writeText(bagCode.value)
    ElMessage.success('袋码已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择袋码复制')
  }
}

/* ============================== 站点下拉 ============================== */

const stationOptions = ref([])

async function loadStations() {
  try {
    const res = await listStations({ pageNum: 1, pageSize: 100 })
    stationOptions.value = (Array.isArray(res) ? res : res?.records) || []
  } catch {
    // 下拉加载失败不阻断详情展示；request.js 已统一提示
    stationOptions.value = []
  }
}

function stationLabel(station) {
  const suffix = station?.status === 'DISABLED' ? `（${STATION_STATUS_META.DISABLED.label}）` : ''
  return `${station?.name || `站点 ID ${station?.id ?? ''}`}${suffix}`
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
  refCheckNo: '',
  reason: '',
  targetStatus: '',
  crossing: false,
  targetStationId: null,
})

const correctRules = computed(() => ({
  refCheckNo: [
    { required: true, message: '请输入盘点差异单号', trigger: 'blur' },
    { max: 50, message: '盘点差异单号不超过 50 个字符', trigger: 'blur' },
  ],
  reason: [
    { required: true, message: '请输入修正原因', trigger: 'blur' },
    { max: 255, message: '修正原因不超过 255 个字符', trigger: 'blur' },
  ],
  targetStatus: [{ required: true, message: '请选择目标状态', trigger: 'change' }],
  targetStationId: correctForm.crossing
    ? [{ required: true, message: '跨站修正必须选择目标站点', trigger: 'change' }]
    : [],
}))

function openCorrect() {
  correctForm.refCheckNo = ''
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
      `确认将袋 ${bagCode.value} 的状态修正为「${targetLabel}」？状态修正为审计重点核查项，将写入流转日志与审计日志。`,
      '状态修正确认',
      { type: 'warning', confirmButtonText: '确认修正', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  correctSubmitting.value = true
  try {
    const payload = {
      refCheckNo: correctForm.refCheckNo.trim(),
      reason: correctForm.reason.trim(),
      targetStatus: correctForm.targetStatus,
    }
    // 仅跨站修正时才带目标站点（同站修正不得改归属）
    if (correctForm.crossing) payload.targetStationId = correctForm.targetStationId
    await statusCorrect(bagCode.value, payload)
    ElMessage.success('状态修正已提交，流转日志与审计日志已记录')
    correctVisible.value = false
    await load()
  } catch (error) {
    reportSubmitError(error)
  } finally {
    correctSubmitting.value = false
  }
}

/* ============================== 遗失找回（#20，仅已遗失） ============================== */

const recoveryVisible = ref(false)
const recoverySubmitting = ref(false)
const recoveryFormRef = ref(null)
const recoveryForm = reactive({ remark: '', stationId: null })

const recoveryRules = {
  remark: [
    { required: true, message: '请输入找回说明', trigger: 'blur' },
    { max: 255, message: '找回说明不超过 255 个字符', trigger: 'blur' },
  ],
  stationId: [{ required: true, message: '请选择找回站点', trigger: 'change' }],
}

function openRecovery() {
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
    await lostRecovery(bagCode.value, {
      remark: recoveryForm.remark.trim(),
      stationId: recoveryForm.stationId,
    })
    ElMessage.success('找回已登记，该袋转入待消毒，合格回流后可复用')
    recoveryVisible.value = false
    await load()
  } catch (error) {
    reportSubmitError(error)
  } finally {
    recoverySubmitting.value = false
  }
}

/* ============================== 生命周期 ============================== */

onMounted(() => {
  loadStations()
})
</script>

<template>
  <PageCard title="袋详情与流转追溯" :subtitle="`袋码 ${bagCode}`">
    <template #extra>
      <el-button @click="goBack">返回台账</el-button>
      <el-button
        :disabled="statusCode !== BAG_STATUS.LOST"
        :title="statusCode === BAG_STATUS.LOST ? '' : '仅「已遗失」状态的袋可登记找回'"
        @click="openRecovery"
      >
        遗失找回
      </el-button>
      <el-button
        type="primary"
        :disabled="statusCode === BAG_STATUS.SCRAPPED"
        @click="openCorrect"
      >
        状态修正
      </el-button>
    </template>

    <!-- 加载态：骨架屏 -->
    <PageSkeleton v-if="loading" type="card" :rows="4" />

    <!-- 错误态：给出原因与重试 -->
    <div v-else-if="error" class="bag-detail__error">
      <el-icon class="bag-detail__error-icon" :size="32"><WarningFilled /></el-icon>
      <p class="bag-detail__error-text">袋详情加载失败，请检查网络后重试</p>
      <el-button type="primary" @click="load">重新加载</el-button>
    </div>

    <!-- 空态：袋码不存在或已跨站转移 -->
    <EmptyState
      v-else-if="notFound"
      description="台账中未找到该快递袋（可能已跨站转移或编码有误）"
    >
      <el-button type="primary" @click="goBack">返回台账</el-button>
    </EmptyState>

    <template v-else-if="bag">
      <div class="bag-detail__top">
        <dl class="bag-detail__fields">
          <div class="bag-detail__field">
            <dt>袋码</dt>
            <dd>
              <span class="sems-mono">{{ bag.bagCode || bagCode }}</span>
              <el-button link type="primary" @click="copyCode">复制</el-button>
            </dd>
          </div>
          <div class="bag-detail__field">
            <dt>状态</dt>
            <dd><StatusTag kind="bag" :value="bag.status" /></dd>
          </div>
          <div class="bag-detail__field">
            <dt>规格</dt>
            <dd>{{ bag.spec || '—' }}</dd>
          </div>
          <div class="bag-detail__field">
            <dt>颜色</dt>
            <dd>{{ bag.color || '—' }}</dd>
          </div>
          <div class="bag-detail__field">
            <dt>容量</dt>
            <dd>{{ bag.capacity || '—' }}</dd>
          </div>
          <div class="bag-detail__field">
            <dt>所在站点</dt>
            <dd>{{ bag.stationName || bag.stationId || '—' }}</dd>
          </div>
          <div class="bag-detail__field">
            <dt>复用次数 / 上限</dt>
            <dd><span class="sems-num">{{ useCountText }}</span></dd>
          </div>
          <div class="bag-detail__field">
            <dt>创建时间</dt>
            <dd class="sems-num">{{ formatTime(bag.createdAt) }}</dd>
          </div>
          <div class="bag-detail__field">
            <dt>更新时间</dt>
            <dd class="sems-num">{{ formatTime(bag.updatedAt) }}</dd>
          </div>
        </dl>

        <div class="bag-detail__qr">
          <img v-if="qrUrl" class="bag-detail__qr-img" :src="qrUrl" alt="快递袋二维码" />
          <div v-else class="bag-detail__qr-placeholder sems-text-muted">
            <template v-if="qrError">
              <p class="bag-detail__qr-text">二维码加载失败</p>
              <el-button size="small" @click="loadQrcode">重试</el-button>
            </template>
            <p v-else class="bag-detail__qr-text">
              {{ qrLoading ? '二维码加载中…' : '二维码待加载' }}
            </p>
          </div>
          <el-button size="small" :disabled="!qrBlob" @click="downloadQrcode">保存二维码</el-button>
          <p class="bag-detail__qr-hint sems-text-muted">二维码内容指向袋详情页，可用于标签补打</p>
        </div>
      </div>

      <el-divider />

      <section>
        <h3 class="sems-section-title">可用操作</h3>
        <p class="bag-detail__notice sems-text-muted">
          <el-icon><InfoFilled /></el-icon>
          <span>
            当前状态：{{ statusLabel }}。状态修正仅用于盘点差异等场景，目标状态限白名单
            {{ CORRECT_TARGET_OPTIONS.map((item) => item.label).join(' / ') }}，跨站修正须指定目标站点；
            遗失找回仅对「已遗失」袋开放，找回后转入待消毒。两者均会写入流转日志与审计日志。
          </span>
        </p>
        <div class="bag-detail__action-buttons">
          <el-button
            type="primary"
            :disabled="statusCode === BAG_STATUS.SCRAPPED"
            @click="openCorrect"
          >
            状态修正
          </el-button>
          <el-button
            :disabled="statusCode !== BAG_STATUS.LOST"
            :title="statusCode === BAG_STATUS.LOST ? '' : '仅「已遗失」状态的袋可登记找回'"
            @click="openRecovery"
          >
            遗失找回
          </el-button>
        </div>
      </section>

      <el-divider />

      <section>
        <h3 class="sems-section-title">完整流转日志（取—用—还—洗—调）</h3>
        <BagTimeline v-if="traceList.length" :records="traceList" />
        <EmptyState v-else icon="Clock" description="暂无流转日志" />
      </section>
    </template>
  </PageCard>

  <!-- ============================ 状态修正（审计重点核查项） ============================ -->
  <el-dialog
    v-model="correctVisible"
    title="状态修正"
    width="min(520px, 92vw)"
    :close-on-click-modal="false"
  >
    <el-alert
      class="bag-detail__dialog-alert"
      type="warning"
      :closable="false"
      show-icon
      title="状态修正为审计重点核查项，将写入流转日志与审计日志"
      description="仅盘点差异等场景可用；目标状态限白名单，跨站修正须指定目标站点。"
    />
    <p class="bag-detail__dialog-bag">
      袋码：<span class="sems-mono">{{ bagCode }}</span>
    </p>
    <el-form ref="correctFormRef" :model="correctForm" :rules="correctRules" label-width="100px">
      <el-form-item label="盘点差异单号" prop="refCheckNo">
        <el-input
          v-model="correctForm.refCheckNo"
          maxlength="50"
          placeholder="盘点单号（必填，关联盘点差异）"
        />
      </el-form-item>
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
          class="bag-detail__dialog-select"
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
          class="bag-detail__dialog-select"
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
      class="bag-detail__dialog-alert"
      type="info"
      :closable="false"
      show-icon
      title="找回后该袋转入待消毒，消毒合格后回流复用，不重复计复用次数"
    />
    <p class="bag-detail__dialog-bag">
      袋码：<span class="sems-mono">{{ bagCode }}</span>
    </p>
    <el-form ref="recoveryFormRef" :model="recoveryForm" :rules="recoveryRules" label-width="100px">
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
          class="bag-detail__dialog-select"
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
</template>

<style scoped>
.bag-detail__top {
  display: flex;
  align-items: flex-start;
  gap: var(--sems-space-5);
  flex-wrap: wrap;
}

.bag-detail__fields {
  flex: 1;
  min-width: 260px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--sems-space-3) var(--sems-space-5);
  margin: 0;
}
.bag-detail__field {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-1);
}
.bag-detail__field dt {
  margin: 0;
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-sm);
}
.bag-detail__field dd {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  flex-wrap: wrap;
  margin: 0;
  color: var(--sems-text-primary);
}

.bag-detail__qr {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-2);
  width: 200px;
  flex: none;
  padding: var(--sems-space-3);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-card);
  background-color: var(--sems-bg-card);
}
.bag-detail__qr-img {
  display: block;
  width: 168px;
  height: 168px;
}
.bag-detail__qr-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sems-space-2);
  width: 168px;
  min-height: 168px;
  text-align: center;
}
.bag-detail__qr-text {
  margin: 0;
}
.bag-detail__qr-hint {
  margin: 0;
  text-align: center;
}

.bag-detail__notice {
  display: flex;
  align-items: flex-start;
  gap: var(--sems-space-2);
  margin: var(--sems-space-2) 0 var(--sems-space-4);
  line-height: 1.6;
}
.bag-detail__notice .el-icon {
  margin-top: 3px;
  flex: none;
}
.bag-detail__action-buttons {
  display: flex;
  gap: var(--sems-space-2);
  flex-wrap: wrap;
}

.bag-detail__error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-3);
  padding: var(--sems-space-6) 0;
  color: var(--sems-text-secondary);
}
.bag-detail__error-icon {
  color: var(--sems-color-warning);
}
.bag-detail__error-text {
  margin: 0;
}

.bag-detail__dialog-alert {
  margin-bottom: var(--sems-space-4);
}
.bag-detail__dialog-bag {
  margin: 0 0 var(--sems-space-3);
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-base);
}
.bag-detail__dialog-select {
  width: 100%;
}
</style>
