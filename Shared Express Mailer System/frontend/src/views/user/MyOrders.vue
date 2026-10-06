<script setup>
/**
 * 用户端 · 我的订单（《系统设计说明书》4.2 / 5.2）。
 *
 * 交互要点：
 *  - 顶部状态筛选 Tab（全部 + ORDER_STATUS_META 六态），分页统一用 DataPager；
 *  - 卡片式列表：左侧 4px 订单状态色条 + 状态标签置顶，袋码与寄件信息控制在两行内，
 *    主操作右对齐；超时是标记不是状态（设计书 3.6），timeoutFlag 为真时叠「已超时」角标；
 *  - 待寄件可「去寄件 / 撤回申领」，撤回必须二次确认并写明「不释放取袋额度、袋仍在用户手上」；
 *  - 使用中可「标记归还」，弹窗从 listNearbyStations 选择投放站点；
 *  - 订阅 user/{id}/order 主题，收到推送后静默刷新列表（不闪骨架屏）。
 */
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DataPager from '@/components/DataPager.vue'
import { cancelBorrow, listMyOrders, markReturn } from '@/api/order'
import { listNearbyStations } from '@/api/station'
import { DEFAULT_PAGE_SIZE, ORDER_STATUS, ORDER_STATUS_META } from '@/constants'
import { useUserStore } from '@/stores/user'
import { useWebSocketStore } from '@/stores/websocket'

const router = useRouter()
const userStore = useUserStore()
const wsStore = useWebSocketStore()

/* ------------------------------ 列表与筛选 ------------------------------ */

/** 状态筛选 Tab：首项「全部」用哨兵值 ALL，其余直接取订单状态枚举顺序 */
const STATUS_TABS = [
  { value: 'ALL', label: '全部' },
  ...Object.entries(ORDER_STATUS_META).map(([value, meta]) => ({ value, label: meta.label })),
]

const activeStatus = ref('ALL')
const query = reactive({ pageNum: 1, pageSize: DEFAULT_PAGE_SIZE })
const orders = ref([])
const total = ref(0)
const loading = ref(true)
const loadError = ref('')

/**
 * 拉取本人订单分页。
 * silent=true 用于 WebSocket 推送后的静默刷新：保留现有内容、不闪骨架屏，
 * 失败也不切换到错误态（request.js 已统一提示），避免推送频繁时页面抖动。
 */
async function load(silent = false) {
  const quiet = silent === true
  if (!quiet) {
    loading.value = true
    loadError.value = ''
  }
  try {
    const data = await listMyOrders({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      status: activeStatus.value === 'ALL' ? undefined : activeStatus.value,
    })
    orders.value = data?.records || []
    total.value = data?.total || 0
  } catch (error) {
    if (!quiet) {
      orders.value = []
      total.value = 0
      loadError.value = error?.message || '订单加载失败，请重试'
    }
  } finally {
    if (!quiet) loading.value = false
  }
}

function onStatusChange() {
  query.pageNum = 1
  load()
}

const emptyText = computed(() => (activeStatus.value === 'ALL' ? '暂无订单' : '该状态下暂无订单'))

/* ------------------------------ 展示辅助 ------------------------------ */

function orderColor(order) {
  const token = ORDER_STATUS_META[order?.status]?.token || '--sems-color-info'
  return `var(${token})`
}

function formatTime(value) {
  if (!value) return '—'
  const text = String(value).replace('T', ' ')
  return text.length > 16 ? text.slice(0, 16) : text
}

/** 超时是标记不是状态（设计书 3.6）：timeoutFlag 为 0/1 的 tinyint */
function isTimeout(order) {
  return order?.timeoutFlag === 1 || order?.timeoutFlag === true || order?.timeoutFlag === '1'
}

function expressText(order) {
  const parts = [order?.expressCompany, order?.trackingNo].filter(Boolean)
  return parts.length ? parts.join(' / ') : '尚未提交寄件信息'
}

function showActions(order) {
  return [ORDER_STATUS.PENDING_SEND, ORDER_STATUS.IN_USE].includes(order?.status)
}

function goDetail(order) {
  router.push({ name: 'OrderDetail', params: { orderNo: order.orderNo } })
}

function goShipping(order) {
  router.push({ name: 'ShippingForm', params: { orderNo: order.orderNo } })
}

/* ------------------------------ 撤回申领 ------------------------------ */

/** 撤回口径（设计书 3.2）：只取消订单，不改袋状态、不释放在持袋数额度 */
const CANCEL_CONFIRM_TEXT =
  '撤回只取消订单，不释放取袋额度，袋仍在您手上，请尽快投回任一站点回收箱。'

async function confirmCancel(order) {
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
    await cancelBorrow(order.orderNo)
    ElMessage.success('订单已撤回，请尽快将袋投回任一站点回收箱')
    load()
  } catch {
    // 失败提示已由 request.js 按错误码统一弹出
  }
}

/* ------------------------------ 标记归还 ------------------------------ */

