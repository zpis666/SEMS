<script setup>
import { onMounted, onUnmounted, watch } from 'vue'
import { useUserStore } from '@/stores/user'
import { useWebSocketStore } from '@/stores/websocket'

/**
 * 根组件。
 *
 * 只负责两件全局事务：
 *  1) 应用启动时用残留 token 拉一次用户信息（刷新页面不掉登录态）；
 *  2) 登录态变化时驱动 WebSocket 的连接与断开（设计书 7.3：推送随会话生命周期）。
 */

const userStore = useUserStore()
const wsStore = useWebSocketStore()

onMounted(async () => {
  await userStore.bootstrap()
  if (userStore.isLogin) {
    wsStore.connect()
  }
})

/**
 * 设计书 7.3 / 10.3：`visibilitychange` 回前台立即重连并**重新拉取当前页数据**兜底
 * （不上消息 ACK/重放机制——页面隐藏期间错过的推送不会被补发，只能靠重新拉取）。
 *
 * 因此除重连外，还向全局广播 `sems:visibility-resume` 事件：当前页监听该事件后
 * 调用自己的加载函数刷新数据（各页可选接入，无监听者时事件无副作用）。
 */
function handleVisibility() {
  if (document.visibilityState === 'visible' && userStore.isLogin) {
    wsStore.ensureConnected()
    window.dispatchEvent(new CustomEvent('sems:visibility-resume'))
  }
}

onMounted(() => document.addEventListener('visibilitychange', handleVisibility))
onUnmounted(() => document.removeEventListener('visibilitychange', handleVisibility))

watch(
  () => userStore.isLogin,
  (login) => {
    if (login) {
      wsStore.connect()
    } else {
      wsStore.disconnect()
    }
  },
)
</script>

<template>
  <!-- 用户端与站点端/平台端的布局由各自路由的 layout 组件承担，根组件只留一个插槽 -->
  <router-view />
</template>
