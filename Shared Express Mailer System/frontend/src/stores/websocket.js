import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useUserStore } from '@/stores/user'

/**
 * WebSocket 推送 Store（见《系统设计说明书》7.3）。
 *
 * 服务端约定：
 *  - 端点 /ws，**首帧鉴权**（token 不进 URL，避免被 Nginx 访问日志记录）：
 *    连接建立后 10 秒内发 {"type":"auth","token":"<JWT>"}；
 *  - 订阅按 JWT 声明生成白名单，越权订阅被服务端拒绝；
 *  - 服务端 30 秒心跳；心跳时会复查 jti 黑名单 / token_version / 账号状态 / exp，
 *    任一失效即主动断开——**续签会拉黑旧 jti，此类断连属预期**，
 *    所以重连前必须先刷新 token（本文件 ensureConnected 已按此实现）。
 *
 * 前端约定：
 *  - 断线指数退避重连 1s/2s/4s/…上限 30s；
 *  - visibilitychange 回前台立即重连，并重新拉取当前页数据兜底（不上消息 ACK/重放）。
 */

/** 重连退避序列（毫秒），到顶后固定 30s 重试 */
const RECONNECT_DELAYS = [1000, 2000, 4000, 8000, 16000, 30000]

/** 首帧鉴权超时：与服务端 10 秒窗口对齐，留 1 秒余量避免边界抖动 */
const AUTH_TIMEOUT_MS = 9000

export const useWebSocketStore = defineStore('websocket', () => {
  const connected = ref(false)
  const authed = ref(false)
  /** 最近一条推送消息；页面 watch 它来刷新列表/库存，不做消息队列 */
  const lastMessage = ref(null)
  /** 已订阅的主题（供调试与去重，服务端才是权威） */
  const subscriptions = ref(new Set())

  let socket = null
  let reconnectAttempt = 0
  let reconnectTimer = null
  let authTimer = null
  /** 主动断开标记：登出/卸载时置位，避免触发无意义的重连 */
  let manualClose = false

  const statusText = computed(() => {
    if (connected.value && authed.value) return '实时推送已连接'
    if (connected.value) return '正在鉴权…'
    return '实时推送未连接'
  })

  function wsUrl() {
    // 用相对路径拼接：开发期由 vite 代理 /ws 到 8090，生产期由 Nginx 反代（见 vite.config.js 与设计书 11.1）
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${protocol}//${window.location.host}/ws`
  }

  function clearTimers() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (authTimer) {
      clearTimeout(authTimer)
      authTimer = null
    }
  }

  function scheduleReconnect() {
    if (manualClose) return
    clearTimers()
    const delay = RECONNECT_DELAYS[Math.min(reconnectAttempt, RECONNECT_DELAYS.length - 1)]
    reconnectAttempt += 1
    reconnectTimer = setTimeout(() => {
      connect()
    }, delay)
  }

  function connect() {
    const userStore = useUserStore()
    if (!userStore.token) return
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
      return
    }
    manualClose = false
    try {
      socket = new WebSocket(wsUrl())
    } catch (error) {
      console.warn('[SEMS] WebSocket 创建失败，将重连', error)
      scheduleReconnect()
      return
    }

    socket.onopen = () => {
      connected.value = true
      authed.value = false
      // 首帧鉴权：10 秒内必须发出，否则服务端会断开
      socket.send(JSON.stringify({ type: 'auth', token: userStore.token }))
      authTimer = setTimeout(() => {
        if (!authed.value) {
          console.warn('[SEMS] WebSocket 首帧鉴权超时，主动断开重连')
          socket?.close()
        }
      }, AUTH_TIMEOUT_MS)
    }

    socket.onmessage = (event) => {
      let message
      try {
        message = JSON.parse(event.data)
      } catch {
        return
      }
      if (message?.type === 'auth_ok' || message?.type === 'auth-ok') {
        authed.value = true
        reconnectAttempt = 0
        if (authTimer) {
          clearTimeout(authTimer)
          authTimer = null
        }
        return
      }
      if (message?.type === 'ping') {
        // 服务端心跳到达即为通道存活；回 pong 让它知道客户端还在
        socket?.send(JSON.stringify({ type: 'pong' }))
        return
      }
      lastMessage.value = message
    }

    socket.onclose = () => {
      connected.value = false
      authed.value = false
      subscriptions.value = new Set()
      clearTimers()
      if (!manualClose) {
        scheduleReconnect()
      }
    }

    socket.onerror = () => {
      // onerror 之后必定触发 onclose，重连逻辑统一放在 onclose，避免重连两次
      console.warn('[SEMS] WebSocket 连接异常')
    }
  }

  /**
   * 回前台时的重连入口：**先刷新 token 再建连**。
   * 续签会拉黑旧 jti，用旧 token 重连必然被服务端在心跳时踢掉。
   */
  async function ensureConnected() {
    const userStore = useUserStore()
    if (!userStore.token) return
    if (socket?.readyState === WebSocket.OPEN && authed.value) return
    try {
      await userStore.renew()
    } catch {
      // 续签失败仍尝试用现有 token 重连；失败会由 401 逻辑统一跳到登录页
    }
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.close()
    }
    reconnectAttempt = 0
    connect()
  }

  /** 订阅主题（服务端按 JWT 声明校验，越权订阅会被拒绝并审计） */
  function subscribe(topic) {
    if (!topic || subscriptions.value.has(topic)) return
    if (socket?.readyState === WebSocket.OPEN && authed.value) {
      socket.send(JSON.stringify({ type: 'subscribe', topic }))
    }
    subscriptions.value.add(topic)
  }

  function unsubscribe(topic) {
    if (!subscriptions.value.has(topic)) return
    if (socket?.readyState === WebSocket.OPEN && authed.value) {
      socket.send(JSON.stringify({ type: 'unsubscribe', topic }))
    }
    subscriptions.value.delete(topic)
  }

  function disconnect() {
    manualClose = true
    clearTimers()
    reconnectAttempt = 0
    connected.value = false
    authed.value = false
    subscriptions.value = new Set()
    if (socket) {
      socket.close()
      socket = null
    }
  }

  return {
    connected,
    authed,
    lastMessage,
    subscriptions,
    statusText,
    connect,
    ensureConnected,
    disconnect,
    subscribe,
    unsubscribe,
  }
})
