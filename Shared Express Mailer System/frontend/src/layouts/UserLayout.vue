<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useWebSocketStore } from '@/stores/websocket'
import AppBrand from '@/components/AppBrand.vue'

/** 用户端 H5：窄栏卡片流、移动安全区与独立扫码主入口。 */
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

function goScan() {
  router.push('/m/scan')
}

const showBack = computed(() => route.path !== '/m/home')
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
      <AppBrand v-if="!showBack" small />
      <h1 v-if="showBack" class="user-layout__title">{{ route.meta?.title || '共享快递袋' }}</h1>
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

    <nav class="user-layout__tabs" aria-label="用户端导航">
      <button class="user-layout__scan" type="button" aria-label="扫码取袋" @click="goScan">
        <el-icon :size="24"><FullScreen /></el-icon>
        <span>扫码</span>
      </button>
      <button
        v-for="tab in tabs"
        :key="tab.path"
        type="button"
        class="user-layout__tab"
        :class="{ 'is-active': activeTab === tab.path }"
        :aria-current="activeTab === tab.path ? 'page' : undefined"
        @click="router.push(tab.path)"
      >
        <el-icon :size="21"><component :is="tab.icon" /></el-icon>
        <span>{{ tab.label }}</span>
      </button>
    </nav>
  </div>
</template>

<style scoped>
.user-layout {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: var(--sems-h5-max-width);
  min-height: 100dvh;
  margin: 0 auto;
  background-color: var(--sems-bg-page);
}

.user-layout__header {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  min-height: calc(var(--sems-header-height) + env(safe-area-inset-top));
  padding: env(safe-area-inset-top) var(--sems-h5-gutter) 0;
  background-color: color-mix(in srgb, var(--sems-bg-card) 94%, transparent);
  border-bottom: 1px solid var(--sems-border-soft);
  backdrop-filter: blur(16px);
}

.user-layout__header :deep(.app-brand) {
  flex: 1;
  min-width: 0;
}

.user-layout__back {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: var(--sems-touch-target);
  height: var(--sems-touch-target);
  margin-left: calc(var(--sems-space-2) * -1);
  border: none;
  border-radius: var(--sems-radius-base);
  background: transparent;
  color: var(--sems-text-primary);
  cursor: pointer;
}

.user-layout__back:active,
.user-layout__tab:active {
  background-color: var(--sems-bg-hover);
}

.user-layout__title {
  flex: 1;
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--sems-text-primary);
  font-size: var(--sems-font-size-lg);
  font-weight: var(--sems-font-weight-bold);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-layout__ws {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: var(--sems-space-1);
  min-height: var(--sems-touch-target);
  padding: 0 var(--sems-space-2);
  border-radius: var(--sems-radius-tag);
  background-color: var(--sems-color-info-bg);
  color: var(--sems-color-info);
  font-size: var(--sems-font-size-xs);
  white-space: nowrap;
}

.user-layout__ws.is-on {
  background-color: var(--sems-color-primary-bg);
  color: var(--sems-color-primary);
}

.user-layout__main {
  flex: 1;
  padding: var(--sems-space-3) var(--sems-h5-gutter) calc(108px + env(safe-area-inset-bottom));
}

.user-layout__tabs {
  position: fixed;
  left: 50%;
  bottom: 0;
  z-index: 30;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  width: 100%;
  max-width: var(--sems-h5-max-width);
  padding: 0 var(--sems-space-2) env(safe-area-inset-bottom);
  transform: translateX(-50%);
  background-color: color-mix(in srgb, var(--sems-bg-card) 96%, transparent);
  border-top: 1px solid var(--sems-border-soft);
  box-shadow: var(--sems-shadow-card);
  backdrop-filter: blur(18px);
}

.user-layout__tab {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  align-self: stretch;
  gap: var(--sems-space-1);
  min-width: 0;
  min-height: 68px;
  padding: var(--sems-space-2) 0;
  border: none;
  border-radius: var(--sems-radius-base);
  background: transparent;
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-xs);
  cursor: pointer;
  transition: color var(--sems-duration-fast), background-color var(--sems-duration-fast);
}

.user-layout__tab:nth-of-type(2) { grid-column: 1; }
.user-layout__tab:nth-of-type(3) { grid-column: 2; }
.user-layout__tab:nth-of-type(4) { grid-column: 4; }

.user-layout__tab.is-active {
  color: var(--sems-color-primary);
  font-weight: var(--sems-font-weight-bold);
}

.user-layout__scan {
  position: relative;
  grid-column: 3;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  align-self: center;
  gap: var(--sems-space-1);
  width: 64px;
  height: 64px;
  margin: 0 auto;
  padding: 0;
  transform: translateY(calc(var(--sems-space-3) * -1));
  border: var(--sems-space-1) solid var(--sems-bg-page);
  border-radius: 50%;
  background: var(--sems-bg-button);
  color: var(--sems-text-inverse);
  box-shadow: var(--sems-shadow-button);
  font-size: var(--sems-font-size-xs);
  cursor: pointer;
  transition: transform var(--sems-duration-fast) var(--sems-ease);
}

.user-layout__scan:active {
  transform: translateY(calc(var(--sems-space-2) * -1)) scale(0.96);
}

@media (min-width: 768px) {
  .user-layout {
    border-right: 1px solid var(--sems-border-soft);
    border-left: 1px solid var(--sems-border-soft);
  }
}
</style>
