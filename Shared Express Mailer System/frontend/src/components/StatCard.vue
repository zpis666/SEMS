<script setup>
import { computed } from 'vue'

/**
 * KPI 卡 —— 看板与工作台数字块统一件。
 *
 * 10.5 约束：数字字号 28px（--sems-font-size-kpi）、等宽数字对齐（.sems-num）。
 * trend 可选，形如 { value: 12.5, direction: 'up' | 'down' }，表示较上一周期的变化率（%）。
 * 趋势用「箭头 + 升/降文字 + 颜色」三重通道表达，不靠颜色单独承载语义。
 */
const props = defineProps({
  /** 指标名，如「在用袋数」 */
  label: { type: String, default: '' },
  /** 指标值（数字或已格式化字符串） */
  value: { type: [Number, String], default: '—' },
  /** 单位，如「只」「单」 */
  unit: { type: String, default: '' },
  /** 环比趋势：{ value, direction } */
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
  <div class="stat-card">
    <p class="stat-card__label">{{ label }}</p>
    <div class="stat-card__value-row">
      <span class="stat-card__value sems-num">{{ value }}</span>
      <span v-if="unit" class="stat-card__unit">{{ unit }}</span>
    </div>
    <p
      v-if="trend"
      class="stat-card__trend"
      :class="trend.direction === 'down' ? 'is-down' : 'is-up'"
    >
      <el-icon class="stat-card__trend-icon">
        <component :is="trendIcon" />
      </el-icon>
      <span>{{ trendText }}</span>
    </p>
  </div>
</template>

<style scoped>
.stat-card {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
  padding: var(--sems-space-4);
  background-color: var(--sems-bg-card);
  border-radius: var(--sems-radius-card);
  box-shadow: var(--sems-shadow-card);
}

.stat-card__label {
  margin: 0;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
}

.stat-card__value-row {
  display: flex;
  align-items: baseline;
  gap: var(--sems-space-1);
}

.stat-card__value {
  font-size: var(--sems-font-size-kpi);
  font-weight: var(--sems-font-weight-bold);
  line-height: 1.2;
  color: var(--sems-text-primary);
}

.stat-card__unit {
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
}

.stat-card__trend {
  display: flex;
  align-items: center;
  gap: var(--sems-space-1);
  margin: 0;
  font-size: var(--sems-font-size-xs);
}

.stat-card__trend.is-up {
  color: var(--sems-color-success);
}

.stat-card__trend.is-down {
  color: var(--sems-color-danger);
}
</style>
