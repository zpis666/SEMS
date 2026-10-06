<script setup>
import { computed } from 'vue'

/**
 * 骨架屏 —— 页面三态中「加载态」的统一实现（10.5：加载用骨架屏，禁止整页 spinner）。
 *
 * 占位尺寸必须与真实内容一致，否则加载完成时会跳版：
 *  - table：48px 高条，与全局表格行高 --sems-row-height 一致，行数由 rows 控制；
 *  - card：两张表行高的卡片块（接近 StatCard / 订单卡的实际高度），个数由 rows 控制（上限 8）；
 *  - chart：16:9 定比块，接近 ECharts 容器的实际比例。
 */
const props = defineProps({
  /** 占位行数 / 块数：table 与 card 类型生效 */
  rows: { type: Number, default: 5 },
  /** 占位形态：table | card | chart */
  type: {
    type: String,
    default: 'table',
    validator: (value) => ['table', 'card', 'chart'].includes(value),
  },
})

const tableRows = computed(() => Math.max(1, Math.min(props.rows, 50)))
const cardBlocks = computed(() => Math.max(1, Math.min(props.rows, 8)))
</script>

<template>
  <div class="page-skeleton" :class="`page-skeleton--${type}`" role="status" aria-label="加载中">
    <el-skeleton animated class="page-skeleton__body">
      <template #template>
        <!-- 表格：48px 行高条 -->
        <div v-if="type === 'table'" class="page-skeleton__table">
          <el-skeleton-item
            v-for="i in tableRows"
            :key="i"
            variant="rect"
            class="page-skeleton__row"
          />
        </div>

        <!-- 卡片流：与真实卡片高度接近的块 -->
        <div v-else-if="type === 'card'" class="page-skeleton__cards">
          <el-skeleton-item
            v-for="i in cardBlocks"
            :key="i"
            variant="rect"
            class="page-skeleton__card"
          />
        </div>

        <!-- 图表：16:9 定比块 -->
        <div v-else class="page-skeleton__chart">
          <el-skeleton-item variant="rect" class="page-skeleton__chart-block" />
        </div>
      </template>
    </el-skeleton>
  </div>
</template>

<style scoped>
.page-skeleton__table {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
}

.page-skeleton__row {
  width: 100%;
  height: var(--sems-row-height);
}

.page-skeleton__cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--sems-space-3);
}

.page-skeleton__card {
  width: 100%;
  height: calc(var(--sems-row-height) * 2);
}

.page-skeleton__chart {
  width: 100%;
}

.page-skeleton__chart-block {
  width: 100%;
  /* height: auto 必须显式覆盖 Element Plus 的 16px 默认高，否则 aspect-ratio 不生效 */
  height: auto;
  aspect-ratio: 16 / 9;
}

/* 图表骨架：外层容器有确定高度时撑满（与真实图表同高，加载完成不跳版），否则退回 16:9 定比 */
.page-skeleton--chart,
.page-skeleton--chart .page-skeleton__body,
.page-skeleton--chart .page-skeleton__chart,
.page-skeleton--chart .page-skeleton__chart-block {
  height: 100%;
}
</style>
