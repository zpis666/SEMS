<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listBags, getBagTrace, getBagQrcode, unusedReturn, repairBag, scrapBag } from '@/api/bag'
import { BAG_STATUS_META } from '@/constants'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusTag from '@/components/StatusTag.vue'
import BagTimeline from '@/components/BagTimeline.vue'

/**
 * 站点端 · 袋详情（设计书 4.3）。
 *
 * 1) 基本信息：站点管理员视角比用户端多——用 listBags({ keyword: 袋码 }) 取台账记录（含站点、复用次数等），
 *    再按袋码精确匹配；不额外传 stationId（后端已限本站）；
 * 2) 流转日志：getBagTrace + <BagTimeline>，完整展示「取—用—还—洗—调」闭环；
 * 3) 可用操作：未使用回收（unusedReturn，仅「已分配」适用）、转维修（#12）、报废（#15）；
 *    转维修/报废的状态机允许前态同为「空闲 / 待消毒 / 可复用」；「找回」为平台管理员专属，不在本页提供。
 */

const route = useRoute()
const router = useRouter()

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

/** 路由参数袋码（URL 变化时重新加载本页） */
const bagCode = computed(() => String(route.params.bagCode || ''))

const bag = ref(null)
const traceList = ref([])
const loading = ref(true)
const error = ref(false)
const notFound = ref(false)

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

/* ------------------------------ 二维码 ------------------------------ */

const qrUrl = ref('')
const qrLoading = ref(false)
const qrError = ref(false)

function releaseQrUrl() {
  if (qrUrl.value) {
    URL.revokeObjectURL(qrUrl.value)
    qrUrl.value = ''
  }
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
    qrUrl.value = URL.createObjectURL(blob)
  } catch {
    qrError.value = true
  } finally {
    qrLoading.value = false
  }
}

onUnmounted(releaseQrUrl)

/* ------------------------------ 弹窗宽度 ------------------------------ */

const viewportWidth = ref(window.innerWidth)
function syncViewport() {
  viewportWidth.value = window.innerWidth
}
const dialogWidth = computed(() => (viewportWidth.value < 768 ? '92%' : '480px'))

onMounted(() => {
  syncViewport()
  window.addEventListener('resize', syncViewport)
})
onUnmounted(() => window.removeEventListener('resize', syncViewport))

/* ------------------------------ 展示与能力判定 ------------------------------ */

const bagStatusLabel = computed(
  () => BAG_STATUS_META[bag.value?.status]?.label || bag.value?.status || '—',
)

const useLimit = computed(() => {
  const used = Number(bag.value?.useCount ?? 0)
  const max = Number(bag.value?.maxUseCount ?? 0)
  return max > 0 && used >= max
})

const useCountText = computed(
  () => `${bag.value?.useCount ?? 0} / ${bag.value?.maxUseCount ?? '—'}`,
)

/** 未使用回收（状态机 #4）：仅「已分配」适用 */
const canUnusedReturn = computed(() => bag.value?.status === 'ALLOCATED')
/** 转维修（#12）/ 报废（#15）：空闲、待消毒、可复用 三种前态 */
const canRepairOrScrap = computed(() =>
  ['IDLE', 'TO_DISINFECT'].includes(bag.value?.status),
)

function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

function goBack() {
  router.push('/station/bags')
}

async function copyCode() {
  try {
    await navigator.clipboard.writeText(bagCode.value)
    ElMessage.success('袋码已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择袋码复制')
  }
}

/* ------------------------------ 操作：未使用回收 ------------------------------ */

const acting = ref(false)

