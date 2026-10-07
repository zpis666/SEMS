<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useWebSocketStore } from '@/stores/websocket'
import { ROLE_LABEL } from '@/constants'

/**
 * 管理端布局（站点端与平台端共用，见《系统设计说明书》10.5）。
 *
 *  - 240px 深色（#1E293B）侧边栏 + 白卡内容区；
 *  - >1200px 常驻，768–1200px 收为 64px 图标条；
 *  - <768px 侧边栏转底部导航（移动端站点管理员在工位用手持设备操作）；
 *  - 菜单项按当前路由所属 scope 取：站点端与平台端各一套，不混用。
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const wsStore = useWebSocketStore()

/** 侧边栏折叠状态：自动跟随窗口宽度，也可手动切换 */
const collapsed = ref(false)
const isMobile = ref(false)

const scope = computed(() => route.matched.find((r) => r.meta?.scope)?.meta.scope || 'station')

const STATION_MENUS = [
  { path: '/station/workbench', label: '工作台', icon: 'Odometer' },
  { path: '/station/recycle', label: '回收确认', icon: 'RefreshLeft' },
  { path: '/station/stock', label: '实物出库', icon: 'Box' },
  { path: '/station/disinfect', label: '消毒登记', icon: 'MagicStick' },
  { path: '/station/repair', label: '维修报废', icon: 'Tools' },
  { path: '/station/inventory', label: '库存盘点', icon: 'DocumentChecked' },
  { path: '/station/allocation', label: '调拨出库 / 核收', icon: 'Van' },
  { path: '/station/bags', label: '本站袋台账', icon: 'Grid' },
]

const PLATFORM_MENUS = [
  { path: '/platform/dashboard', label: '数据看板', icon: 'DataLine' },
  { path: '/platform/users', label: '用户管理', icon: 'UserFilled' },
  { path: '/platform/stations', label: '站点管理', icon: 'OfficeBuilding' },
  { path: '/platform/bags', label: '快递袋台账', icon: 'Grid' },
  { path: '/platform/allocations', label: '调拨管理', icon: 'Van' },
  { path: '/platform/orders', label: '订单管理', icon: 'Tickets' },
  { path: '/platform/alerts', label: '异常与风控', icon: 'Warning' },
  { path: '/platform/audit-logs', label: '操作审计', icon: 'Notebook' },
  { path: '/platform/login-logs', label: '登录日志', icon: 'Key' },
]

const menus = computed(() => (scope.value === 'platform' ? PLATFORM_MENUS : STATION_MENUS))

const activePath = computed(() => {
  // 袋详情等子页高亮到所属列表项
  const found = menus.value.find((m) => route.path === m.path || route.path.startsWith(`${m.path}/`))
  if (found) return found.path
  const prefix = scope.value === 'platform' ? '/platform/bags' : '/station/bags'
  return route.path.startsWith(prefix) ? prefix : route.path
})

const roleLabel = computed(() => ROLE_LABEL[userStore.role] || userStore.role)

function syncLayout() {
  const width = window.innerWidth
  isMobile.value = width < 768
  if (width >= 768 && width < 1200) {
    collapsed.value = true
  } else if (width >= 1200) {
    collapsed.value = false
  }
}

onMounted(() => {
  syncLayout()
  window.addEventListener('resize', syncLayout)
})
onUnmounted(() => window.removeEventListener('resize', syncLayout))

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确认退出登录？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  await userStore.logout()
  router.push('/login')
}

function goProfile() {
  router.push(`/${scope.value}/profile`)
}

function handleUserCommand(command) {
  if (command === 'profile') goProfile()
  else if (command === 'logout') handleLogout()
}
</script>

<template>
  <div class="admin-layout" :class="{ 'is-collapsed': collapsed, 'is-mobile': isMobile }">
    <!-- 移动端：侧边栏转底部导航 -->
    <aside v-if="!isMobile" class="admin-layout__sidebar">
      <div class="admin-layout__brand">
        <span class="admin-layout__brand-mark">SEMS</span>
        <span v-if="!collapsed" class="admin-layout__brand-text">共享快递袋系统</span>
      </div>
      <nav class="admin-layout__menu">
        <router-link
          v-for="item in menus"
          :key="item.path"
          :to="item.path"
          class="admin-layout__menu-item"
          :class="{ 'is-active': activePath === item.path }"
          :title="collapsed ? item.label : ''"
        >
          <el-icon :size="18"><component :is="item.icon" /></el-icon>
          <span v-if="!collapsed">{{ item.label }}</span>
        </router-link>
      </nav>
      <button class="admin-layout__collapse" type="button" @click="collapsed = !collapsed">
        <el-icon><component :is="collapsed ? 'Expand' : 'Fold'" /></el-icon>
      </button>
    </aside>

    <div class="admin-layout__body">
      <header class="admin-layout__header">
        <h1 class="admin-layout__title">{{ route.meta?.title || '' }}</h1>
        <div class="admin-layout__actions">
          <span
            class="admin-layout__ws"
            :class="{ 'is-on': wsStore.connected && wsStore.authed }"
            :title="wsStore.statusText"
          >
            <el-icon><Connection /></el-icon>
            <span class="admin-layout__ws-text">{{ wsStore.statusText }}</span>
          </span>
          <el-dropdown trigger="click" @command="handleUserCommand">
            <span class="admin-layout__user">
              <el-icon><UserFilled /></el-icon>
              <span>{{ userStore.user?.nickname || '未登录' }}</span>
              <el-tag size="small" type="info">{{ roleLabel }}</el-tag>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="admin-layout__main">
        <router-view />
      </main>

      <nav v-if="isMobile" class="admin-layout__bottom-nav">
        <router-link
          v-for="item in menus.slice(0, 5)"
          :key="item.path"
          :to="item.path"
          class="admin-layout__bottom-item"
          :class="{ 'is-active': activePath === item.path }"
        >
          <el-icon :size="18"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>
    </div>
  </div>
