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
      <p class="sems-eyebrow">绿色循环 · 从一只袋开始</p>
      <h2 class="home__hello">{{ greeting }}，{{ nickname }}</h2>
      <p class="home__slogan sems-text-muted">今天也让快递包装，多一次循环。</p>
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
      <section class="home-hero">
        <div class="home-hero__top">
          <div class="home-held">
            <p class="home-hero__eyebrow">我的在持额度</p>
            <p class="home-held__metric">
              <span class="home-held__value">{{ heldCount }}</span>
              <span class="home-held__limit">/ {{ OPEN_LIMIT }} 只</span>
            </p>
          </div>
          <span class="home-hero__icon" aria-hidden="true"><el-icon :size="30"><Box /></el-icon></span>
        </div>
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
        <div class="home-hero__footer">
          <p v-if="atLimit" class="home-held__tip">
            <el-icon><WarningFilled /></el-icon>
            <span>已到在持上限，归还闭环后释放额度</span>
          </p>
          <p v-else class="home-held__tip">还可申领 {{ OPEN_LIMIT - heldCount }} 只，轻装出发</p>
          <button class="home-scan" type="button" @click="router.push('/m/scan')">
            <el-icon><FullScreen /></el-icon>
            <span>扫码取袋</span>
            <el-icon><ArrowRight /></el-icon>
          </button>
        </div>
      </section>

      <PageCard title="附近站点" :subtitle="`共 ${stations.length} 个开放站点`">
        <EmptyState v-if="!stations.length" description="附近暂未开放站点">
          <el-button type="primary" @click="load">刷新</el-button>
        </EmptyState>
        <ul v-else class="home-stations">
          <li v-for="station in stations" :key="station.id" class="home-station">
            <span class="home-station__marker" :class="{ 'is-empty': station.availableStock <= 0 }" aria-hidden="true">
              <el-icon :size="21"><Location /></el-icon>
            </span>
            <div class="home-station__body">
              <div class="home-station__top">
                <span class="home-station__name">{{ station.name }}</span>
                <span v-if="station.availableStock > 0" class="home-station__stock">
                  <span class="home-station__stock-value">{{ station.availableStock }}</span>
                  <span>只可用</span>
                </span>
                <span v-else class="home-station__stock is-none">暂无可用袋</span>
              </div>
              <p class="home-station__line">{{ station.address || '地址待完善' }}</p>
              <p class="home-station__line">
                <el-icon><Clock /></el-icon>
                <span>{{ station.openTime || '开放时段以站点公告为准' }}</span>
              </p>
            </div>
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
              <span class="home-order__icon" aria-hidden="true"><el-icon><Box /></el-icon></span>
              <span class="home-order__content">
                <span class="home-order__top">
                  <span class="home-order__bag sems-mono">{{ order.bagCode }}</span>
                  <el-icon class="home-order__arrow"><ArrowRight /></el-icon>
                </span>
                <span class="home-order__bottom">
                  <StatusTag kind="order" :value="order.status" size="small" />
                  <span class="sems-text-muted">{{ formatShortTime(order.createdAt) }}</span>
                </span>
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

.home :deep(.page-card) {
  border: 1px solid var(--sems-border-soft);
}

.home__greeting {
  padding: var(--sems-space-2) var(--sems-space-1) var(--sems-space-1);
}

.home__greeting .sems-eyebrow {
  margin-bottom: var(--sems-space-2);
}

.home__hello {
  margin: 0 0 var(--sems-space-2);
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

/* ---------- 在持额度与扫码主卡 ---------- */
.home-hero {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-4);
  padding: var(--sems-space-5);
  overflow: hidden;
  border-radius: var(--sems-radius-card);
  background: var(--sems-bg-hero);
  box-shadow: var(--sems-shadow-button);
  color: var(--sems-text-inverse);
}

.home-hero__top,
.home-hero__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-3);
}

.home-hero__eyebrow {
  margin: 0 0 var(--sems-space-2);
  color: var(--sems-text-on-brand-soft);
  font-size: var(--sems-font-size-sm);
}

.home-hero__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: var(--sems-btn-height-touch);
  height: var(--sems-btn-height-touch);
  border-radius: var(--sems-radius-card);
  background-color: color-mix(in srgb, var(--sems-text-inverse) 10%, transparent);
  color: var(--sems-text-inverse);
}

.home-held__metric {
  display: flex;
  align-items: baseline;
  gap: var(--sems-space-2);
  margin: 0;
}

