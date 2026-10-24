<script setup>
import { computed } from 'vue'
const props = defineProps({
  label: { type: String, default: '' },
  value: { type: [Number, String], default: '—' },
  unit: { type: String, default: '' },
  icon: { type: String, default: 'DataAnalysis' },
  hint: { type: String, default: '' },
  accent: { type: String, default: 'primary' },
  trend: { type: Object, default: null },
})
const trendIcon = computed(() => (props.trend?.direction === 'down' ? 'Bottom' : 'Top'))
const trendText = computed(() => {
  if (!props.trend) return ''
  const direction = props.trend.direction === 'down' ? '下降' : '上升'
  const value = Number(props.trend.value)
  if (!Number.isFinite(value)) return `较上期${direction}`
  return `较上期${direction} ${Math.round(Math.abs(value) * 100) / 100}%`
})
</script>

<template>
  <div class="stat-card" :class="`is-${accent}`">
    <div class="stat-card__top">
      <span class="stat-card__icon"><el-icon :size="20"><component :is="icon" /></el-icon></span>
      <span class="stat-card__caption">实时指标</span>
    </div>
    <p class="stat-card__label">{{ label }}</p>
    <div class="stat-card__value-row"><span class="stat-card__value sems-num">{{ value }}</span><span v-if="unit" class="stat-card__unit">{{ unit }}</span></div>
    <p v-if="trend" class="stat-card__trend" :class="trend.direction === 'down' ? 'is-down' : 'is-up'"><el-icon><component :is="trendIcon" /></el-icon><span>{{ trendText }}</span></p>
    <p v-else class="stat-card__hint">{{ hint || '全平台数据汇总' }}</p>
  </div>
</template>

<style scoped>
.stat-card { position: relative; display: flex; flex-direction: column; min-height: 164px; padding: var(--sems-space-5); overflow: hidden; background: var(--sems-bg-card); border: 1px solid var(--sems-border); border-radius: var(--sems-radius-card); box-shadow: var(--sems-shadow-card); transition: border-color var(--sems-duration-base), transform var(--sems-duration-base) var(--sems-ease), box-shadow var(--sems-duration-base); }
.stat-card:hover { transform: translateY(-2px); border-color: var(--sems-border-strong); box-shadow: var(--sems-shadow-popup); }
.stat-card__top { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--sems-space-4); }
.stat-card__icon { display: inline-flex; align-items: center; justify-content: center; width: 38px; height: 38px; color: var(--sems-color-primary); background: var(--sems-color-primary-bg); border-radius: var(--sems-radius-base); }
.is-blue .stat-card__icon { color: var(--sems-chart-3); background: color-mix(in srgb, var(--sems-chart-3) 10%, var(--sems-color-white)); }
.is-amber .stat-card__icon { color: var(--sems-color-warning); background: var(--sems-color-warning-bg); }
.is-green .stat-card__icon { color: var(--sems-color-success); background: var(--sems-color-success-bg); }
.stat-card__caption { color: var(--sems-text-secondary); font-size: var(--sems-font-size-xs); }
.stat-card__label { margin: 0 0 var(--sems-space-1); color: var(--sems-text-regular); font-size: var(--sems-font-size-sm); }
.stat-card__value-row { display: flex; align-items: baseline; gap: var(--sems-space-2); }
.stat-card__value { color: var(--sems-text-primary); font-size: var(--sems-font-size-kpi); font-weight: var(--sems-font-weight-bold); line-height: 1.25; letter-spacing: -0.04em; }
.stat-card__unit { color: var(--sems-text-secondary); font-size: var(--sems-font-size-sm); }
.stat-card__hint { margin: auto 0 0; padding-top: var(--sems-space-2); color: var(--sems-text-secondary); font-size: var(--sems-font-size-xs); }
.stat-card__trend { display: flex; align-items: center; gap: var(--sems-space-1); margin: auto 0 0; font-size: var(--sems-font-size-xs); }
.stat-card__trend.is-up { color: var(--sems-color-success); }.stat-card__trend.is-down { color: var(--sems-color-danger); }
@media (max-width: 767px) { .stat-card { min-height: 146px; padding: var(--sems-space-4); } .stat-card__top { margin-bottom: var(--sems-space-3); } }
</style>
