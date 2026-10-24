<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import AppBrand from '@/components/AppBrand.vue'
import { useUserStore } from '@/stores/user'
import { useWebSocketStore } from '@/stores/websocket'
import { ROLE_LABEL } from '@/constants'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const wsStore = useWebSocketStore()
const collapsed = ref(false)
const isMobile = ref(false)
const mobileMenuOpen = ref(false)
const scope = computed(() => route.matched.find((r) => r.meta?.scope)?.meta.scope || 'station')

const STATION_MENUS = [
  { path: '/station/workbench', label: '工作台', description: '库存与待办', icon: 'Odometer' },
  { path: '/station/recycle', label: '回收确认', description: '回收箱清点', icon: 'RefreshLeft' },
  { path: '/station/stock', label: '实物出库', description: '扫码登记出库', icon: 'Box' },
  { path: '/station/disinfect', label: '消毒登记', description: '批次处理留痕', icon: 'MagicStick' },
  { path: '/station/repair', label: '维修报废', description: '延长袋生命周期', icon: 'Tools' },
  { path: '/station/inventory', label: '库存盘点', description: '核对账实差异', icon: 'DocumentChecked' },
  { path: '/station/allocation', label: '调拨出库 / 核收', description: '站点间流转', icon: 'Van' },
  { path: '/station/bags', label: '本站袋台账', description: '袋档案与追溯', icon: 'Grid' },
]
const PLATFORM_MENUS = [
  { path: '/platform/dashboard', label: '数据看板', description: '运营实时概览', icon: 'DataLine' },
  { path: '/platform/users', label: '用户管理', description: '账号与角色', icon: 'UserFilled' },
  { path: '/platform/stations', label: '站点管理', description: '站点与库存', icon: 'OfficeBuilding' },
  { path: '/platform/bags', label: '快递袋台账', description: '全生命周期', icon: 'Grid' },
  { path: '/platform/allocations', label: '调拨管理', description: '跨站资源流转', icon: 'Van' },
  { path: '/platform/orders', label: '订单管理', description: '取用与归还', icon: 'Tickets' },
  { path: '/platform/alerts', label: '异常与风控', description: '及时发现风险', icon: 'Warning' },
  { path: '/platform/audit-logs', label: '操作审计', description: '操作全量留痕', icon: 'Notebook' },
  { path: '/platform/login-logs', label: '登录日志', description: '账号安全记录', icon: 'Key' },
]
const menus = computed(() => (scope.value === 'platform' ? PLATFORM_MENUS : STATION_MENUS))
const activePath = computed(() => {
  const found = menus.value.find((m) => route.path === m.path || route.path.startsWith(`${m.path}/`))
  if (found) return found.path
  const prefix = scope.value === 'platform' ? '/platform/bags' : '/station/bags'
  return route.path.startsWith(prefix) ? prefix : route.path
})
const activeMenu = computed(() => menus.value.find((item) => item.path === activePath.value))
const roleLabel = computed(() => ROLE_LABEL[userStore.role] || userStore.role)
const dateText = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())
const initials = computed(() => (userStore.user?.nickname || '用户').slice(0, 1))

function syncLayout() {
  const width = window.innerWidth
  isMobile.value = width < 768
  if (width >= 768 && width < 1200) collapsed.value = true
  else if (width >= 1200) collapsed.value = false
}
onMounted(() => {
  syncLayout()
  window.addEventListener('resize', syncLayout)
})
onUnmounted(() => window.removeEventListener('resize', syncLayout))
async function handleLogout() {
  try {
    await ElMessageBox.confirm('确认退出当前账号吗？', '退出登录', {
      confirmButtonText: '退出登录', cancelButtonText: '继续使用', type: 'warning',
    })
  } catch { return }
  await userStore.logout()
  router.push('/login')
}
function goProfile() { router.push(`/${scope.value}/profile`) }
function handleUserCommand(command) {
  if (command === 'profile') goProfile()
  else if (command === 'logout') handleLogout()
}
</script>

