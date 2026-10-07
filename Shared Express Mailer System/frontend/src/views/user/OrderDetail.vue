<script setup>
/**
 * 用户端 · 订单详情（《系统设计说明书》4.2 / 5.2）。
 *
 * - getOrder(orderNo) 拉取本人订单，非本人订单后端返回 2002，页面转为「无权查看」空态；
 * - 展示订单号、袋码、状态、寄件信息（快递公司 / 运单号 / 收件人掩码）与节点时间线；
 * - 时间线用 BagTimeline 渲染，records 由订单各节点时间自行构造（只放非空节点）；
 * - 操作按钮与「我的订单」同一口径：待寄件 → 去寄件 / 撤回申领；使用中 → 标记归还。
 */
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import BagTimeline from '@/components/BagTimeline.vue'
import { cancelBorrow, getOrder, markReturn } from '@/api/order'
import { listNearbyStations } from '@/api/station'
import { ORDER_STATUS, ORDER_STATUS_META } from '@/constants'
import { useUserStore } from '@/stores/user'
import { useWebSocketStore } from '@/stores/websocket'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const wsStore = useWebSocketStore()

const orderNo = computed(() => String(route.params.orderNo || ''))
const order = ref(null)
const loading = ref(true)
const loadError = ref('')
const forbidden = ref(false)

async function load(silent = false) {
  const quiet = silent === true
  if (!quiet) {
    loading.value = true
    loadError.value = ''
    forbidden.value = false
  }
  try {
    order.value = await getOrder(orderNo.value)
  } catch (error) {
    if (error?.code === 2002) {
      order.value = null
      forbidden.value = true
    } else if (!quiet) {
      order.value = null
      loadError.value = error?.message || '订单加载失败，请重试'
    }
  } finally {
    if (!quiet) loading.value = false
  }
}

/* ------------------------------ 展示辅助 ------------------------------ */

function formatTime(value) {
  if (!value) return '—'
  const text = String(value).replace('T', ' ')
  return text.length > 16 ? text.slice(0, 16) : text
}

/** 超时是标记不是状态（设计书 3.6） */
function isTimeout(row) {
  return row?.timeoutFlag === 1 || row?.timeoutFlag === true || row?.timeoutFlag === '1'
}

const statusLabel = computed(() => ORDER_STATUS_META[order.value?.status]?.label || '—')

const isShipped = computed(() => {
  const data = order.value
  return Boolean(data?.expressCompany || data?.trackingNo || data?.recipientPhoneMask)
})

/**
 * 订单节点时间线：只放非空节点；event 用中文节点名，toStatus 用对应订单状态码，
 * createdAt 用各节点时间。操作人 / 站点信息订单侧能确定的才填，其余留空由组件兜底展示。
 */
const timelineRecords = computed(() => {
  const data = order.value
  if (!data) return []
  const records = []
  let prevStatus = ''

  const push = (node) => {
    if (!node.time) return
    records.push({
      event: node.event,
      fromStatus: prevStatus,
      toStatus: node.toStatus,
      operatorId: node.operatorId ?? null,
      operatorName: node.operatorName || '',
      stationId: node.stationId ?? null,
      stationName: node.stationName || '',
      remark: node.remark || '',
      createdAt: node.time,
    })
    prevStatus = node.toStatus
  }

  push({
    event: '申领',
    time: data.createdAt,
    toStatus: ORDER_STATUS.PENDING_SEND,
    operatorId: userStore.user?.id,
    operatorName: '本人',
    remark: '扫码申领成功，订单创建',
  })
  push({
    event: '提交寄件',
    time: data.shippedAt,
    toStatus: ORDER_STATUS.IN_USE,
    operatorId: userStore.user?.id,
    operatorName: '本人',
    remark: data.expressCompany
      ? [data.expressCompany, data.trackingNo].filter(Boolean).join(' / ')
      : '已提交寄件信息',
  })
  push({
    event: '标记归还',
    time: data.returnMarkAt,
    toStatus: ORDER_STATUS.PENDING_CONFIRM,
    operatorId: userStore.user?.id,
    operatorName: '本人',
    stationId: data.returnStationId,
    stationName: data.returnStationName || '',
    remark: '已投放回收箱，等待站点回收确认',
  })
  push({
    event: '回收确认',
    time: data.confirmedAt,
    toStatus: ORDER_STATUS.FINISHED,
    operatorName: '站点管理员',
    stationId: data.returnStationId,
    stationName: data.returnStationName || '',
    remark: '站点回收确认，订单完成',
  })
  push({
    event: '取消',
    time: data.cancelledAt,
    toStatus: ORDER_STATUS.CANCELLED,
    remark: '订单已取消（撤回或超时取消），袋由站点回收闭环',
  })
  push({
    event: '遗失',
    time: data.lostAt,
    toStatus: ORDER_STATUS.LOST,
    operatorName: '平台管理员',
    remark: '已确认遗失，写入责任记录',
  })
  return records
})