</template>

<style scoped>
.admin-layout {
  display: flex;
  min-height: 100dvh;
  background-color: var(--sems-bg-page);
}

/* ---------------- 侧边栏（全站唯一的深色区，10.5） ---------------- */
.admin-layout__sidebar {
  display: flex;
  flex-direction: column;
  width: var(--sems-sidebar-width);
  flex: none;
  background-color: var(--sems-bg-sidebar);
  transition: width var(--sems-duration-base);
}
.admin-layout.is-collapsed .admin-layout__sidebar {
  width: var(--sems-sidebar-width-collapsed);
}

.admin-layout__brand {
  display: flex;
  align-items: center;
  gap: var(--sems-space-3);
  height: var(--sems-header-height);
  padding: 0 var(--sems-space-4);
  color: var(--sems-text-inverse);
  overflow: hidden;
}
.admin-layout__brand-mark {
  font-weight: var(--sems-font-weight-bold);
  letter-spacing: 0.08em;
  font-size: var(--sems-font-size-lg);
  flex: none;
}
.admin-layout__brand-text {
  font-size: var(--sems-font-size-sm);
  white-space: nowrap;
  opacity: 0.85;
}

.admin-layout__menu {
  flex: 1;
  padding: var(--sems-space-2) 0;
  overflow-y: auto;
}
.admin-layout__menu-item {
  display: flex;
  align-items: center;
  gap: var(--sems-space-3);
  height: 44px;
  padding: 0 var(--sems-space-4);
  color: color-mix(in srgb, var(--sems-text-inverse) 72%, transparent);
  text-decoration: none;
  font-size: var(--sems-font-size-base);
  white-space: nowrap;
  overflow: hidden;
}
.admin-layout__menu-item:hover {
  background-color: color-mix(in srgb, var(--sems-text-inverse) 8%, transparent);
  color: var(--sems-text-inverse);
}
.admin-layout__menu-item.is-active {
  background-color: color-mix(in srgb, var(--sems-text-inverse) 12%, transparent);
  color: var(--sems-text-inverse);
  box-shadow: inset 3px 0 0 var(--sems-color-primary);
  font-weight: var(--sems-font-weight-medium);
}

.admin-layout__collapse {
  height: 40px;
  border: none;
  border-top: 1px solid color-mix(in srgb, var(--sems-text-inverse) 12%, transparent);
  background: transparent;
  color: color-mix(in srgb, var(--sems-text-inverse) 72%, transparent);
  cursor: pointer;
}

/* ---------------- 内容区 ---------------- */
.admin-layout__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.admin-layout__header {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-4);
  height: var(--sems-header-height);
  padding: 0 var(--sems-space-5);
  background-color: var(--sems-bg-card);
  border-bottom: 1px solid var(--sems-border);
}
.admin-layout__title {
  margin: 0;
  font-size: var(--sems-font-size-lg);
  font-weight: var(--sems-font-weight-bold);
  color: var(--sems-text-primary);
}
.admin-layout__actions {
  display: flex;
  align-items: center;
  gap: var(--sems-space-4);
}
.admin-layout__ws {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-1);
  font-size: var(--sems-font-size-xs);
  color: var(--sems-text-secondary);
}
.admin-layout__ws.is-on {
  color: var(--sems-color-success);
}
.admin-layout__user {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-2);
  cursor: pointer;
  color: var(--sems-text-regular);
  font-size: var(--sems-font-size-base);
  outline: none;
}

.admin-layout__main {
  flex: 1;
  padding: var(--sems-space-5);
}

/* ---------------- 移动端底部导航 ---------------- */
.admin-layout__bottom-nav {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  background-color: var(--sems-bg-card);
  border-top: 1px solid var(--sems-border);
  padding-bottom: env(safe-area-inset-bottom);
  z-index: 30;
}
.admin-layout__bottom-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: var(--sems-space-2) 0;
  font-size: var(--sems-font-size-xs);
  color: var(--sems-text-secondary);
  text-decoration: none;
}
.admin-layout__bottom-item.is-active {
  color: var(--sems-color-primary);
}

@media (max-width: 767px) {
  .admin-layout__header {
    padding: 0 var(--sems-space-4);
  }
  .admin-layout__main {
    padding: var(--sems-space-4);
    padding-bottom: calc(72px + env(safe-area-inset-bottom));
  }
  /* 小屏隐藏文字状态，避免头部拥挤 */
  .admin-layout__ws-text {
    display: none;
  }
}
</style>