<template>
  <div class="admin-layout" :class="{ 'is-collapsed': collapsed, 'is-mobile': isMobile, 'menu-open': mobileMenuOpen }">
    <button v-if="mobileMenuOpen" class="admin-layout__scrim" aria-label="关闭菜单" @click="mobileMenuOpen = false" />
    <aside v-if="!isMobile || mobileMenuOpen" class="admin-layout__sidebar">
      <div class="admin-layout__brand"><AppBrand :compact="collapsed && !isMobile" inverse /></div>
      <div v-if="!collapsed || isMobile" class="admin-layout__scope">
        <span class="admin-layout__scope-dot" />
        {{ scope === 'platform' ? '平台运营中心' : '站点工作台' }}
      </div>
      <nav class="admin-layout__menu" aria-label="管理功能导航">
        <p v-if="!collapsed" class="admin-layout__menu-label">功能导航</p>
        <router-link v-for="item in menus" :key="item.path" :to="item.path"
          class="admin-layout__menu-item" :class="{ 'is-active': activePath === item.path }"
          :title="collapsed ? item.label : ''" @click="mobileMenuOpen = false">
          <span class="admin-layout__menu-icon"><el-icon :size="19"><component :is="item.icon" /></el-icon></span>
          <span v-if="!collapsed || isMobile">{{ item.label }}</span>
          <el-icon v-if="activePath === item.path && (!collapsed || isMobile)" class="admin-layout__menu-arrow"><ArrowRight /></el-icon>
        </router-link>
      </nav>
      <div class="admin-layout__sidebar-bottom">
        <div v-if="!collapsed" class="admin-layout__cycle-note">
          <el-icon><Refresh /></el-icon>
          <p>每一次归还，<br />都是下一次循环的开始。</p>
        </div>
        <button class="admin-layout__collapse" type="button" :aria-label="collapsed ? '展开导航' : '收起导航'" @click="collapsed = !collapsed">
          <el-icon><component :is="collapsed ? 'Expand' : 'Fold'" /></el-icon>
          <span v-if="!collapsed">收起导航</span>
        </button>
      </div>
    </aside>

    <div class="admin-layout__body">
      <header class="admin-layout__header">
        <div class="admin-layout__heading">
          <button v-if="isMobile" class="admin-layout__mobile-menu" aria-label="打开菜单" @click="mobileMenuOpen = true"><el-icon :size="21"><Menu /></el-icon></button>
          <div>
            <p class="admin-layout__date">{{ dateText }}<span class="admin-layout__date-divider">/</span>{{ roleLabel }}</p>
            <h1 class="admin-layout__title">{{ route.meta?.title || '' }}</h1>
          </div>
        </div>
        <div class="admin-layout__actions">
          <span class="admin-layout__ws" :class="{ 'is-on': wsStore.connected && wsStore.authed }" :title="wsStore.statusText">
            <span class="admin-layout__ws-dot" /><span class="admin-layout__ws-text">{{ wsStore.connected && wsStore.authed ? '实时同步中' : '实时连接待恢复' }}</span>
          </span>
          <span class="admin-layout__action-divider" />
          <el-dropdown trigger="click" @command="handleUserCommand">
            <button class="admin-layout__user" type="button">
              <span class="admin-layout__avatar">{{ initials }}</span>
              <span class="admin-layout__user-copy"><strong>{{ userStore.user?.nickname || '用户' }}</strong><small>{{ activeMenu?.description || roleLabel }}</small></span>
              <el-icon class="admin-layout__user-caret"><ArrowDown /></el-icon>
            </button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="profile">个人中心</el-dropdown-item><el-dropdown-item command="logout" divided>退出登录</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>
      <main class="admin-layout__main"><div class="admin-layout__content"><router-view /></div></main>
      <nav v-if="isMobile" class="admin-layout__bottom-nav" aria-label="常用功能">
        <router-link v-for="item in menus.slice(0, 4)" :key="item.path" :to="item.path" class="admin-layout__bottom-item" :class="{ 'is-active': activePath === item.path }">
          <el-icon :size="20"><component :is="item.icon" /></el-icon><span>{{ item.label.split(' / ')[0] }}</span>
        </router-link>
        <button class="admin-layout__bottom-item" @click="mobileMenuOpen = true"><el-icon :size="20"><Menu /></el-icon><span>全部功能</span></button>
      </nav>
    </div>
  </div>
</template>

