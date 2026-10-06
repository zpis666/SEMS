<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'

/**
 * 批量接口结果弹窗 —— 回收确认 / 出入库 / 消毒等批量操作的统一结果反馈。
 *
 * results 元素形如 { bagCode, success, code, message }（后端逐袋部分成功的返回口径）：
 *  - 顶部统计「成功 N / 失败 M」；
 *  - 表格逐袋列出结果，失败项用危险色标注错误码与原因；
 *  - 提供「复制失败清单」，便于登记工单或转交处理。
 *
 * 顶部统计口径：total / failed 为可选的「后端信封计数」，传入时直接采用——批量上限 500，
 * items 可能被后端截断（明细只回传前 N 条），计数以后端为准，不能只数字组长度；
 * 未传入时回退到按 results 现算（兼容未接入统一信封的其它调用点）。
 * extraSummary 为可选补充行，用于展示批次级口径（如回收确认的审计动作计数）。
 */
const props = defineProps({
  /** 显隐（v-model） */
  modelValue: { type: Boolean, default: false },
  /** 逐袋结果明细（后端统一信封 data.items） */
  results: { type: Array, default: () => [] },
  /** 弹窗标题 */
  title: { type: String, default: '批量执行结果' },
  /**
   * 本批总数（后端信封 data.total，可选）。
   * 批量上限 500，items 可能被后端截断，计数以后端为准；传入时顶部统计不再按 results 长度现算。
   */
  total: { type: Number, default: null },
  /** 本批失败数（后端信封 data.failed，可选；传入时同上以后端计数为准） */
  failed: { type: Number, default: null },
  /** 可选补充说明行（如回收确认的「跨站 / 未标记直投 / 代操作」审计口径计数），为空不展示 */
  extraSummary: { type: String, default: '' },
})

const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

/** 按 results 逐条现算的成功数：仅在后端未给批次计数时作为回退口径 */
const itemSuccessCount = computed(() => props.results.filter((item) => item && item.success).length)
const failedResults = computed(() => props.results.filter((item) => item && !item.success))

/** 失败数：优先用后端信封的 failed（items 可能被截断），缺失时按 results 现算 */
const failedCount = computed(() =>
  Number.isFinite(props.failed) ? props.failed : failedResults.value.length,
)

/** 成功数：后端同时给出 total 与 failed 时用 total - failed（信封口径 success = total - failed），否则按 results 现算 */
const successCount = computed(() =>
  Number.isFinite(props.total) && Number.isFinite(props.failed)
    ? Math.max(props.total - props.failed, 0)
    : itemSuccessCount.value,
)

/** 总数：优先用后端信封的 total，缺失时按 results 现算 */
const totalCount = computed(() =>
  Number.isFinite(props.total) ? props.total : props.results.length,
)

/** 复制失败清单：一行一只，含袋码、错误码与原因（只复制明细中可见的失败行） */
async function copyFailed() {
  if (!failedResults.value.length) {
    ElMessage.info('明细中没有可复制的失败项')
    return
  }
  const text = failedResults.value
    .map((item) =>
      [item.bagCode, item.code ? `错误码 ${item.code}` : '', item.message || '']
        .filter(Boolean)
        .join('  '),
    )
    .join('\n')
  try {
    await copyText(text)
    ElMessage.success('失败清单已复制到剪贴板')
  } catch (error) {
    ElMessage.error('复制失败，请手动选择表格内容复制')
  }
}

/** 剪贴板：安全上下文优先 navigator.clipboard，不可用时降级 textarea + execCommand */
function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) {
    return navigator.clipboard.writeText(text)
  }
  return new Promise((resolve, reject) => {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.setAttribute('readonly', '')
    textarea.style.position = 'fixed'
    textarea.style.top = '-9999px'
    document.body.appendChild(textarea)
    textarea.select()
    let ok = false
    try {
      ok = document.execCommand('copy')
    } catch (error) {
      ok = false
    }
    document.body.removeChild(textarea)
    if (ok) resolve()
    else reject(new Error('复制失败'))
  })
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="min(680px, 94vw)"
    align-center
    :close-on-click-modal="false"
  >
    <div class="batch-result__head">
      <div class="batch-result__summary">
        <span class="batch-result__stat is-success">成功 {{ successCount }}</span>
        <span class="batch-result__divider">/</span>
        <span class="batch-result__stat is-failed">失败 {{ failedCount }}</span>
        <span class="batch-result__total">共 {{ totalCount }} 只</span>
      </div>
      <!-- 批次级补充口径（如回收确认的审计动作计数）：跨站 / 未标记直投 / 代操作 -->
      <p v-if="extraSummary" class="batch-result__extra">{{ extraSummary }}</p>
    </div>

    <EmptyState v-if="!results.length" description="暂无执行结果" />

    <el-table v-else :data="results" max-height="360">
      <el-table-column prop="bagCode" label="袋码" min-width="200">
        <template #default="{ row }">
          <span class="sems-mono">{{ row.bagCode }}</span>
        </template>
      </el-table-column>

      <el-table-column label="结果" width="100">
        <template #default="{ row }">
          <span class="batch-result__badge" :class="row.success ? 'is-success' : 'is-failed'">
            <el-icon>
              <component :is="row.success ? 'CircleCheck' : 'CircleClose'" />
            </el-icon>
            <span>{{ row.success ? '成功' : '失败' }}</span>
          </span>
        </template>
      </el-table-column>

      <el-table-column label="错误码" width="100">
        <template #default="{ row }">
          <span v-if="row.success">—</span>
          <span v-else class="batch-result__code sems-num">{{ row.code || '—' }}</span>
        </template>
      </el-table-column>

      <el-table-column label="原因" min-width="180">
        <template #default="{ row }">
          <span :class="{ 'batch-result__reason': !row.success }">{{ row.message || '—' }}</span>
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <div class="batch-result__footer">
        <el-button :disabled="failedResults.length === 0" @click="copyFailed">复制失败清单</el-button>
        <el-button type="primary" @click="visible = false">关闭</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.batch-result__head {
  margin-bottom: var(--sems-space-3);
}

.batch-result__summary {
  display: flex;
  align-items: baseline;
  gap: var(--sems-space-2);
}

.batch-result__extra {
  margin: var(--sems-space-1) 0 0;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
}

.batch-result__stat {
  font-size: var(--sems-font-size-base);
  font-weight: var(--sems-font-weight-bold);
}

.batch-result__stat.is-success {
  color: var(--sems-color-success);
}

.batch-result__stat.is-failed {
  color: var(--sems-color-danger);
}

.batch-result__divider {
  color: var(--sems-text-secondary);
}

.batch-result__total {
  margin-left: auto;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
}

.batch-result__badge {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-1);
  font-size: var(--sems-font-size-sm);
}

.batch-result__badge.is-success {
  color: var(--sems-color-success);
}

.batch-result__badge.is-failed {
  color: var(--sems-color-danger);
}

.batch-result__code,
.batch-result__reason {
  color: var(--sems-color-danger);
}

.batch-result__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--sems-space-2);
}
</style>
