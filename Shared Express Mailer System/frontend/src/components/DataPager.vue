<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { DEFAULT_PAGE_SIZE, PAGE_SIZES } from '@/constants'

/**
 * 分页器 —— 全站列表统一分页件（页面不要自己拼 el-pagination）。
 *
 * 用法：
 *   <DataPager v-model:page="query.pageNum" v-model:size="query.pageSize"
 *              :total="total" @change="load" />
 *
 * 约定：
 *  - change 事件参数为 { page, size }，页面据此重新拉取数据；
 *  - 移动端（<768px）只保留 prev/pager/next，总数与每页条数在窄屏放不下；
 *  - 每页条数变化时回到第 1 页，且只派发一次 change（避免重复请求）。
 */
const props = defineProps({
  /** 当前页码（v-model:page） */
  page: { type: Number, default: 1 },
  /** 每页条数（v-model:size） */
  size: { type: Number, default: DEFAULT_PAGE_SIZE },
  /** 总条数 */
  total: { type: Number, default: 0 },
  /** 请求进行中时禁用，防止连点翻页 */
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['update:page', 'update:size', 'change'])

/* ---------- 移动端形态 ---------- */

const isMobile = ref(false)
let mediaQuery = null

function handleMediaChange(event) {
  isMobile.value = event.matches
}

onMounted(() => {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
  mediaQuery = window.matchMedia('(max-width: 767px)')
  isMobile.value = mediaQuery.matches
  if (typeof mediaQuery.addEventListener === 'function') {
    mediaQuery.addEventListener('change', handleMediaChange)
  } else if (typeof mediaQuery.addListener === 'function') {
    mediaQuery.addListener(handleMediaChange) // 兼容旧版 Safari
  }
})

onBeforeUnmount(() => {
  if (!mediaQuery) return
  if (typeof mediaQuery.removeEventListener === 'function') {
    mediaQuery.removeEventListener('change', handleMediaChange)
  } else if (typeof mediaQuery.removeListener === 'function') {
    mediaQuery.removeListener(handleMediaChange)
  }
})

const layout = computed(() =>
  isMobile.value ? 'prev, pager, next' : 'total, sizes, prev, pager, next',
)

/* ---------- 事件派发 ---------- */

/**
 * 每页条数变化后，el-pagination 可能紧跟着再抛一次 current-change，
 * 用一次性标记吞掉它，保证一次操作只派发一次 change。
 */
let ignoreNextCurrentChange = false

function handleSizeChange(size) {
  ignoreNextCurrentChange = true
  emit('update:size', size)
  emit('update:page', 1)
  emit('change', { page: 1, size })
  nextTick(() => {
    ignoreNextCurrentChange = false
  })
}

function handleCurrentChange(page) {
  if (page !== props.page) emit('update:page', page)
  if (ignoreNextCurrentChange) return
  emit('change', { page, size: props.size })
}
</script>

<template>
  <div class="data-pager" :class="{ 'is-mobile': isMobile }">
    <el-pagination
      background
      :disabled="disabled"
      :layout="layout"
      :current-page="page"
      :page-size="size"
      :page-sizes="PAGE_SIZES"
      :total="total"
      :pager-count="isMobile ? 5 : 7"
      @size-change="handleSizeChange"
      @current-change="handleCurrentChange"
    />
  </div>
</template>

<style scoped>
.data-pager {
  display: flex;
  justify-content: flex-end;
  padding-top: var(--sems-space-4);
}

.data-pager.is-mobile {
  justify-content: center;
  padding-top: var(--sems-space-3);
}
</style>