<style scoped>
.admin-layout { display: flex; min-height: 100dvh; background: var(--sems-bg-highlight), var(--sems-bg-page); }
.admin-layout__sidebar { position: fixed; inset: 0 auto 0 0; z-index: 40; display: flex; flex-direction: column; width: var(--sems-sidebar-width); background: var(--sems-bg-sidebar); transition: width var(--sems-duration-base) var(--sems-ease); }
.admin-layout.is-collapsed .admin-layout__sidebar { width: var(--sems-sidebar-width-collapsed); }
.admin-layout__brand { display: flex; align-items: center; min-height: 90px; padding: var(--sems-space-5); overflow: hidden; }
.is-collapsed .admin-layout__brand { justify-content: center; padding: var(--sems-space-4); }
.admin-layout__scope { display: flex; align-items: center; gap: var(--sems-space-2); margin: 0 var(--sems-space-4) var(--sems-space-5); padding: var(--sems-space-3); color: var(--sems-text-on-brand-soft); background: color-mix(in srgb, var(--sems-text-inverse) 6%, transparent); border: 1px solid color-mix(in srgb, var(--sems-text-inverse) 10%, transparent); border-radius: var(--sems-radius-base); font-size: var(--sems-font-size-sm); }
.admin-layout__scope-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--sems-text-on-brand-soft); }
.admin-layout__menu { flex: 1; padding: 0 var(--sems-space-3); overflow-y: auto; }
.admin-layout__menu-label { margin: 0 var(--sems-space-3) var(--sems-space-2); color: var(--sems-text-on-brand-soft); font-size: var(--sems-font-size-xs); letter-spacing: 0.1em; opacity: 0.65; }
.admin-layout__menu-item { display: flex; align-items: center; gap: var(--sems-space-3); min-height: 46px; margin-bottom: var(--sems-space-1); padding: 0 var(--sems-space-3); color: var(--sems-text-on-brand-soft); border-radius: var(--sems-radius-base); text-decoration: none; font-size: var(--sems-font-size-base); white-space: nowrap; transition: color var(--sems-duration-fast), background-color var(--sems-duration-fast); }
.admin-layout__menu-item:hover { color: var(--sems-text-inverse); background: color-mix(in srgb, var(--sems-text-inverse) 7%, transparent); }
.admin-layout__menu-item.is-active { color: var(--sems-text-inverse); background: color-mix(in srgb, var(--sems-text-inverse) 13%, transparent); font-weight: var(--sems-font-weight-medium); }
.admin-layout__menu-icon { display: inline-flex; align-items: center; justify-content: center; width: 24px; flex: none; }
.admin-layout__menu-item.is-active .admin-layout__menu-icon { color: var(--sems-text-inverse); }
.admin-layout__menu-arrow { margin-left: auto; font-size: var(--sems-font-size-sm); opacity: 0.7; }
.is-collapsed .admin-layout__menu-item { justify-content: center; padding: 0; }
.admin-layout__sidebar-bottom { padding: var(--sems-space-3); }
.admin-layout__cycle-note { display: flex; gap: var(--sems-space-3); margin: 0 var(--sems-space-1) var(--sems-space-5); padding: var(--sems-space-3) 0; color: var(--sems-text-on-brand-soft); font-size: var(--sems-font-size-xs); line-height: 1.8; }
.admin-layout__cycle-note p { margin: 0; }
.admin-layout__cycle-note .el-icon { margin-top: var(--sems-space-1); }
.admin-layout__collapse { display: flex; align-items: center; justify-content: center; gap: var(--sems-space-2); width: 100%; min-height: 42px; border: 1px solid color-mix(in srgb, var(--sems-text-inverse) 12%, transparent); border-radius: var(--sems-radius-base); background: transparent; color: var(--sems-text-on-brand-soft); cursor: pointer; }
.admin-layout__collapse:hover { color: var(--sems-text-inverse); background: color-mix(in srgb, var(--sems-text-inverse) 7%, transparent); }
.admin-layout__body { display: flex; flex: 1; flex-direction: column; min-width: 0; margin-left: var(--sems-sidebar-width); transition: margin-left var(--sems-duration-base) var(--sems-ease); }
.is-collapsed .admin-layout__body { margin-left: var(--sems-sidebar-width-collapsed); }
.admin-layout__header { position: sticky; top: 0; z-index: 20; display: flex; align-items: center; justify-content: space-between; gap: var(--sems-space-4); min-height: var(--sems-header-height); padding: var(--sems-space-3) var(--sems-space-6); background: color-mix(in srgb, var(--sems-bg-card) 88%, transparent); border-bottom: 1px solid var(--sems-border-soft); backdrop-filter: blur(16px); }
.admin-layout__heading { display: flex; align-items: center; gap: var(--sems-space-2); min-width: 0; }
.admin-layout__date { display: flex; align-items: center; gap: var(--sems-space-2); margin: 0 0 var(--sems-space-1); color: var(--sems-text-secondary); font-size: var(--sems-font-size-xs); }
.admin-layout__date-divider { color: var(--sems-border-strong); }
.admin-layout__title { margin: 0; color: var(--sems-text-primary); font-size: var(--sems-font-size-xl); font-weight: var(--sems-font-weight-bold); letter-spacing: -0.03em; }
.admin-layout__actions { display: flex; align-items: center; gap: var(--sems-space-5); }
.admin-layout__ws { display: inline-flex; align-items: center; gap: var(--sems-space-2); color: var(--sems-text-secondary); font-size: var(--sems-font-size-xs); }
.admin-layout__ws-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--sems-color-info); }
.admin-layout__ws.is-on { color: var(--sems-color-primary); }
.admin-layout__ws.is-on .admin-layout__ws-dot { background: var(--sems-color-success); box-shadow: 0 0 0 4px var(--sems-color-success-bg); }
.admin-layout__action-divider { width: 1px; height: 28px; background: var(--sems-border); }
.admin-layout__user { display: flex; align-items: center; gap: var(--sems-space-3); padding: 0; border: none; background: transparent; color: var(--sems-text-regular); cursor: pointer; text-align: left; }
.admin-layout__avatar { display: inline-flex; align-items: center; justify-content: center; width: 38px; height: 38px; color: var(--sems-color-primary); background: var(--sems-color-primary-bg); border: 1px solid color-mix(in srgb, var(--sems-color-primary) 12%, transparent); border-radius: 50%; font-weight: var(--sems-font-weight-bold); }
.admin-layout__user-copy { display: flex; flex-direction: column; gap: 2px; }
.admin-layout__user-copy strong { color: var(--sems-text-primary); font-size: var(--sems-font-size-base); font-weight: var(--sems-font-weight-medium); }
.admin-layout__user-copy small { color: var(--sems-text-secondary); font-size: var(--sems-font-size-xs); }
.admin-layout__user-caret { color: var(--sems-text-secondary); }
.admin-layout__main { flex: 1; padding: var(--sems-space-5) var(--sems-space-6) var(--sems-space-6); }
.admin-layout__content { width: 100%; max-width: var(--sems-content-max-width); margin: 0 auto; }
.admin-layout__bottom-nav, .admin-layout__scrim, .admin-layout__mobile-menu { display: none; }
@media (max-width: 1199px) { .admin-layout__header, .admin-layout__main { padding-left: var(--sems-space-5); padding-right: var(--sems-space-5); } .admin-layout__user-copy, .admin-layout__user-caret { display: none; } }
@media (max-width: 767px) {
  .admin-layout__body, .is-collapsed .admin-layout__body { margin-left: 0; }
  .admin-layout__header { min-height: 68px; padding: var(--sems-space-2) var(--sems-space-4); }
  .admin-layout__mobile-menu { display: inline-flex; align-items: center; justify-content: center; width: var(--sems-touch-target); height: var(--sems-touch-target); padding: 0; color: var(--sems-text-primary); border: none; background: transparent; }
  .admin-layout__title { font-size: var(--sems-font-size-lg); }
  .admin-layout__date-divider, .admin-layout__ws-text, .admin-layout__action-divider { display: none; }
  .admin-layout__actions { gap: var(--sems-space-3); }
  .admin-layout__main { padding: var(--sems-space-4); padding-bottom: calc(88px + env(safe-area-inset-bottom)); }
  .admin-layout__sidebar { width: min(300px, 85vw); box-shadow: var(--sems-shadow-popup); }
  .admin-layout__scrim { position: fixed; inset: 0; z-index: 35; display: block; width: 100%; height: 100%; border: none; background: color-mix(in srgb, var(--sems-bg-sidebar) 45%, transparent); backdrop-filter: blur(2px); }
  .admin-layout__cycle-note { display: none; }
  .admin-layout__bottom-nav { position: fixed; right: 0; bottom: 0; left: 0; z-index: 30; display: grid; grid-template-columns: repeat(5, 1fr); background: color-mix(in srgb, var(--sems-bg-card) 96%, transparent); border-top: 1px solid var(--sems-border); padding-bottom: env(safe-area-inset-bottom); backdrop-filter: blur(16px); }
  .admin-layout__bottom-item { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: var(--sems-space-1); min-height: 64px; padding: var(--sems-space-2) 0; border: none; background: transparent; color: var(--sems-text-secondary); font-size: var(--sems-font-size-xs); text-decoration: none; }
  .admin-layout__bottom-item.is-active { color: var(--sems-color-primary); }
}
</style>
