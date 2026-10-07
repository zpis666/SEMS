<script setup>
import { computed } from 'vue'
import {
  BAG_STATUS_META,
  ORDER_STATUS_META,
  ALLOCATION_STATUS_META,
  ALLOCATION_DETAIL_STATUS_META,
  INVENTORY_CHECK_STATUS_META,
  USER_STATUS_META,
  STATION_STATUS_META,
  DISINFECT_RESULT_META,
  REPAIR_RESULT_META,
  LIABILITY_STATUS_META,
} from '@/constants'

/**
 * 状态标签 —— 全站状态展示的唯一组件。
 *
 * 设计约束（《系统设计说明书》10.5）：
 *  1) 标签一律「色 + 文案」，禁止仅靠颜色区分状态；
 *  2) 同为一种颜色但语义不同的状态（空闲/已分配、待回收确认/调拨在途）靠「描边 or 实心浅底」区分；
 *  3) 绿色系标签必须带图标（`success` 类一律加对勾），以便色觉障碍用户识别。
 *
 * 颜色全部来自 styles/tokens.css 的 CSS 变量，组件里不出现任何十六进制色值。
 */

const props = defineProps({
  /** 状态取值集合：bag | order | allocation | allocationDetail | inventoryCheck | user | station | disinfectResult | repairResult | liability */
  kind: { type: String, default: 'bag' },
  /** 状态码或中文枚举值 */
  value: { type: String, default: '' },
  /** 覆盖默认文案 */
  label: { type: String, default: '' },
  /** 尺寸：default | small */
  size: { type: String, default: 'default' },
})

const META_MAP = {
  bag: BAG_STATUS_META,
  order: ORDER_STATUS_META,
  allocation: ALLOCATION_STATUS_META,
  allocationDetail: ALLOCATION_DETAIL_STATUS_META,
  inventoryCheck: INVENTORY_CHECK_STATUS_META,
  user: USER_STATUS_META,
  station: STATION_STATUS_META,
  disinfectResult: DISINFECT_RESULT_META,
  repairResult: REPAIR_RESULT_META,
  liability: LIABILITY_STATUS_META,
}

const meta = computed(() => {
  const map = META_MAP[props.kind] || {}
  return map[props.value] || { label: props.value || '—', token: '--sems-color-info', variant: 'outline' }
})

const text = computed(() => props.label || meta.value.label)

/**
 * 颜色解析：
 * - 袋/订单状态用各自的 11 / 6 态专用色令牌；
 * - 其余（调拨、盘点、用户、站点等）复用语义色令牌，按 type 映射。
 */
const colorVar = computed(() => {
  if (meta.value.token) return `var(${meta.value.token})`
  const typeToken = {
    primary: '--sems-color-primary',
    success: '--sems-color-success',
    warning: '--sems-color-warning',
    danger: '--sems-color-danger',
    info: '--sems-color-info',
  }[meta.value.type || 'info']
  return `var(${typeToken})`
})

const solid = computed(() => meta.value.variant === 'solid')

/** 绿色系状态带图标：色觉障碍下仍可辨（10.5「状态双通道」） */
const showCheckIcon = computed(() => {
  const type = meta.value.type
  if (type === 'success') return true
  // 袋/订单状态没有 type 字段，按令牌判断是否为 success 绿
  return ['--sems-bag-reusable', '--sems-order-finished'].includes(meta.value.token)
})
</script>

<template>
  <span
    class="sems-status-tag"
    :class="[`sems-status-tag--${size}`, solid ? 'is-solid' : 'is-outline']"
    :style="{ '--tag-color': colorVar }"
  >
    <svg v-if="showCheckIcon" class="sems-status-tag__icon" viewBox="0 0 12 12" aria-hidden="true">
      <path
        d="M2.5 6.2l2.3 2.3 4.7-5"
        fill="none"
        stroke="currentColor"
        stroke-width="1.8"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </svg>
    <span>{{ text }}</span>
  </span>
</template>

<style scoped>
.sems-status-tag {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-1);
  padding: 0 var(--sems-space-2);
  border-radius: var(--sems-radius-tag);
  font-size: var(--sems-font-size-sm);
  line-height: 22px;
  white-space: nowrap;
  /* 色 + 文案双通道：文字始终使用状态色，描边态再叠一圈同色边框 */
  color: var(--tag-color);
}
.sems-status-tag--small {
  font-size: var(--sems-font-size-xs);
  line-height: 18px;
  padding: 0 var(--sems-space-1);
}
.sems-status-tag.is-outline {
  border: 1px solid var(--tag-color);
  background-color: transparent;
}
.sems-status-tag.is-solid {
  border: 1px solid transparent;
  /* 浅底由当前状态色与白底混合得到，避免为每个状态再定义一套浅色令牌 */
  background-color: color-mix(in srgb, var(--tag-color) 10%, var(--sems-color-white));
}
.sems-status-tag__icon {
  width: 12px;
  height: 12px;
  flex: none;
}
</style>
