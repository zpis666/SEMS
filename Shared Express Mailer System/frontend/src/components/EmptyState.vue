<script setup>
/**
 * 空态占位 —— 页面三态中「空态」的统一实现：图标 + 一句话 + 主操作。
 *
 * 全站列表/看板的「暂无数据」一律走本组件，页面不要各画各的。
 * icon 传 Element Plus 图标名（main.js 已全量全局注册），默认 Box。
 */
defineProps({
  /** Element Plus 图标名，如 Box / Clock / Tickets */
  icon: { type: String, default: 'Box' },
  /** 空态说明文案 */
  description: { type: String, default: '暂无数据' },
})
</script>

<template>
  <div class="empty-state">
    <el-icon class="empty-state__icon" :size="48">
      <component :is="icon" />
    </el-icon>
    <p class="empty-state__desc">{{ description }}</p>
    <div v-if="$slots.default" class="empty-state__action">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sems-space-3);
  padding: var(--sems-space-6) var(--sems-space-4);
  text-align: center;
}

.empty-state__icon {
  color: var(--sems-text-secondary);
}

.empty-state__desc {
  margin: 0;
  font-size: var(--sems-font-size-base);
  color: var(--sems-text-secondary);
}

.empty-state__action {
  margin-top: var(--sems-space-1);
}
</style>