const returnDialog = reactive({ visible: false, orderNo: '', stationId: null, submitting: false })
const stations = ref([])
const stationsLoading = ref(false)
const stationsError = ref('')

async function openReturnDialog(order) {
  returnDialog.visible = true
  returnDialog.orderNo = order.orderNo
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
    await markReturn(returnDialog.orderNo, { stationId: returnDialog.stationId })
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

// 断线重连后订阅集合会被清空（stores/websocket.js），鉴权成功时补订阅
watch(
  () => wsStore.authed,
  (authed) => {
    if (authed) subscribeOrderTopic()
  },
)

// 本人订单主题的推送（topic 以 user/ 开头）到达即静默刷新列表
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
  <div class="my-orders">
    <PageCard>
      <el-tabs v-model="activeStatus" @tab-change="onStatusChange">
        <el-tab-pane
          v-for="tab in STATUS_TABS"
          :key="tab.value"
          :label="tab.label"
          :name="tab.value"
        />
      </el-tabs>
    </PageCard>

    <PageSkeleton v-if="loading" :rows="3" type="card" />

    <EmptyState v-else-if="loadError" icon="Warning" :description="loadError">
      <el-button type="primary" @click="load()">重试</el-button>
    </EmptyState>

    <template v-else>
      <EmptyState v-if="!orders.length" :description="emptyText">
        <el-button type="primary" @click="router.push('/m/scan')">去扫码取袋</el-button>
      </EmptyState>

      <div v-else class="order-list">
        <article
          v-for="order in orders"
          :key="order.orderNo"
          class="order-card"
          :style="{ '--order-color': orderColor(order) }"
        >
          <header class="order-card__top">
            <div class="order-card__status">
              <StatusTag kind="order" :value="order.status" size="small" />
              <span v-if="isTimeout(order)" class="order-card__timeout">已超时</span>
            </div>
            <time class="order-card__time">{{ formatTime(order.createdAt) }}</time>
          </header>

          <div
            class="order-card__body"
            role="link"
            tabindex="0"
            @click="goDetail(order)"
            @keydown.enter="goDetail(order)"
          >
            <div class="order-card__line">
              <span class="order-card__bag sems-mono">{{ order.bagCode }}</span>
              <el-icon class="order-card__arrow"><ArrowRight /></el-icon>
            </div>
            <p class="order-card__meta">{{ expressText(order) }}</p>
          </div>

          <footer v-if="showActions(order)" class="order-card__actions">
            <template v-if="order.status === ORDER_STATUS.PENDING_SEND">
              <el-button @click="confirmCancel(order)">撤回申领</el-button>
              <el-button type="primary" @click="goShipping(order)">去寄件</el-button>
            </template>
            <el-button
              v-else-if="order.status === ORDER_STATUS.IN_USE"
              type="primary"
              @click="openReturnDialog(order)"
            >
              标记归还
            </el-button>
          </footer>
        </article>
      </div>

      <DataPager
        v-if="total > 0"
        v-model:page="query.pageNum"
        v-model:size="query.pageSize"
        :total="total"
        :disabled="loading"
        @change="load()"
      />
    </template>

    <!-- 标记归还：选择投放站点 -->
    <el-dialog
      v-model="returnDialog.visible"
      title="标记归还"
      width="min(92vw, 420px)"
      :close-on-click-modal="false"
    >
      <p class="return-tip sems-text-muted">
        请选择您投放回收箱的站点。标记后袋进入「回收在途」，待站点确认后订单完成。
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
.my-orders {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
}

.my-orders :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.order-list {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
}

/* 左侧 4px 语义色条由订单状态令牌驱动（--order-color 由行内样式注入） */
.order-card {
  padding: var(--sems-space-3) var(--sems-space-4);
  background-color: var(--sems-bg-card);
  border: 1px solid var(--sems-border);
  border-left: var(--sems-space-1) solid var(--order-color, var(--sems-color-info));
  border-radius: var(--sems-radius-card);
  box-shadow: var(--sems-shadow-card);
}

.order-card__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-2);
}

.order-card__status {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
}

/* 超时角标：色 + 文案双通道，橙色 warning */
.order-card__timeout {
  padding: 0 var(--sems-space-1);
  border: 1px solid var(--sems-order-timeout);
  border-radius: var(--sems-radius-tag);
  color: var(--sems-order-timeout);
  font-size: var(--sems-font-size-xs);
  line-height: 18px;
  white-space: nowrap;
}

.order-card__time {
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-xs);
}

.order-card__body {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-1);
  cursor: pointer;
}

.order-card__line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
}

.order-card__bag {
  font-size: var(--sems-font-size-lg);
  font-weight: var(--sems-font-weight-medium);
  color: var(--sems-text-primary);
  word-break: break-all;
}

.order-card__arrow {
  color: var(--sems-text-secondary);
}

.order-card__meta {
  margin: 0;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
  word-break: break-all;
}

.order-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--sems-space-2);
  margin-top: var(--sems-space-3);
  padding-top: var(--sems-space-3);
  border-top: 1px solid var(--sems-border);
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
