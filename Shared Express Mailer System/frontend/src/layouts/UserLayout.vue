<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useWebSocketStore } from '@/stores/websocket'

/**
 * 用户端布局（移动端优先，见《系统设计说明书》10.5 版面与交互要点）。
 *
 *  - 单列卡片流，页边距 16px；
 *  - 底部 Tab + 悬浮扫码主按钮（热区 ≥44px）；
 *  - >768px 时居中为 480px 窄栏（桌面浏览器打开也能看）；
 *  - 底部固定元素加 env(safe-area-inset-bottom)，容器高度用 100dvh（避免移动端浏览器地址栏抖动）。
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const wsStore = useWebSocketStore()

const tabs = [
  { path: '/m/home', label: '首页', icon: 'HomeFilled' },
  { path: '/m/orders', label: '我的订单', icon: 'Tickets' },
  { path: '/m/profile', label: '个人中心', icon: 'User' },
]

const activeTab = computed(() => {
  const path = route.path
  if (path.startsWith('/m/orders')) return '/m/orders'
  if (path.startsWith('/m/profile')) return '/m/profile'
  return '/m/home'
})

/** 扫码是全站主操作，独立于 Tab 栏做成悬浮按钮（10.5） */
function goScan() {
  router.push('/m/scan')
}

const showBack = computed(() => route.path !== '/m/home')

/**
 * WebSocket 状态：色 + 图标 + 短文案三通道，禁止仅靠颜色区分状态（10.5 禁止事项 3）。
 * 完整状态文案（wsStore.statusText）保留在 title 提示里；短文案用于窄屏头部。
 */
const wsConnected = computed(() => wsStore.connected && wsStore.authed)
const wsText = computed(() => (wsConnected.value ? '已连接' : wsStore.connected ? '连接中' : '未连接'))
const wsIcon = computed(() => (wsConnected.value ? 'CircleCheckFilled' : wsStore.connected ? 'Loading' : 'CircleCloseFilled'))
</script>

<template>
  <div class="user-layout">
    <header class="user-layout__header">
      <button v-if="showBack" class="user-layout__back" type="button" aria-label="返回" @click="router.back()">
        <el-icon><ArrowLeft /></el-icon>
      </button>
      <h1 class="user-layout__title">{{ route.meta?.title || '共享快递袋' }}</h1>
      <span
        v-if="userStore.isLogin"
        class="user-layout__ws"
        :title="wsStore.statusText"
        :class="{ 'is-on': wsConnected }"
      >
        <el-icon><component :is="wsIcon" /></el-icon>
        <span>{{ wsText }}</span>
      </span>
    </header>

    <main class="user-layout__main">
      <router-view />
    </main>

    <button v-if="activeTab === '/m/home'" class="user-layout__scan" type="button" @click="goScan">
      <el-icon :size="24"><FullScreen /></el-icon>
      <span>扫码取袋</span>
    </button>

    <nav class="user-layout__tabs">
      <button
        v-for="tab in tabs"
        :key="tab.path"
        type="button"
        class="user-layout__tab"
        :class="{ 'is-active': activeTab === tab.path }"
        @click="router.push(tab.path)"
      >
        <el-icon :size="20"><component :is="tab.icon" /></el-icon>
        <span>{{ tab.label }}</span>
      </button>
    </nav>
  </div>
</template>

<style scoped>
.user-layout {
  display: flex;
  flex-direction: column;
  /* dvh 而非 vh：移动端浏览器地址栏收起/展开时不跳版（10.5） */
  min-height: 100dvh;
  background-color: var(--sems-bg-page);
  /* >768px 居中窄栏：桌面打开 H5 不会拉成一条长线 */
  max-width: var(--sems-h5-max-width);
  margin: 0 auto;
  position: relative;
}

.user-layout__header {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  height: var(--sems-header-height);
  padding: 0 var(--sems-h5-gutter);
  background-color: var(--sems-bg-card);
  border-bottom: 1px solid var(--sems-border);
}

.user-layout__back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: var(--sems-touch-target);
  height: var(--sems-touch-target);
  margin-left: calc(var(--sems-space-2) * -1);
  border: none;
  background: transparent;
  color: var(--sems-text-primary);
  cursor: pointer;
}

.user-layout__title {
  flex: 1;
  margin: 0;
  font-size: var(--sems-font-size-lg);
  font-weight: var(--sems-font-weight-bold);
  color: var(--sems-text-primary);
}

/* WebSocket 状态指示：图标 + 文案 + 颜色三通道（10.5 禁止事项 3，禁止仅靠颜色区分状态；
   连不上不影响业务，页面数据本来就有兜底拉取，这里只做提示） */
.user-layout__ws {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-1);
  color: var(--sems-color-info);
  font-size: var(--sems-font-size-xs);
  white-space: nowrap;
}
.user-layout__ws.is-on {
  color: var(--sems-color-success);
}

.user-layout__main {
  flex: 1;
  padding: var(--sems-h5-gutter);
  /* 给底部 Tab 与悬浮按钮留出空间 */
  padding-bottom: calc(96px + env(safe-area-inset-bottom));
}

.user-layout__scan {
  position: fixed;
  left: 50%;
  bottom: calc(72px + env(safe-area-inset-bottom));
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  height: var(--sems-btn-height-touch);
  padding: 0 var(--sems-space-5);
  border: none;
  border-radius: 24px;
  background-color: var(--sems-color-primary);
  color: var(--sems-text-inverse);
  font-size: var(--sems-font-size-lg);
  font-weight: var(--sems-font-weight-bold);
  box-shadow: var(--sems-shadow-popup);
  cursor: pointer;
  z-index: 20;
}
.user-layout__scan:active {
  opacity: 0.85;
}

.user-layout__tabs {
  position: fixed;
  left: 50%;
  bottom: 0;
  transform: translateX(-50%);
  width: 100%;
  max-width: var(--sems-h5-max-width);
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  background-color: var(--sems-bg-card);
  border-top: 1px solid var(--sems-border);
  padding-bottom: env(safe-area-inset-bottom);
  z-index: 30;
}

.user-layout__tab {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-height: var(--sems-touch-target);
  padding: var(--sems-space-2) 0;
  border: none;
  background: transparent;
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-xs);
  cursor: pointer;
}
/* 可点击元素一律用主色蓝，不用 info 灰（10.5 禁止事项 6） */
.user-layout__tab.is-active {
  color: var(--sems-color-primary);
  font-weight: var(--sems-font-weight-medium);
}
</style>