async function handleUnusedReturn() {
  if (!canUnusedReturn.value) return
  try {
    await ElMessageBox.confirm(
      '仅当绑定订单已取消或不存在时，未使用回收才会把袋收回空闲；若订单仍在待寄件，后端会自动路由为回收确认（袋转入待消毒）。确认提交？',
      '未使用回收',
      { type: 'warning', confirmButtonText: '确认回收', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  acting.value = true
  try {
    await unusedReturn(bagCode.value, {})
    ElMessage.success('回收已提交')
    await load()
  } catch (error) {
    // 其余错误文案由 request.js 统一提示，3001 字段级错误在此兜底
    notifyFormError(error)
  } finally {
    acting.value = false
  }
}

/* ------------------------------ 操作：转维修 / 报废 ------------------------------ */

const repairFormRef = ref(null)
const scrapFormRef = ref(null)

const repairDialog = reactive({ visible: false, description: '', submitting: false })
const scrapDialog = reactive({ visible: false, reason: '', submitting: false })

const repairRules = {
  description: [
    { required: true, message: '请填写破损描述', trigger: 'blur' },
    { max: 255, message: '破损描述不超过 255 字', trigger: 'blur' },
  ],
}
const scrapRules = {
  reason: [
    { required: true, message: '请填写报废原因', trigger: 'blur' },
    { max: 255, message: '报废原因不超过 255 字', trigger: 'blur' },
  ],
}

function openRepair() {
  repairDialog.description = ''
  repairDialog.visible = true
}

function openScrap() {
  scrapDialog.reason = ''
  scrapDialog.visible = true
}

async function submitRepair() {
  const valid = await repairFormRef.value?.validate().catch(() => false)
  if (!valid) return
  repairDialog.submitting = true
  try {
    await repairBag(bagCode.value, { description: repairDialog.description.trim() })
    ElMessage.success('已登记维修，该袋转入维修中')
    repairDialog.visible = false
    await load()
  } catch (error) {
    // 其余错误文案由 request.js 统一提示，3001 字段级错误在此兜底
    notifyFormError(error)
  } finally {
    repairDialog.submitting = false
  }
}

async function submitScrap() {
  const valid = await scrapFormRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    await ElMessageBox.confirm(
      '报废为审计重点核查项：操作会写入审计日志并保留报废原因。确认对该袋执行报废？',
      '二次确认',
      { type: 'warning', confirmButtonText: '确认报废', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  scrapDialog.submitting = true
  try {
    await scrapBag(bagCode.value, { reason: scrapDialog.reason.trim() })
    ElMessage.success('已完成报废登记')
    scrapDialog.visible = false
    await load()
  } catch (error) {
    // 其余错误文案由 request.js 统一提示，3001 字段级错误在此兜底
    notifyFormError(error)
  } finally {
    scrapDialog.submitting = false
  }
}
</script>

<template>
  <PageCard title="快递袋详情" :subtitle="`袋码 ${bagCode}`">
    <template #extra>
      <el-button @click="goBack">返回台账</el-button>
    </template>

    <PageSkeleton v-if="loading" type="card" :rows="4" />

    <div v-else-if="error" class="bag-detail__error">
      <el-icon class="bag-detail__error-icon" :size="32"><WarningFilled /></el-icon>
      <p class="bag-detail__error-text">袋详情加载失败，请检查网络后重试</p>
      <el-button type="primary" @click="load">重新加载</el-button>
    </div>

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
              <span class="sems-mono">{{ bag.bagCode }}</span>
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
            <dd>
              <span class="sems-num">{{ useCountText }}</span>
              <el-tag v-if="useLimit" type="warning" size="small" effect="plain">
                须转维修/报废
              </el-tag>
            </dd>
          </div>
          <div class="bag-detail__field">
            <dt>建档时间</dt>
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
          <p class="bag-detail__qr-hint sems-text-muted">二维码内容指向袋详情页，可用于标签补打</p>
        </div>
      </div>

      <el-divider />

      <section>
        <h3 class="sems-section-title">可用操作</h3>
        <p class="bag-detail__notice sems-text-muted">
          <el-icon><InfoFilled /></el-icon>
          <span>
            未使用回收：仅当绑定订单已取消或不存在时可回收回空闲；若订单仍在待寄件，后端会自动路由为回收确认（袋转入待消毒）。
            「找回」为平台管理员专属操作，不在本页提供。
          </span>
        </p>
        <div class="bag-detail__action-buttons">
          <el-button
            type="primary"
            :disabled="!canUnusedReturn"
            :loading="acting"
            @click="handleUnusedReturn"
          >
            未使用回收
          </el-button>
          <el-button :disabled="!canRepairOrScrap" @click="openRepair">转维修</el-button>
          <el-button type="danger" plain :disabled="!canRepairOrScrap" @click="openScrap">
            报废
          </el-button>
        </div>
        <p v-if="!canUnusedReturn && !canRepairOrScrap" class="bag-detail__action-hint sems-text-muted">
          当前状态（{{ bagStatusLabel }}）下没有可用的袋状态操作，置灰按钮表示该操作不适用。
        </p>
      </section>

      <el-divider />

      <section>
        <h3 class="sems-section-title">流转日志（取—用—还—洗—调）</h3>
        <!-- BagTimeline 自带空态（暂无流转记录） -->
        <BagTimeline :records="traceList" />
      </section>
    </template>
  </PageCard>

  <!-- 转维修 -->
  <el-dialog
    v-model="repairDialog.visible"
    title="转维修"
    :width="dialogWidth"
    :close-on-click-modal="false"
  >
    <p class="sems-text-muted bag-detail__dialog-bag">
      袋码：<span class="sems-mono">{{ bagCode }}</span>
    </p>
    <el-form
      ref="repairFormRef"
      :model="repairDialog"
      :rules="repairRules"
      label-position="top"
    >
      <el-form-item label="破损描述" prop="description">
        <el-input
          v-model="repairDialog.description"
          type="textarea"
          :rows="3"
          maxlength="255"
          show-word-limit
          placeholder="请描述破损情况，如：封口拉链断裂、袋体划伤"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="repairDialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="repairDialog.submitting" @click="submitRepair">
        确认转维修
      </el-button>
    </template>
  </el-dialog>

  <!-- 报废：二次确认并标注审计重点核查 -->
  <el-dialog
    v-model="scrapDialog.visible"
    title="报废"
    :width="dialogWidth"
    :close-on-click-modal="false"
  >
    <p class="sems-text-muted bag-detail__dialog-bag">
      袋码：<span class="sems-mono">{{ bagCode }}</span>
    </p>
    <el-form ref="scrapFormRef" :model="scrapDialog" :rules="scrapRules" label-position="top">
      <el-form-item label="报废原因" prop="reason">
        <el-input
          v-model="scrapDialog.reason"
          type="textarea"
          :rows="3"
          maxlength="255"
          show-word-limit
          placeholder="如：严重破损无法修复 / 复用次数已达上限"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="scrapDialog.visible = false">取消</el-button>
      <el-button type="danger" :loading="scrapDialog.submitting" @click="submitScrap">
        提交报废
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
.bag-detail__action-hint {
  margin: var(--sems-space-3) 0 0;
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

.bag-detail__dialog-bag {
  margin: 0 0 var(--sems-space-3);
}
</style>
