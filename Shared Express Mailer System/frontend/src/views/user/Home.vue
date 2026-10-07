<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listMyOrders } from '@/api/order'
import { listNearbyStations } from '@/api/station'
import { useUserStore } from '@/stores/user'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusTag from '@/components/StatusTag.vue'

/**
 * 用户端首页（《系统设计说明书》5.2 M2、10.2 页面清单）。
 *
 * 三块内容：
 *  1) 问候 + 在持袋数：在持袋数 = 用户绑定的袋处于「已分配 / 使用中」的订单数，
 *     恰好对应订单三态 PENDING_SEND / IN_USE / PENDING_CONFIRM，取三类的分页 total 求和
 *     （设计书 5.2：在持袋数上限 3；撤回与超时取消不释放额度）；
 *  2) 附近站点（listNearbyStations）：名称 / 地址 / 开放时段 / 可用库存，库存为 0 标注「暂无可用袋」；
 *  3) 进行中订单快捷入口：取前 3 条非终态订单，点击进入订单详情。
 *
 * 三态落实：加载 = 骨架屏（占位与真实卡片一致）、空态 = EmptyState、错误态 = 「重试」按钮。
 */

const router = useRouter()
const userStore = useUserStore()

/** 在持袋数上限（设计书 5.2：同一用户在持袋数不得超过 3） */
const OPEN_LIMIT = 3
/** 在持袋对应状态（设计书 3.5：袋处于已分配/使用中时计数） */
const HELD_STATUSES = ['PENDING_SEND', 'IN_USE', 'PENDING_CONFIRM']
/** 首页只取前 3 条进行中订单，同时用各状态分页 total 求在持袋数 */
const PREVIEW_SIZE = 3

const loading = ref(true)
const loadError = ref('')
const heldCount = ref(0)
const ongoingOrders = ref([])
const stations = ref([])

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const nickname = computed(() => userStore.user?.nickname || '同学')

/** 达到上限：进度条转 warning 色并给出文字提示（状态不靠颜色单独承载） */
const atLimit = computed(() => heldCount.value >= OPEN_LIMIT)
const heldPercent = computed(() => Math.min(100, Math.round((heldCount.value / OPEN_LIMIT) * 100)))

/** 后端时间统一为 yyyy-MM-dd HH:mm:ss（设计书 6.1），截取「月-日 时:分」展示即可 */
function formatShortTime(value) {
  return value ? String(value).slice(5, 16) : '—'
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [orderPages, stationData] = await Promise.all([
      Promise.all(
        HELD_STATUSES.map((status) =>
          listMyOrders({ status, pageNum: 1, pageSize: PREVIEW_SIZE }),
        ),
      ),
      listNearbyStations(),
    ])
    heldCount.value = orderPages.reduce((sum, page) => sum + (page?.total || 0), 0)
    ongoingOrders.value = orderPages
      .flatMap((page) => page?.records || [])
      .sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || '')))
      .slice(0, PREVIEW_SIZE)
    stations.value = Array.isArray(stationData) ? stationData : []
  } catch (error) {
    loadError.value = error?.message || '加载失败，请检查网络后重试'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="home">
    <header class="home__greeting">
      <h2 class="home__hello">{{ greeting }}，{{ nickname }}</h2>
      <p class="home__slogan sems-text-muted">扫码取袋、循环使用，让快递袋留在循环里</p>
    </header>

    <PageCard v-if="loadError" title="加载失败">
      <div class="home__error">
        <el-icon :size="40" class="home__error-icon"><WarningFilled /></el-icon>
        <p>{{ loadError }}</p>
        <el-button type="primary" @click="load">重试</el-button>
      </div>
    </PageCard>

    <template v-else-if="loading">
      <PageCard title="在持袋数">
        <PageSkeleton type="card" :rows="2" />
      </PageCard>
      <PageCard title="附近站点">
        <PageSkeleton type="card" :rows="3" />
      </PageCard>
      <PageCard title="进行中订单">
        <PageSkeleton type="card" :rows="3" />
      </PageCard>
    </template>

    <template v-else>
      <PageCard title="在持袋数" subtitle="归还闭环完成后释放额度">
        <div class="home-held">
          <p class="home-held__metric">
            <span class="home-held__value">{{ heldCount }}</span>
            <span class="home-held__limit">/ {{ OPEN_LIMIT }}</span>
          </p>
          <div
            class="home-held__bar"
            role="progressbar"
            :aria-valuenow="heldCount"
            aria-valuemin="0"
            :aria-valuemax="OPEN_LIMIT"
          >
            <span
              class="home-held__bar-fill"
              :class="{ 'is-warning': atLimit }"
              :style="{ width: `${heldPercent}%` }"
            />
          </div>
          <p v-if="atLimit" class="home-held__tip is-warning">
            <el-icon><WarningFilled /></el-icon>
            <span>已到在持上限，请先归还手上的袋</span>
          </p>
          <p v-else class="home-held__tip sems-text-muted">还可申领 {{ OPEN_LIMIT - heldCount }} 只</p>
        </div>
      </PageCard>

      <PageCard title="附近站点" :subtitle="`共 ${stations.length} 个开放站点`">
        <EmptyState v-if="!stations.length" description="附近暂未开放站点">
          <el-button type="primary" @click="load">刷新</el-button>
        </EmptyState>
        <ul v-else class="home-stations">
          <li v-for="station in stations" :key="station.id" class="home-station">
            <div class="home-station__top">
              <span class="home-station__name">{{ station.name }}</span>
              <span v-if="station.availableStock > 0" class="home-station__stock">
                可用 {{ station.availableStock }} 只
              </span>
              <span v-else class="home-station__stock is-none">暂无可用袋</span>
            </div>
            <p class="home-station__line">
              <el-icon><Location /></el-icon>
              <span>{{ station.address || '地址待完善' }}</span>
            </p>
            <p class="home-station__line">
              <el-icon><Clock /></el-icon>
              <span>开放时段：{{ station.openTime || '以站点公告为准' }}</span>
            </p>
          </li>
        </ul>
      </PageCard>

      <PageCard title="进行中订单">
        <template #extra>
          <el-button link type="primary" @click="router.push('/m/orders')">全部订单</el-button>
        </template>
        <EmptyState v-if="!ongoingOrders.length" description="暂无进行中的订单">
          <el-button type="primary" @click="router.push('/m/scan')">去扫码取袋</el-button>
        </EmptyState>
        <ul v-else class="home-orders">
          <li v-for="order in ongoingOrders" :key="order.orderNo">
            <button
              type="button"
              class="home-order"
              @click="router.push(`/m/orders/${order.orderNo}`)"
            >
              <span class="home-order__top">
                <StatusTag kind="order" :value="order.status" size="small" />
                <span class="sems-text-muted">{{ formatShortTime(order.createdAt) }}</span>
              </span>
              <span class="home-order__bottom">
                <span class="home-order__bag sems-mono">{{ order.bagCode }}</span>
                <el-icon class="home-order__arrow"><ArrowRight /></el-icon>
              </span>
            </button>
          </li>
        </ul>
      </PageCard>
    </template>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-4);
}