.home-held__value {
  color: var(--sems-text-inverse);
  font-size: var(--sems-font-size-display);
  font-weight: var(--sems-font-weight-bold);
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.home-held__limit {
  color: var(--sems-text-on-brand-soft);
  font-size: var(--sems-font-size-base);
  font-variant-numeric: tabular-nums;
}

.home-held__bar {
  height: var(--sems-space-2);
  overflow: hidden;
  border-radius: var(--sems-radius-tag);
  background-color: color-mix(in srgb, var(--sems-text-inverse) 16%, transparent);
}

.home-held__bar-fill {
  display: block;
  height: 100%;
  border-radius: var(--sems-radius-tag);
  background-color: var(--sems-text-inverse);
  transition: width var(--sems-duration-base) var(--sems-ease);
}

.home-held__bar-fill.is-warning {
  background-color: var(--sems-color-accent-soft);
}

.home-held__tip {
  display: flex;
  align-items: center;
  gap: var(--sems-space-1);
  margin: 0;
  color: var(--sems-text-on-brand-soft);
  font-size: var(--sems-font-size-xs);
}

.home-scan {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  gap: var(--sems-space-2);
  min-height: var(--sems-touch-target);
  padding: 0 var(--sems-space-4);
  border: none;
  border-radius: var(--sems-radius-base);
  background-color: var(--sems-bg-card);
  color: var(--sems-color-primary-deep);
  font-size: var(--sems-font-size-sm);
  font-weight: var(--sems-font-weight-bold);
  cursor: pointer;
  transition: transform var(--sems-duration-fast) var(--sems-ease);
}

.home-scan:active {
  transform: scale(0.97);
}

@media (max-width: 380px) {
  .home-hero__footer {
    align-items: stretch;
    flex-direction: column;
  }

  .home-scan {
    width: 100%;
  }
}

/* ---------- 附近站点 ---------- */
.home-stations {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.home-station {
  display: flex;
  align-items: flex-start;
  gap: var(--sems-space-3);
  padding: var(--sems-space-3);
  border: 1px solid var(--sems-border-soft);
  border-radius: var(--sems-radius-base);
  background-color: var(--sems-bg-soft);
}

.home-station__marker {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: var(--sems-touch-target);
  height: var(--sems-touch-target);
  border-radius: var(--sems-radius-base);
  background-color: var(--sems-color-primary-bg);
  color: var(--sems-color-primary);
}

.home-station__marker.is-empty {
  background-color: var(--sems-color-warning-bg);
  color: var(--sems-color-warning);
}

.home-station__body {
  flex: 1;
  min-width: 0;
}

.home-station__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  min-height: var(--sems-space-5);
}

.home-station__name {
  min-width: 0;
  color: var(--sems-text-primary);
  font-size: var(--sems-font-size-base);
  font-weight: var(--sems-font-weight-bold);
}

.home-station__stock {
  display: inline-flex;
  flex: none;
  align-items: baseline;
  gap: var(--sems-space-1);
  padding: var(--sems-space-1) var(--sems-space-2);
  border-radius: var(--sems-radius-tag);
  background-color: var(--sems-color-primary-bg);
  color: var(--sems-color-primary);
  font-size: var(--sems-font-size-xs);
  font-weight: var(--sems-font-weight-medium);
}

.home-station__stock-value {
  font-size: var(--sems-font-size-lg);
  font-weight: var(--sems-font-weight-bold);
  font-variant-numeric: tabular-nums;
}

.home-station__stock.is-none {
  background-color: var(--sems-color-warning-bg);
  color: var(--sems-color-warning);
}

.home-station__line {
  display: flex;
  align-items: center;
  gap: var(--sems-space-1);
  margin: var(--sems-space-1) 0 0;
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-xs);
  line-height: 1.5;
}

/* ---------- 进行中订单 ---------- */
.home-orders {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.home-order {
  display: flex;
  align-items: center;
  gap: var(--sems-space-3);
  width: 100%;
  min-height: 64px;
  padding: var(--sems-space-3);
  border: 1px solid var(--sems-border-soft);
  border-radius: var(--sems-radius-base);
  background-color: var(--sems-bg-card);
  text-align: left;
  cursor: pointer;
  transition: background-color var(--sems-duration-fast), border-color var(--sems-duration-fast);
}

.home-order:active {
  border-color: var(--sems-border-strong);
  background-color: var(--sems-bg-soft);
}

.home-order__icon {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: var(--sems-touch-target);
  height: var(--sems-touch-target);
  border-radius: var(--sems-radius-base);
  background-color: var(--sems-color-primary-bg);
  color: var(--sems-color-primary);
  font-size: var(--sems-font-size-xl);
}

.home-order__content {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: var(--sems-space-2);
  min-width: 0;
}

.home-order__top,
.home-order__bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
}

.home-order__bag {
  min-width: 0;
  overflow: hidden;
  color: var(--sems-text-primary);
  font-size: var(--sems-font-size-sm);
  font-weight: var(--sems-font-weight-medium);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-order__arrow {
  color: var(--sems-color-primary);
}
</style>
