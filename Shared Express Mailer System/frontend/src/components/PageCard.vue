<script setup>
/**
 * 白卡容器 —— 页面内容区块的统一承载件（《系统设计说明书》10.5）。
 *
 * 约束：
 *  - 圆角只取 --sems-radius-card，阴影只取 --sems-shadow-card，内边距 16px；
 *  - 不使用 el-card（其默认样式与令牌脱节），直接用一个 div 自绘；
 *  - 右上角操作（按钮/筛选）放具名插槽 extra，标题与副标题都可省略。
 */
defineProps({
  /** 卡片标题，省略时只渲染内容区（若提供了 extra 仍会渲染头部） */
  title: { type: String, default: '' },
  /** 标题下方的次要说明 */
  subtitle: { type: String, default: '' },
})
</script>

<template>
  <section class="page-card">
    <header v-if="title || subtitle || $slots.extra" class="page-card__header">
      <div class="page-card__heading">
        <h2 v-if="title" class="page-card__title">{{ title }}</h2>
        <p v-if="subtitle" class="page-card__subtitle">{{ subtitle }}</p>
      </div>
      <div v-if="$slots.extra" class="page-card__extra">
        <slot name="extra" />
      </div>
    </header>
    <slot />
  </section>
</template>

<style scoped>
.page-card {
  padding: var(--sems-space-4);
  background-color: var(--sems-bg-card);
  border-radius: var(--sems-radius-card);
  box-shadow: var(--sems-shadow-card);
}

.page-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sems-space-3);
  margin-bottom: var(--sems-space-4);
}

.page-card__heading {
  min-width: 0;
}

.page-card__title {
  margin: 0;
  font-size: var(--sems-font-size-lg);
  font-weight: var(--sems-font-weight-bold);
  color: var(--sems-text-primary);
}

.page-card__subtitle {
  margin: var(--sems-space-1) 0 0;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
}

.page-card__extra {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sems-space-2);
}
</style>
