import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getToken, setToken } from '@/utils/request'
import * as authApi from '@/api/auth'
import * as userApi from '@/api/user'

/**
 * 用户会话 Store（见《系统设计说明书》10.1 stores/user：token/role/stationId）。
 *
 * 会话策略（10.3）：token 以内存为主、sessionStorage 兜底（标签页级，关闭即清除）；
 * 剩余 <30min 由定时器触发静默续签（7.1），页面停留无操作也照常续签。
 */

/** 角色等级：与后端 LoginUser.roleLevel() 同一套口径（USER=1 < STATION_ADMIN=2 < PLATFORM_ADMIN=3） */
const ROLE_LEVEL = { USER: 1, STATION_ADMIN: 2, PLATFORM_ADMIN: 3 }

/** 续签阈值：剩余不足该值即续签（与 sems.jwt.renew-threshold 默认值 30m 对齐，见设计书 11.2） */
const RENEW_THRESHOLD_MS = 30 * 60 * 1000

export const useUserStore = defineStore('user', () => {
  const token = ref(getToken())
  const user = ref(null)
  /** 续签定时器句柄；页面卸载不需要清理（整个 SPA 生命周期只该有一个） */
  let renewTimer = null

  const isLogin = computed(() => !!token.value && !!user.value)
  const role = computed(() => user.value?.role || '')
  const roleLevel = computed(() => ROLE_LEVEL[role.value] || 0)
  const isPlatformAdmin = computed(() => role.value === 'PLATFORM_ADMIN')
  const isStationAdmin = computed(() => role.value === 'STATION_ADMIN')
  const stationId = computed(() => user.value?.stationId ?? null)
  const mustChangePassword = computed(() => !!user.value?.mustChangePassword)

  /** 登录后默认落地页：三类角色各自的工作台 */
  const homePath = computed(() => {
    if (role.value === 'PLATFORM_ADMIN') return '/platform/dashboard'
    if (role.value === 'STATION_ADMIN') return '/station/workbench'
    return '/m/home'
  })

  function applySession(payload) {
    token.value = payload.token || ''
    setToken(token.value)
    user.value = payload.user || null
    scheduleRenew(payload.expiresAt)
  }

  function clearSession() {
    token.value = ''
    user.value = null
    setToken('')
    if (renewTimer) {
      clearTimeout(renewTimer)
      renewTimer = null
    }
  }

  /**
   * 静默续签调度（7.1）：按 token 剩余时间算出「还应等多久才到 30 分钟阈值」，
   * 到点即续签；续签会拉黑旧 jti，WebSocket 会在下次心跳断开——
   * 所以这里续签成功后要通知 ws store 重连（在 stores/websocket.js 里监听）。
   */
  function scheduleRenew(expiresAt) {
    if (renewTimer) {
      clearTimeout(renewTimer)
      renewTimer = null
    }
    if (!expiresAt || !token.value) return
    const expireTime = new Date(expiresAt).getTime()
    const wait = expireTime - Date.now() - RENEW_THRESHOLD_MS
    // 已进入阈值区间则立即续签；否则等到达阈值那一刻；至少留 1 秒避免死循环
    renewTimer = setTimeout(
      () => {
        renew().catch(() => {
          /* 续签失败不打断用户操作：token 真过期时下一次请求会 401，由 request.js 统一跳登录 */
        })
      },
      Math.max(wait, 1000),
    )
  }

  async function login(payload) {
    const data = await authApi.login(payload)
    applySession(data)
    return data
  }

  async function register(payload) {
    return authApi.register(payload)
  }

  async function refresh() {
    const data = await authApi.refresh()
    applySession(data)
    return data
  }

  /** 真正的续签实现：失败即抛，由调用方决定是否静默 */
  async function renew() {
    return refresh()
  }

  async function fetchMe() {
    const data = await userApi.getMe()
    if (user.value) {
      user.value = { ...user.value, ...data }
    } else {
      user.value = data
    }
    return data
  }

  async function updateProfile(payload) {
    const data = await userApi.updateMe(payload)
    await fetchMe()
    return data
  }

  async function changePassword(payload) {
    await userApi.changePassword(payload)
    // 改密会自增 token_version，旧 token 立即失效（7.1）——必须回到登录页重新登录
    clearSession()
  }

  async function logout() {
    try {
      await authApi.logout()
    } catch {
      // 登出接口失败也要清本地会话：否则用户会陷入「点了登出但还显示已登录」
    } finally {
      clearSession()
    }
  }

  /** 启动时用 sessionStorage 里的 token 拉一次用户信息，刷新页面不至于掉登录态 */
  async function bootstrap() {
    if (!token.value || user.value) return
    try {
      await fetchMe()
    } catch {
      clearSession()
    }
  }

  return {
    token,
    user,
    isLogin,
    role,
    roleLevel,
    isPlatformAdmin,
    isStationAdmin,
    stationId,
    mustChangePassword,
    homePath,
    login,
    register,
    refresh,
    renew,
    fetchMe,
    updateProfile,
    changePassword,
    logout,
    bootstrap,
    clearSession,
    scheduleRenew,
  }
})
