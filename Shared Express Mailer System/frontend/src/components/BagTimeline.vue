<script setup>
import { BAG_EVENT_LABEL, BAG_STATUS_META } from '@/constants'
import StatusTag from '@/components/StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'

/**
 * 袋流转时间线 —— 「取—用—还—洗—调」闭环最直观的追溯展示位（10.5，不得省略）。
 *
 * records 元素形如：
 *   { event, fromStatus, toStatus, operatorId, operatorName, stationId, stationName, remark, createdAt }
 *
 * 约定：
 *  - 节点按 toStatus 对应的 --sems-bag-* 令牌着色；最后一条是当前态，加粗高亮；
 *  - 遗失（LOST）与报废（SCRAP / SCRAP_DIRECT）节点标红，备注随节点突出显示；
 *  - 每条展示事件名、状态变化（StatusTag 小号）、操作人、站点、时间与备注；
 *  - 空数组显示 EmptyState。
 */
defineProps({
  /** 流转日志列表（后端按时间正序返回） */
  records: { type: Array, default: () => [] },
})

/** 危险节点：遗失/报废是审计重点，必须一眼可辨 */
function isDanger(record) {
  return (
    record?.toStatus === 'LOST' ||
    record?.event === 'SCRAP' ||
    record?.event === 'SCRAP_DIRECT'
  )
}

/** 节点颜色：优先取状态令牌，缺状态时按危险/中性兜底 */
function nodeColor(record) {
  const meta = BAG_STATUS_META[record?.toStatus]
  if (meta) return `var(${meta.token})`
  return isDanger(record) ? 'var(--sems-color-danger)' : 'var(--sems-color-info)'
}

function eventLabel(record) {
  return BAG_EVENT_LABEL[record?.event] || record?.event || '状态流转'
}

function formatTime(value) {
  if (!value) return '—'
  // 已是「yyyy-MM-dd HH:mm:ss」形态的直接展示，避免时区解析引入偏移
  if (typeof value === 'string' && !value.includes('T')) return value
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = (unit) => String(unit).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}
</script>

<template>
  <EmptyState v-if="!records.length" icon="Clock" description="暂无流转记录" />

  <el-timeline v-else class="bag-timeline">
    <el-timeline-item
      v-for="(record, index) in records"
      :key="index"
      :color="nodeColor(record)"
      :timestamp="formatTime(record.createdAt)"
      placement="top"
      :class="{
        'is-current': index === records.length - 1,
        'is-danger': isDanger(record),
      }"
    >
      <div class="bag-timeline__node">
        <div class="bag-timeline__head">
          <span class="bag-timeline__event">{{ eventLabel(record) }}</span>
          <span v-if="index === records.length - 1" class="bag-timeline__current">当前状态</span>
        </div>

        <div class="bag-timeline__transition">
          <template v-if="record.fromStatus">
            <StatusTag kind="bag" :value="record.fromStatus" size="small" />
            <span class="bag-timeline__arrow">→</span>
          </template>
          <StatusTag kind="bag" :value="record.toStatus" size="small" />
        </div>

        <div class="bag-timeline__meta">
          <span>操作人：{{ record.operatorName || record.operatorId || '系统' }}</span>
          <span>站点：{{ record.stationName || record.stationId || '—' }}</span>
        </div>

        <p v-if="record.remark" class="bag-timeline__remark">备注：{{ record.remark }}</p>
      </div>
    </el-timeline-item>
  </el-timeline>
</template>

<style scoped>
.bag-timeline__node {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
}

.bag-timeline__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sems-space-2);
}

.bag-timeline__event {
  font-size: var(--sems-font-size-base);
  color: var(--sems-text-primary);
}

.bag-timeline__current {
  font-size: var(--sems-font-size-xs);
  color: var(--sems-color-primary);
}

.bag-timeline__transition {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sems-space-1);
}

.bag-timeline__arrow {
  font-size: var(--sems-font-size-xs);
  color: var(--sems-text-secondary);
}

.bag-timeline__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sems-space-1) var(--sems-space-4);
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
}

.bag-timeline__remark {
  margin: 0;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
}

/* 当前态（最后一条）加粗高亮，节点加一圈描边 */
.bag-timeline .is-current .bag-timeline__event {
  font-weight: var(--sems-font-weight-bold);
}

.bag-timeline .is-current :deep(.el-timeline-item__node) {
  box-shadow: 0 0 0 3px var(--sems-border);
}

/* 遗失 / 报废节点标红：时间戳与备注同步用危险色 */
.bag-timeline .is-danger .bag-timeline__remark,
.bag-timeline .is-danger :deep(.el-timeline-item__timestamp) {
  color: var(--sems-color-danger);
}
</style>
