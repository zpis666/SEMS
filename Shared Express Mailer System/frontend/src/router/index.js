import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

/**
 * 路由表 + 全局守卫（见《系统设计说明书》10.1 router：未登录跳登录；按 role 过滤可访问路由）。
 *
 * 三块布局按角色分域：
 *   /m/**        用户端（移动端优先，单列卡片流）
 *   /station/**  站点端（PC 工作台 + 手机单任务页）
 *   /platform/** 平台端（PC 深色侧边栏）
 *
 * 用 hash 模式（createWebHashHistory）：部署时 Nginx 只需托管静态文件，
 * 不必额外配置 history 回退（设计书 11.1 的 try_files 回退依然保留，作为切 history 模式的余量）。
 */

/** 角色门槛（与后端 @RequireRole 的等级制一致：高等级自动继承低等级的能力） */
const ROLE_LEVEL = { USER: 1, STATION_ADMIN: 2, PLATFORM_ADMIN: 3 }

const routes = [
  { path: '/', redirect: '/m/home' },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/common/Login.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/common/Register.vue'),
    meta: { public: true, title: '注册' },
  },
  {
    path: '/change-password',
    name: 'ChangePassword',
    component: () => import('@/views/common/ChangePassword.vue'),
    // 强制改密账号未改密前只放行改密/登出/续签（后端拦截器同样只放行这几个），故本页不加角色门槛
    meta: { title: '修改密码' },
  },
  {
    path: '/403',
    name: 'Forbidden',
    component: () => import('@/views/common/Forbidden.vue'),
    meta: { public: true, title: '无权限' },
  },
  {
    path: '/404',
    name: 'NotFound',
    component: () => import('@/views/common/NotFound.vue'),
    meta: { public: true, title: '页面不存在' },
  },

  /* ------------------------------ 用户端 ------------------------------ */
  {
    path: '/m',
    component: () => import('@/layouts/UserLayout.vue'),
    children: [
      {
        path: 'home',
        name: 'UserHome',
        component: () => import('@/views/user/Home.vue'),
        meta: { title: '首页' },
      },
      {
        path: 'scan',
        name: 'UserScan',
        component: () => import('@/views/user/ScanBorrow.vue'),
        meta: { title: '扫码取袋' },
      },
      {
        path: 'bag/:bagCode',
        name: 'BagInfo',
        component: () => import('@/views/user/BagInfo.vue'),
        meta: { title: '袋详情' },
      },
      {
        path: 'orders',
        name: 'MyOrders',
        component: () => import('@/views/user/MyOrders.vue'),
        meta: { title: '我的订单' },
      },
      {
        path: 'orders/:orderNo',
        name: 'OrderDetail',
        component: () => import('@/views/user/OrderDetail.vue'),
        meta: { title: '订单详情' },
      },
      {
        path: 'orders/:orderNo/shipping',
        name: 'ShippingForm',
        component: () => import('@/views/user/ShippingForm.vue'),
        meta: { title: '填写寄件信息' },
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/user/Profile.vue'),
        meta: { title: '个人中心' },
      },
    ],
  },

  /* ------------------------------ 站点端 ------------------------------ */
  {
    path: '/station',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { minRole: 'STATION_ADMIN', scope: 'station' },
    children: [
      {
        path: 'workbench',
        name: 'StationWorkbench',
        component: () => import('@/views/station/Workbench.vue'),
        meta: { title: '工作台' },
      },
      {
        path: 'profile',
        name: 'StationProfile',
        component: () => import('@/views/user/Profile.vue'),
        meta: { title: '个人中心' },
      },
      {
        path: 'recycle',
        name: 'RecycleConfirm',
        component: () => import('@/views/station/RecycleConfirm.vue'),
        meta: { title: '回收确认' },
      },
      {
        path: 'stock',
        name: 'StockInOut',
        component: () => import('@/views/station/StockInOut.vue'),
        meta: { title: '出入库 / 激活上架' },
      },
      {
        path: 'disinfect',
        name: 'Disinfection',
        component: () => import('@/views/station/Disinfection.vue'),
        meta: { title: '消毒登记' },
      },
      {
        path: 'repair',
        name: 'RepairScrap',
        component: () => import('@/views/station/RepairScrap.vue'),
        meta: { title: '维修报废' },
      },
      {
        path: 'inventory',
        name: 'InventoryCheck',
        component: () => import('@/views/station/InventoryCheck.vue'),
        meta: { title: '库存盘点' },
      },
      {
        path: 'allocation',
        name: 'StationAllocation',
        component: () => import('@/views/station/Allocation.vue'),
        meta: { title: '调拨出库 / 核收' },
      },
      {
        path: 'bags',
        name: 'StationBagLedger',
        component: () => import('@/views/station/BagLedger.vue'),
        meta: { title: '本站袋台账' },
      },
      {
        path: 'bags/:bagCode',
        name: 'StationBagDetail',
        component: () => import('@/views/station/BagDetail.vue'),
        meta: { title: '袋详情' },
      },
    ],
  },

  /* ------------------------------ 平台端 ------------------------------ */
  {
    path: '/platform',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { minRole: 'PLATFORM_ADMIN', scope: 'platform' },
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/platform/Dashboard.vue'),
        meta: { title: '数据看板' },
      },
      {
        path: 'profile',
        name: 'PlatformProfile',
        component: () => import('@/views/user/Profile.vue'),
        meta: { title: '个人中心' },
      },
      {
        path: 'users',
        name: 'UserManage',
        component: () => import('@/views/platform/UserManage.vue'),
        meta: { title: '用户管理' },
      },
      {
        path: 'stations',
        name: 'StationManage',
        component: () => import('@/views/platform/StationManage.vue'),
        meta: { title: '站点管理' },
      },
      {
        path: 'bags',
        name: 'BagLedger',
        component: () => import('@/views/platform/BagLedger.vue'),
        meta: { title: '快递袋台账' },
      },
      {
        path: 'bags/:bagCode',
        name: 'PlatformBagDetail',
        component: () => import('@/views/platform/BagDetail.vue'),
        meta: { title: '袋详情与流转追溯' },
      },
      {
        path: 'allocations',
        name: 'AllocationManage',
        component: () => import('@/views/platform/AllocationManage.vue'),
        meta: { title: '调拨管理' },
      },
      {
        path: 'orders',
        name: 'OrderManage',
        component: () => import('@/views/platform/OrderManage.vue'),
        meta: { title: '订单管理' },
      },
      {
        path: 'audit-logs',
        name: 'AuditLog',
        component: () => import('@/views/platform/AuditLog.vue'),
        meta: { title: '操作审计' },
      },
      {
        path: 'login-logs',
        name: 'LoginLog',
        component: () => import('@/views/platform/LoginLog.vue'),
        meta: { title: '登录日志' },
      },
      {
        path: 'alerts',
        name: 'Alerts',
        component: () => import('@/views/platform/Alerts.vue'),
        meta: { title: '异常与风控' },
      },
    ],
  },

  { path: '/:pathMatch(.*)*', redirect: '/404' },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const userStore = useUserStore()

  if (to.meta.public) {
    return true
  }

  if (!userStore.token) {
    // 未登录：带上回跳路由（设计书 6.6「401 统一跳登录并保留当前路由用于登录后回跳」）
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 刷新页面后内存里的 user 为空，这里补拉一次；拉不到说明 token 已失效
  if (!userStore.user) {
    try {
      await userStore.bootstrap()
    } catch {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  // 强制改密账号：只放行改密页，其余一律拉回（与后端拦截器白名单一致）
  if (userStore.mustChangePassword && to.path !== '/change-password') {
    return { path: '/change-password' }
  }

  // 角色门槛：沿路由链取最严的一档
  const required = to.matched.reduce((max, record) => {
    const level = ROLE_LEVEL[record.meta?.minRole] || 0
    return Math.max(max, level)
  }, 0)
  if (required > 0 && userStore.roleLevel < required) {
    return { path: '/403' }
  }

  return true
})

router.afterEach((to) => {
  const base = '共享快递袋系统'
  document.title = to.meta?.title ? `${to.meta.title} · ${base}` : base
})

export default router