/* ------------------------------ 操作 ------------------------------ */

const canShip = computed(() => order.value?.status === ORDER_STATUS.PENDING_SEND)
const canReturn = computed(() => order.value?.status === ORDER_STATUS.IN_USE)
const hasActions = computed(() => canShip.value || canReturn.value)

function goBagInfo() {
  router.push({ name: 'BagInfo', params: { bagCode: order.value?.bagCode } })
}

function goShipping() {
  router.push({ name: 'ShippingForm', params: { orderNo: orderNo.value } })
}

/** 撤回口径（设计书 3.2）：只取消订单，不改袋状态、不释放在持袋数额度 */
const CANCEL_CONFIRM_TEXT =
  '撤回只取消订单，不释放取袋额度，袋仍在您手上，请尽快投回任一站点回收箱。'

async function confirmCancel() {
  try {
    await ElMessageBox.confirm(`${CANCEL_CONFIRM_TEXT}确认撤回该订单吗？`, '撤回申领确认', {
      type: 'warning',
      confirmButtonText: '确认撤回',
      cancelButtonText: '再想想',
    })
  } catch {
    return // 用户放弃撤回
  }
  try {
    await cancelBorrow(orderNo.value)
    ElMessage.success('订单已撤回，请尽快将袋投回任一站点回收箱')
    load()
  } catch {
    // 失败提示已由 request.js 按错误码统一弹出
  }
}

/* ------------------------------ 标记归还 ------------------------------ */

const returnDialog = reactive({ visible: false, stationId: null, submitting: false })
const stations = ref([])
const stationsLoading = ref(false)
const stationsError = ref('')

async function openReturnDialog() {
  returnDialog.visible = true
  returnDialog.stationId = null
  await loadStations()
}

async function loadStations() {
  stationsLoading.value = true
  stationsError.value = ''
  try {
    const data = await listNearbyStations()
    stations.value = Array.isArray(data) ? data : data?.records || []
  } catch (error) {
    stations.value = []
    stationsError.value = error?.message || '站点加载失败，请重试'
  } finally {
    stationsLoading.value = false
  }
}

function stationLabel(station) {
  return station?.name || station?.stationName || `站点编号 ${station?.id ?? ''}`
}

async function submitReturn() {
  if (!returnDialog.stationId) {
    ElMessage.warning('请先选择投放站点')
    return
  }
  returnDialog.submitting = true
  try {
    await markReturn(orderNo.value, { stationId: returnDialog.stationId })
    ElMessage.success('已标记归还，请尽快将袋投入所选站点的回收箱')
    returnDialog.visible = false
    load()
  } catch {
    // 失败提示已由 request.js 按错误码统一弹出
  } finally {
    returnDialog.submitting = false
  }
}

/* ------------------------------ 实时刷新 ------------------------------ */

const orderTopic = computed(() => (userStore.user?.id ? `user/${userStore.user.id}/order` : ''))

function subscribeOrderTopic() {
  if (orderTopic.value) wsStore.subscribe(orderTopic.value)
}

watch(
  () => wsStore.authed,
  (authed) => {
    if (authed) subscribeOrderTopic()
  },
)

watch(
  () => wsStore.lastMessage,
  (message) => {
    const topic = message?.topic
    if (typeof topic === 'string' && topic.startsWith('user/')) {
      load(true)
    }
  },
)

onMounted(() => {
  load()
  subscribeOrderTopic()
})

onUnmounted(() => {
  if (orderTopic.value) wsStore.unsubscribe(orderTopic.value)
})
</script>

