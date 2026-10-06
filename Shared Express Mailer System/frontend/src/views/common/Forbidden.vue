<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

/**
 * 403 无权限页（契约 4.1）。
 *
 * 「返回首页」按登录态落地：已登录回各自角色首页（homePath），未登录去登录页。
 */

const router = useRouter()
const userStore = useUserStore()

const homePath = computed(() => (userStore.isLogin ? userStore.homePath : '/login'))

function goHome() {
  router.push(homePath.value)
}
</script>

<template>
  <div class="error-page">
    <el-icon class="error-page__icon"><Lock /></el-icon>
    <h1 class="sems-page-title">无权访问</h1>
    <p class="error-page__desc">当前账号没有访问该页面的权限。如确有需要，请联系平台管理员调整角色或站点绑定。</p>
    <el-button class="error-page__action" type="primary" size="large" @click="goHome">返回首页</el-button>
  </div>
</template>

<style scoped>
.error-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sems-space-3);
  min-height: 100dvh;
  padding: var(--sems-space-6) var(--sems-space-4);
  background-color: var(--sems-bg-page);
  text-align: center;
}

/* 大图标：尺寸由 KPI 字号令牌 ×2 推得，不另立字号档位 */
.error-page__icon {
  font-size: calc(var(--sems-font-size-kpi) * 2);
  color: var(--sems-text-secondary);
}

.error-page__desc {
  max-width: 420px;
  margin: 0;
  color: var(--sems-text-secondary);
  line-height: 1.7;
}

.error-page__action {
  margin-top: var(--sems-space-2);
}
</style>
