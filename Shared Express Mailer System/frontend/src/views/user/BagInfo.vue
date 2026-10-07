<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getBagInfo, getBagTrace } from '@/api/bag'
import { BAG_CODE_REGEX, toUpperCase } from '@/utils/validators'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import StatusTag from '@/components/StatusTag.vue'
import BagTimeline from '@/components/BagTimeline.vue'

/**
 * 袋详情（路由参数 bagCode；《系统设计说明书》7.4 二维码方案、10.5 袋码与流转时间线）。
 *
 *  - getBagInfo：规格、状态标签、所在站点、是否可领用（非归属人只有最小必要集）；
 *  - getBagTrace：交 <BagTimeline> 展示「取—用—还—洗—调」全生命周期流转；
 *  - 袋码等宽字体展示 + 一键复制（navigator.clipboard，非安全上下文走兜底）。
 *
 * 注意：本页是用户端扫码落地页，**不调用**二维码接口——设计书 5.4 限定
 * GET /bags/{bagCode}/qrcode 仅站点/平台管理员可用，普通用户调用必然 403；
 * 页面上以袋码文本 + 说明呈现「袋上二维码」，二维码图片只在管理端（打印/补打）展示。
 */

const route = useRoute()
const router = useRouter()

const bagCode = computed(() => toUpperCase(String(route.params.bagCode || '')))

const loading = ref(true)
const loadError = ref('')
const info = ref(null)

const traceLoading = ref(true)
const traceError = ref('')
const traceForbidden = ref(false)
const traceList = ref([])

const traceSubtitle = computed(() => (traceList.value.length ? `共 ${traceList.value.length} 条` : ''))

function infoErrorMessage(error) {
  if (error?.code === 4106) return '没有找到这只袋，请确认袋码是否正确'
  if (error?.code === 4107) return '这只袋当前不可用（已报废或已遗失）'
  return error?.message || '袋信息加载失败，请重试'
}

function traceErrorMessage(error) {
  if (error?.code === 2001 || error?.code === 2002) return '该袋的流转记录仅对站点管理员开放'
  return error?.message || '流转记录加载失败，请重试'
}

async function load() {
  if (!BAG_CODE_REGEX.test(bagCode.value)) {
    loading.value = false
    loadError.value = '袋码格式不正确，请确认链接完整'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    info.value = await getBagInfo(bagCode.value)
  } catch (error) {
    info.value = null
    loadError.value = infoErrorMessage(error)
  } finally {
    loading.value = false
  }
  // 袋信息都查不到时，不再查流转日志
  if (!loadError.value) {
    loadTrace()
  }
}

async function loadTrace() {
  traceLoading.value = true
  traceError.value = ''
  traceForbidden.value = false
  try {
    const records = await getBagTrace(bagCode.value)
    traceList.value = Array.isArray(records) ? records : []
  } catch (error) {
    traceList.value = []
    traceError.value = traceErrorMessage(error)
    traceForbidden.value = error?.code === 2001 || error?.code === 2002
  } finally {
    traceLoading.value = false
  }
}

/** 一键复制袋码：优先 Clipboard API，失败时退回临时输入框方案 */
async function copyBagCode() {
  const code = bagCode.value
  if (!code) return
  try {
    await navigator.clipboard.writeText(code)
    ElMessage.success('袋码已复制')
    return
  } catch {
    /* 非安全上下文/权限受限时走下方兜底 */
  }
  try {
    const input = document.createElement('textarea')
    input.value = code
    input.style.position = 'fixed'
    input.style.opacity = '0'
    document.body.appendChild(input)
    input.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(input)
    if (ok) {
      ElMessage.success('袋码已复制')
    } else {
      ElMessage.error('复制失败，请长按袋码手动复制')
    }
  } catch {
    ElMessage.error('复制失败，请长按袋码手动复制')
  }
}

/** 可领用时引导到扫码取袋页并带上袋码（ScanBorrow 支持 query 预填） */
function goBorrow() {
  router.push({ path: '/m/scan', query: { bagCode: bagCode.value } })
}

onMounted(load)

// 同一路由换袋码时组件会被复用（/m/bag/:bagCode），参数变化要重新拉取
watch(bagCode, () => {
  if (bagCode.value) load()
})
</script>

