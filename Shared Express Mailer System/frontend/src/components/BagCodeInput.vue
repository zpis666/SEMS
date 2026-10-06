<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Camera } from '@element-plus/icons-vue'
import { BAG_CODE_REGEX, bagCodeRules, toUpperCase } from '@/utils/validators'
import { createScanner } from '@/utils/scanner'

/**
 * 袋码输入框 —— 全站袋码入口统一件（用户端扫码取袋、站点端回收/出入库/消毒等复用）。
 *
 * 行为约定：
 *  - v-model 双向绑定，输入即时大写（扫码枪/H5 手输统一口径）；
 *  - 回车与「扫码」按钮都触发 scan 事件，页面据此发起业务请求；
 *  - 格式用 bagCodeRules 做提示但不阻断：允许提交系统里未知的袋码，
 *    由后端返回 4106 等错误码后按 6.4 的错误文案提示；
 *  - 扫描系统生成的二维码时（内容形如 /m/bag/SJB-…），自动从文本中提取袋码。
 */
const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '请输入或扫描袋码 SJB-L-2609-00001-4' },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'scan'])

const inputRef = ref(null)

const code = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', toUpperCase(value)),
})

/* ---------- 格式提示（非阻断） ---------- */

const touched = ref(false)

/** 直接复用 bagCodeRules 的校验器取提示文案：空值不提示，格式不符只提示、不拦提交 */
const formatError = computed(() => {
  const value = props.modelValue
  if (!value) return ''
  for (const rule of bagCodeRules) {
    if (typeof rule.validator !== 'function') continue
    let message = ''
    rule.validator(rule, value, (error) => {
      if (error) message = error.message
    })
    if (message) return message
  }
  return ''
})

const showFormatHint = computed(
  () => touched.value && !!props.modelValue && !!formatError.value,
)

/* ---------- 回车与扫码 ---------- */

function handleEnter() {
  if (props.disabled) return
  const value = toUpperCase(props.modelValue)
  if (!value) {
    ElMessage.warning('请输入或扫描袋码')
    return
  }
  emit('scan', value)
}

const scanVisible = ref(false)
const videoRef = ref(null)
let scanner = null

async function openScanner() {
  if (props.disabled) return
  scanVisible.value = true
  await nextTick() // 等取景框渲染完成，video 元素才能挂上媒体流
  scanner = createScanner()
  try {
    await scanner.start(videoRef.value, handleDecoded)
  } catch (error) {
    // 无摄像头/无权限/非安全上下文：提示后回落手动输入（scanner 已给出中文文案）
    ElMessage.error(error?.message || '扫码启动失败，请手动输入袋码')
    closeScanner()
    inputRef.value?.focus()
  }
}

/** 从识别文本中提取袋码：二维码内容可能是 /m/bag/袋码 或完整 URL */
const BAG_CODE_IN_TEXT = new RegExp(
  BAG_CODE_REGEX.source.replace(/^\^/, '').replace(/\$$/, ''),
)

function extractBagCode(text) {
  const normalized = toUpperCase(text)
  if (!normalized) return ''
  const matched = normalized.match(BAG_CODE_IN_TEXT)
  return matched ? matched[0] : normalized
}

function handleDecoded(text) {
  const value = extractBagCode(text)
  if (!value) return
  emit('update:modelValue', value)
  closeScanner()
  emit('scan', value)
}

function closeScanner() {
  scanVisible.value = false
}

function stopScanner() {
  if (!scanner) return
  scanner.stop()
  scanner = null
}

watch(scanVisible, (visible) => {
  if (!visible) stopScanner()
})

onBeforeUnmount(stopScanner)
</script>

<template>
  <div class="bag-code-input">
    <el-input
      ref="inputRef"
      v-model="code"
      :placeholder="placeholder"
      :disabled="disabled"
      clearable
      @blur="touched = true"
      @keyup.enter="handleEnter"
    >
      <template #append>
        <el-button :icon="Camera" :disabled="disabled" @click="openScanner">扫码</el-button>
      </template>
    </el-input>

    <p v-if="showFormatHint" class="bag-code-input__hint">{{ formatError }}</p>

    <el-dialog
      v-model="scanVisible"
      title="扫描袋码"
      width="min(360px, 92vw)"
      align-center
      append-to-body
      :close-on-click-modal="false"
    >
      <div class="bag-code-input__viewport">
        <video ref="videoRef" class="bag-code-input__video" muted playsinline />
        <div class="bag-code-input__frame" aria-hidden="true" />
      </div>
      <p class="bag-code-input__tip">将袋码二维码放入取景框内，识别成功后自动填入</p>
    </el-dialog>
  </div>
</template>

<style scoped>
.bag-code-input {
  width: 100%;
}

.bag-code-input__hint {
  margin: var(--sems-space-1) 0 0;
  font-size: var(--sems-font-size-xs);
  color: var(--sems-color-danger);
}

.bag-code-input__viewport {
  position: relative;
  width: 100%;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  background-color: var(--sems-bg-hover);
  border-radius: var(--sems-radius-base);
}

.bag-code-input__video {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.bag-code-input__frame {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 60%;
  aspect-ratio: 1;
  transform: translate(-50%, -50%);
  border: 2px solid var(--sems-color-primary);
  border-radius: var(--sems-radius-base);
}

.bag-code-input__tip {
  margin: var(--sems-space-3) 0 0;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
  text-align: center;
}
</style>