<template>
  <div class="order-detail">
    <PageSkeleton v-if="loading" :rows="4" type="card" />

    <EmptyState v-else-if="forbidden" icon="Lock" description="无权查看该订单">
      <el-button type="primary" @click="router.replace({ name: 'MyOrders' })">返回我的订单</el-button>
    </EmptyState>

    <EmptyState v-else-if="loadError" icon="Warning" :description="loadError">
      <el-button type="primary" @click="load()">重试</el-button>
    </EmptyState>

    <template v-else-if="order">
      <PageCard title="订单信息">
        <div class="info-row">
          <span class="info-label">订单号</span>
          <span class="info-value sems-mono">{{ order.orderNo }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">袋码</span>
          <span class="info-value sems-mono">{{ order.bagCode }}</span>
          <el-button link type="primary" @click="goBagInfo">袋详情</el-button>
        </div>
        <div class="info-row">
          <span class="info-label">状态</span>
          <span class="info-value">
            <StatusTag kind="order" :value="order.status" size="small" />
            <span v-if="isTimeout(order)" class="timeout-badge">已超时</span>
          </span>
          <span class="sems-text-muted">申领时间 {{ formatTime(order.createdAt) }}</span>
        </div>
      </PageCard>

      <PageCard title="寄件信息">
        <template v-if="isShipped">
          <div class="info-row">
            <span class="info-label">快递公司</span>
            <span class="info-value">{{ order.expressCompany || '—' }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">运单号</span>
            <span class="info-value sems-mono">{{ order.trackingNo || '—' }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">收件人手机号</span>
            <span class="info-value sems-mono">{{ order.recipientPhoneMask || '—' }}</span>
          </div>
        </template>
        <p v-else class="sems-text-muted">尚未提交寄件信息。</p>
      </PageCard>

      <PageCard title="状态时间线" subtitle="按订单节点时间展示，节点包含申领、寄件、归还与确认等关键动作">
        <BagTimeline :records="timelineRecords" />
      </PageCard>

      <PageCard v-if="hasActions" title="可执行操作">
        <div class="detail-actions">
          <template v-if="canShip">
            <el-button @click="confirmCancel">撤回申领</el-button>
            <el-button type="primary" @click="goShipping">去寄件</el-button>
          </template>
          <el-button v-else-if="canReturn" type="primary" @click="openReturnDialog">
            标记归还
          </el-button>
        </div>
        <p v-if="canShip" class="sems-text-muted detail-actions__tip">
          撤回只取消订单，不释放取袋额度，袋仍在您手上，请尽快投回任一站点回收箱。
        </p>
        <p v-else-if="canReturn" class="sems-text-muted detail-actions__tip">
          标记归还后订单进入「待回收确认」，袋保持「使用中」。请将袋投入所选站点的回收箱，等待站点确认。
        </p>
      </PageCard>
    </template>

    <!-- 标记归还：选择投放站点 -->
    <el-dialog
      v-model="returnDialog.visible"
      title="标记归还"
      width="min(92vw, 420px)"
      :close-on-click-modal="false"
    >
      <p class="return-tip sems-text-muted">
        请选择您投放回收箱的站点。标记后订单进入「待回收确认」，袋保持「使用中」，待站点确认后订单完成。
      </p>

      <PageSkeleton v-if="stationsLoading" :rows="3" type="card" />

      <EmptyState v-else-if="stationsError" icon="Warning" :description="stationsError">
        <el-button type="primary" @click="loadStations">重试</el-button>
      </EmptyState>

      <EmptyState v-else-if="!stations.length" description="附近暂无可投放的站点">
        <el-button @click="loadStations">刷新</el-button>
      </EmptyState>

      <el-radio-group v-else v-model="returnDialog.stationId" class="station-list">
        <el-radio v-for="station in stations" :key="station.id" :value="station.id">
          <span class="station-item__name">{{ stationLabel(station) }}</span>
          <span v-if="station.address" class="station-item__address sems-text-muted">
            {{ station.address }}
          </span>
        </el-radio>
      </el-radio-group>

      <template #footer>
        <el-button @click="returnDialog.visible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!returnDialog.stationId"
          :loading="returnDialog.submitting"
          @click="submitReturn"
        >
          确认标记归还
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.order-detail {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-4);
}

.info-row {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  padding: var(--sems-space-2) 0;
  border-bottom: 1px solid var(--sems-border);
}

.info-row:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.info-label {
  flex: none;
  width: 84px;
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-sm);
}

.info-value {
  flex: 1;
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  color: var(--sems-text-primary);
  word-break: break-all;
}

.timeout-badge {
  padding: 0 var(--sems-space-1);
  border: 1px solid var(--sems-order-timeout);
  border-radius: var(--sems-radius-tag);
  color: var(--sems-order-timeout);
  font-size: var(--sems-font-size-xs);
  line-height: 18px;
  white-space: nowrap;
}

.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sems-space-2);
}

.detail-actions__tip {
  margin: var(--sems-space-3) 0 0;
}

.return-tip {
  margin: 0 0 var(--sems-space-3);
}

.station-list {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
  width: 100%;
}

.station-list :deep(.el-radio) {
  display: flex;
  align-items: flex-start;
  width: 100%;
  height: auto;
  margin-right: 0;
  padding: var(--sems-space-3);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-base);
}

.station-list :deep(.el-radio.is-checked) {
  border-color: var(--sems-color-primary);
}

.station-list :deep(.el-radio__label) {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-1);
  white-space: normal;
}

.station-item__name {
  color: var(--sems-text-primary);
  font-size: var(--sems-font-size-base);
  line-height: 1.4;
}

.station-item__address {
  line-height: 1.4;
}
</style>