.home__greeting {
  padding: var(--sems-space-1) var(--sems-space-1) 0;
}

.home__hello {
  margin: 0 0 var(--sems-space-1);
  font-size: var(--sems-font-size-xl);
  font-weight: var(--sems-font-weight-bold);
  color: var(--sems-text-primary);
}

.home__slogan {
  margin: 0;
}

.home__error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-3);
  padding: var(--sems-space-5) 0;
  text-align: center;
  color: var(--sems-text-secondary);
}

.home__error p {
  margin: 0;
}

.home__error-icon {
  color: var(--sems-color-warning);
}

/* ---------- 在持袋数 ---------- */
.home-held {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
}

.home-held__metric {
  display: flex;
  align-items: baseline;
  gap: var(--sems-space-2);
  margin: 0;
}

.home-held__value {
  font-size: var(--sems-font-size-kpi);
  font-weight: var(--sems-font-weight-bold);
  line-height: 1;
  color: var(--sems-text-primary);
  font-variant-numeric: tabular-nums;
}

.home-held__limit {
  font-size: var(--sems-font-size-lg);
  color: var(--sems-text-secondary);
  font-variant-numeric: tabular-nums;
}

.home-held__bar {
  height: 10px;
  overflow: hidden;
  border-radius: var(--sems-radius-tag);
  background-color: var(--sems-bg-hover);
}

.home-held__bar-fill {
  display: block;
  height: 100%;
  border-radius: var(--sems-radius-tag);
  background-color: var(--sems-color-primary);
  transition: width var(--sems-duration-base);
}

.home-held__bar-fill.is-warning {
  background-color: var(--sems-color-warning);
}

.home-held__tip {
  display: flex;
  align-items: center;
  gap: var(--sems-space-1);
  margin: 0;
  font-size: var(--sems-font-size-sm);
}

.home-held__tip.is-warning {
  color: var(--sems-color-warning);
}

/* ---------- 附近站点 ---------- */
.home-stations {
  margin: 0;
  padding: 0;
  list-style: none;
}

.home-station {
  padding: var(--sems-space-3) 0;
  border-bottom: 1px solid var(--sems-border);
}

.home-station:first-child {
  padding-top: 0;
}

.home-station:last-child {
  padding-bottom: 0;
  border-bottom: none;
}

.home-station__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
}

.home-station__name {
  font-size: var(--sems-font-size-lg);
  font-weight: var(--sems-font-weight-medium);
  color: var(--sems-text-primary);
}

.home-station__stock {
  flex: none;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-color-success);
}

.home-station__stock.is-none {
  color: var(--sems-color-warning);
}

.home-station__line {
  display: flex;
  align-items: center;
  gap: var(--sems-space-1);
  margin: var(--sems-space-1) 0 0;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
}

/* ---------- 进行中订单 ---------- */
.home-orders {
  margin: 0;
  padding: 0;
  list-style: none;
}

.home-order {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
  width: 100%;
  min-height: var(--sems-touch-target);
  padding: var(--sems-space-3) 0;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.home-orders li + li .home-order {
  border-top: 1px solid var(--sems-border);
}

.home-order__top,
.home-order__bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
}

.home-order__bag {
  font-size: var(--sems-font-size-base);
  color: var(--sems-text-primary);
}

.home-order__arrow {
  color: var(--sems-color-primary);
}
</style>