<template>
  <div class="bag-info">
    <PageCard v-if="loading" title="袋详情">
      <PageSkeleton type="card" :rows="4" />
    </PageCard>

    <PageCard v-else-if="loadError" title="袋详情">
      <div class="bag-info__error">
        <el-icon :size="40" class="bag-info__error-icon"><WarningFilled /></el-icon>
        <p>{{ loadError }}</p>
        <div class="bag-info__error-actions">
          <el-button @click="router.back()">返回</el-button>
          <el-button type="primary" @click="load">重试</el-button>
        </div>
      </div>
    </PageCard>

    <template v-else>
      <PageCard title="基本信息">
        <div class="bag-info__rows">
          <div class="bag-info__row">
            <span class="bag-info__label">袋码</span>
            <span class="bag-info__code">
              <span class="sems-mono">{{ bagCode }}</span>
              <el-button link type="primary" @click="copyBagCode">
                <el-icon><CopyDocument /></el-icon>
                <span>复制</span>
              </el-button>
            </span>
          </div>
          <div class="bag-info__row">
            <span class="bag-info__label">规格</span>
            <span class="bag-info__value">{{ info?.spec || '—' }}</span>
          </div>
          <div v-if="info?.color || info?.capacity" class="bag-info__row">
            <span class="bag-info__label">颜色 / 容量</span>
            <span class="bag-info__value">
              {{ [info?.color, info?.capacity].filter(Boolean).join(' · ') }}
            </span>
          </div>
          <div v-if="info?.status" class="bag-info__row">
            <span class="bag-info__label">袋状态</span>
            <StatusTag kind="bag" :value="info.status" />
          </div>
          <div v-if="info?.useCount !== undefined && info?.useCount !== null" class="bag-info__row">
            <span class="bag-info__label">复用次数</span>
            <span class="bag-info__value">{{ info.useCount }} 次</span>
          </div>
          <div class="bag-info__row">
            <span class="bag-info__label">所在站点</span>
            <span class="bag-info__value">{{ info?.stationName || '—' }}</span>
          </div>
          <div class="bag-info__row">
            <span class="bag-info__label">是否可领用</span>
            <span v-if="info?.available" class="bag-info__available is-ok">
              <el-icon><CircleCheck /></el-icon>
              <span>可领用</span>
            </span>
            <span v-else class="bag-info__available is-no">
              <el-icon><CircleClose /></el-icon>
              <span>暂不可领用</span>
            </span>
          </div>
        </div>

        <el-button v-if="info?.available" type="primary" class="bag-info__borrow" @click="goBorrow">
          去申领这只袋
        </el-button>
      </PageCard>

      <!-- 设计书 5.4：二维码接口仅站点/平台管理员可用，用户端不请求（调用必然 403）；
           这里改为展示袋码文本与标签说明，二维码图片在管理端用于打印/补打 -->
      <PageCard title="袋上二维码">
        <div class="bag-info__qr">
          <p class="bag-info__qr-code sems-mono">{{ bagCode }}</p>
          <p class="bag-info__qr-hint sems-text-muted">
            二维码标签由站点管理员打印后粘贴在袋体上，内容即本页链接，扫码可直接打开本页；
            标签模糊时可用本页袋码人工核对。
          </p>
        </div>
      </PageCard>

      <PageCard title="流转记录" :subtitle="traceSubtitle">
        <PageSkeleton v-if="traceLoading" type="card" :rows="4" />
        <div v-else-if="traceError" class="bag-info__trace-error">
          <p class="sems-text-muted">{{ traceError }}</p>
          <el-button v-if="!traceForbidden" type="primary" plain @click="loadTrace">重试</el-button>
        </div>
        <!-- 空数组时 BagTimeline 内部会渲染 EmptyState，页面不重复处理 -->
        <BagTimeline v-else :records="traceList" />
      </PageCard>
    </template>
  </div>
</template>

<style scoped>
.bag-info {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-4);
}

/* ---------- 错误态 ---------- */
.bag-info__error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-3);
  padding: var(--sems-space-5) 0;
  text-align: center;
  color: var(--sems-text-secondary);
}

.bag-info__error p {
  margin: 0;
}

.bag-info__error-icon {
  color: var(--sems-color-warning);
}

.bag-info__error-actions {
  display: flex;
  gap: var(--sems-space-3);
}

/* ---------- 基本信息 ---------- */
.bag-info__rows {
  display: flex;
  flex-direction: column;
}

.bag-info__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-3);
  min-height: var(--sems-touch-target);
  border-bottom: 1px solid var(--sems-border);
}

.bag-info__row:last-child {
  border-bottom: none;
}

.bag-info__label {
  flex: none;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
}

.bag-info__value {
  text-align: right;
  color: var(--sems-text-primary);
  word-break: break-all;
}

.bag-info__code {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-2);
  color: var(--sems-text-primary);
  word-break: break-all;
}

.bag-info__available {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-1);
}

.bag-info__available.is-ok {
  color: var(--sems-color-success);
}

.bag-info__available.is-no {
  color: var(--sems-color-warning);
}

.bag-info__borrow {
  width: 100%;
  height: var(--sems-btn-height-touch);
  margin-top: var(--sems-space-4);
  font-size: var(--sems-font-size-lg);
}

/* ---------- 袋上二维码（文本说明，不请求二维码接口） ---------- */
.bag-info__qr {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-3);
}

.bag-info__qr-code {
  margin: 0;
  padding: var(--sems-space-4) var(--sems-space-5);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-base);
  background-color: var(--sems-bg-card);
  font-size: var(--sems-font-size-lg);
  font-weight: var(--sems-font-weight-medium);
  color: var(--sems-text-primary);
  word-break: break-all;
  text-align: center;
}

.bag-info__qr-hint {
  margin: 0;
  text-align: center;
}

/* ---------- 流转记录 ---------- */
.bag-info__trace-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-3);
  padding: var(--sems-space-4) 0;
  text-align: center;
}

.bag-info__trace-error p {
  margin: 0;
}
</style>
