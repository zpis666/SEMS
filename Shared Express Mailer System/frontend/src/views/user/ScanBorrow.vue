<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getBagInfo } from '@/api/bag'
import { borrow } from '@/api/order'
import { BAG_CODE_REGEX, toUpperCase } from '@/utils/validators'
import { createScanner } from '@/utils/scanner'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import BagCodeInput from '@/components/BagCodeInput.vue'
import StatusTag from '@/components/StatusTag.vue'

/**
 * 扫码取袋主流程（《系统设计说明书》3.4.1 取袋流程、5.2 M2、10.5「扫码用全屏结果卡反馈」）。
 *
 * 流程：扫码/手输 → GET /bags/{bagCode}/info → 确认卡（规格 / 所在站点 / 是否可领用）
 *      → 二次确认 → POST /borrows → 全屏结果卡（成功绿 / 失败红，配文案与提示音）。
 *
 * 失败按错误码给更具体的指引（错误编号见设计书 6.4）：4101 库存不足 / 4103 并发被领走 /
 * 4104 在持袋数超限 / 4107 袋不可用 / 4109 今日次数超限 / 4110 被调拨锁定 / 5102·5103 可重试；
 * 其余错误直接用后端返回的中文 message（设计书 6.6 前端提示约定）。
 *
 * 摄像头策略（复用 utils/scanner）：无 mediaDevices（老浏览器/非安全上下文）时隐藏取景区，
 * 只保留手输兜底；权限被拒等运行期错误提示后可重试，手输入口始终可用。
 */

const route = useRoute()
const router = useRouter()

/** 页面阶段：scan 扫码录入 / loading 查询袋信息 / query-error 查询失败 / confirm 确认卡 / result 全屏结果卡 */
const stage = ref('scan')
const bagCode = ref('')
const bagInfo = ref(null)
const queryError = ref('')
const borrowError = ref(null)
const borrowResult = ref(null)
const submitting = ref(false)

/* ------------------------------ 摄像头取景 ------------------------------ */

const videoRef = ref(null)
/** mediaDevices 不可用时（老浏览器/非安全上下文）直接隐藏取景区，只留手输（契约要求） */
const cameraSupported = ref(
  typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia,
)
const cameraActive = ref(false)
/** createScanner() 返回的扫描器实例，离开扫描阶段或卸载时 stop() */
let scanController = null
/** 单轮扫码只处理一次识别结果 */
let scanHandled = false

async function startCamera() {
  if (!cameraSupported.value || !videoRef.value) return
  scanHandled = false
  try {
    const scanner = createScanner()
    scanController = scanner
    cameraActive.value = true
    await scanner.start(videoRef.value, onCodeDetected)
  } catch (error) {
    // 权限被拒 / 无可用摄像头 / 扫码组件加载失败：scanner 已给出中文文案，直接透传
    stopCamera()
    ElMessage.warning(error?.message || '摄像头启动失败，请在下方手动输入袋码')
  }
}

/** 关闭摄像头（可重复调用），避免离开页面后摄像头指示灯常亮 */
function stopCamera() {
  try {
    scanController?.stop()
  } catch {
    /* 停止异常不影响页面流转 */
  }
  scanController = null
  cameraActive.value = false
}

/* ------------------------------ 提示音（10.5：结果卡配文案与提示音） ------------------------------ */

let audioCtx = null

function ensureAudio() {
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext
    if (!Ctx) return null
    if (!audioCtx) audioCtx = new Ctx()
    if (audioCtx.state === 'suspended') audioCtx.resume()
    return audioCtx
  } catch {
    return null
  }
}

function playBeep(success) {
  try {
    const ctx = ensureAudio()
    if (!ctx) return
    const oscillator = ctx.createOscillator()
    const gain = ctx.createGain()
    oscillator.type = 'sine'
    oscillator.frequency.value = success ? 880 : 220
    gain.gain.setValueAtTime(0.08, ctx.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.18)
    oscillator.connect(gain)
    gain.connect(ctx.destination)
    oscillator.start()
    oscillator.stop(ctx.currentTime + 0.2)
  } catch {
    /* 提示音是增强项，浏览器兼容问题不应影响结果展示 */
  }
}

/* ------------------------------ 袋码解析与查询 ------------------------------ */

/**
 * 二维码内容是袋详情链接（设计书 7.4：https://{域名}/m/bag/{袋码}），
 * 这里从任意扫得文本中提取袋码；直接扫到袋码文本时原样返回。
 */
function extractBagCode(raw) {
  const text = toUpperCase(String(raw || ''))
  const match = text.match(/(SJB-[A-Z]-\d{4}-\d{5}-[0-9A-Z])/)
  return match ? match[1] : text
}

function onCodeDetected(raw) {
  if (scanHandled) return
  const code = extractBagCode(raw)
  if (!code) return
  scanHandled = true
  stopCamera()
  try {
    navigator.vibrate?.(80)
  } catch {
    /* 无震动能力则忽略 */
  }
  ElMessage.success(`已扫到袋码 ${code}`)
  handleScanInput(code)
}

/** 手动输入 / 扫码枪回车 / 子组件 @scan 统一入口 */
function handleScanInput(payload) {
  const code = extractBagCode(typeof payload === 'string' && payload ? payload : bagCode.value)
  if (!BAG_CODE_REGEX.test(code)) {
    ElMessage.warning('袋码格式形如 SJB-L-2609-00001-4，请核对后重试')
    return
  }
  stopCamera()
  bagCode.value = code
  queryBag(code)
}

function bagInfoErrorMessage(error) {
  if (error?.code === 4106) return '没有找到这只袋，请确认袋码是否正确'
  if (error?.code === 4107) return '这只袋当前不可用（未激活、已报废或已遗失）'
  return error?.message || '袋信息查询失败，请重试'
}

async function queryBag(code) {
  stage.value = 'loading'
  queryError.value = ''
  try {
    bagInfo.value = await getBagInfo(code)
    stage.value = 'confirm'
  } catch (error) {
    bagInfo.value = null
    queryError.value = bagInfoErrorMessage(error)
    stage.value = 'query-error'
  }
}

/* ------------------------------ 申领 ------------------------------ */

/** 按错误码给更具体的指引（设计书 6.4 错误码表）；未命中时用后端中文 message */
const BORROW_ERROR_COPY = {
  4101: '站点库存不足，这只袋暂时无法申领，请稍后重试或换一只袋',
  4103: '手慢了，这只袋刚被别人领走',
  4104: '您在持袋数已达上限，请先归还手上的袋',
  4107: '这只袋暂不可用',
  4109: '今日取袋次数已用完',
  4110: '这只袋已被调拨单锁定',
  5102: '库存服务正在初始化，请稍后重试',
  5103: '库存对账中，请稍后重试',
}

function borrowErrorMessage(error) {
  return BORROW_ERROR_COPY[error?.code] || error?.message || '申领失败，请重试'
}

async function confirmBorrow() {
  if (!bagInfo.value?.available || submitting.value) return
  try {
    await ElMessageBox.confirm(
      `确认申领袋 ${bagCode.value} 吗？申领后将占用 1 个在持额度，请及时填写寄件信息。`,
      '二次确认',
      { confirmButtonText: '确认申领', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    return // 用户取消
  }
  submitting.value = true
  ensureAudio() // 在用户手势内初始化提示音，规避浏览器自动播放限制
  try {
    borrowResult.value = await borrow({ bagCode: bagCode.value })
    borrowError.value = null
    playBeep(true)
  } catch (error) {
    borrowError.value = { code: error?.code, message: borrowErrorMessage(error) }
    playBeep(false)
  } finally {
    submitting.value = false
  }
}

/** 结果卡「重试」：对同一只袋重发申领（写操作由 request.js 注入新的幂等 ID） */
async function retryBorrow() {
  if (submitting.value) return
  submitting.value = true
  borrowError.value = null
  try {
    borrowResult.value = await borrow({ bagCode: bagCode.value })
    playBeep(true)
  } catch (error) {
    borrowError.value = { code: error?.code, message: borrowErrorMessage(error) }
  } finally {
    submitting.value = false
  }
}

function goShipping() {
  router.push(`/m/orders/${borrowResult.value.orderNo}/shipping`)
}

/** 回到扫码录入初始态 */
function resetScan() {
  stage.value = 'scan'
  bagCode.value = ''
  bagInfo.value = null
  queryError.value = ''
  borrowError.value = null
  borrowResult.value = null
  scanHandled = false
}

onMounted(() => {
  // 从袋详情页「去申领」跳转时带袋码，直接发起查询，少一步操作
  if (route.query.bagCode) {
    const preset = extractBagCode(route.query.bagCode)
    if (BAG_CODE_REGEX.test(preset)) {
      bagCode.value = preset
      queryBag(preset)
    }
  }
})

onBeforeUnmount(stopCamera)
</script>

<template>
  <div class="scan">
    <!-- ① 扫码 / 手输 -->
    <template v-if="stage === 'scan'">
      <PageCard title="扫码取袋" subtitle="将袋上二维码对准取景框，或在下方输入袋码">
        <div v-if="cameraSupported" class="viewfinder">
          <video ref="videoRef" class="viewfinder__video" autoplay playsinline muted />
          <div class="viewfinder__frame" aria-hidden="true">
            <span class="viewfinder__corner is-tl" />
            <span class="viewfinder__corner is-tr" />
            <span class="viewfinder__corner is-bl" />
            <span class="viewfinder__corner is-br" />
          </div>
          <button
            v-if="!cameraActive"
            type="button"
            class="viewfinder__start"
            @click="startCamera"
          >
            <el-icon :size="24"><Camera /></el-icon>
            <span>点击开始摄像头扫码</span>
          </button>
        </div>
        <p v-else class="scan__camera-note sems-text-muted">
          当前环境无法使用摄像头，请在下方手动输入袋码
        </p>

        <BagCodeInput v-model="bagCode" @scan="handleScanInput" />

        <p class="scan__hint sems-text-muted">支持扫码枪直接回车提交，袋码格式形如 SJB-L-2609-00001-4</p>
        <el-button
          type="primary"
          class="scan__submit"
          :disabled="!bagCode"
          @click="handleScanInput()"
        >
          查询袋信息
        </el-button>
      </PageCard>
    </template>

    <!-- ② 查询中：骨架屏（占位与确认卡一致） -->
    <PageCard v-else-if="stage === 'loading'" title="袋信息">
      <PageSkeleton type="card" :rows="4" />
    </PageCard>

    <!-- ③ 查询失败：错误态给「重试」 -->
    <PageCard v-else-if="stage === 'query-error'" title="查询失败">
      <div class="scan__error">
        <el-icon :size="40" class="scan__error-icon"><WarningFilled /></el-icon>
        <p>{{ queryError }}</p>
        <div class="scan__error-actions">
          <el-button @click="resetScan">重新输入</el-button>
          <el-button type="primary" @click="queryBag(bagCode)">重试</el-button>
        </div>
      </div>
    </PageCard>

    <!-- ④ 确认卡 -->
    <PageCard v-else-if="stage === 'confirm'" title="确认申领" subtitle="请核对袋信息后再提交">
      <div class="confirm">
        <div class="confirm__row">
          <span class="confirm__label">袋码</span>
          <span class="confirm__value sems-mono">{{ bagInfo?.bagCode || bagCode }}</span>
        </div>
        <div class="confirm__row">
          <span class="confirm__label">规格</span>
          <span class="confirm__value">{{ bagInfo?.spec || '—' }}</span>
        </div>
        <div class="confirm__row">
          <span class="confirm__label">所在站点</span>
          <span class="confirm__value">{{ bagInfo?.stationName || '—' }}</span>
        </div>
        <div v-if="bagInfo?.status" class="confirm__row">
          <span class="confirm__label">袋状态</span>
          <StatusTag kind="bag" :value="bagInfo.status" />
        </div>
        <div class="confirm__row">
          <span class="confirm__label">是否可领用</span>
          <span v-if="bagInfo?.available" class="confirm__available is-ok">
            <el-icon><CircleCheck /></el-icon>
            <span>可领用</span>
          </span>
          <span v-else class="confirm__available is-no">
            <el-icon><CircleClose /></el-icon>
            <span>暂不可领用</span>
          </span>
        </div>
      </div>

      <div class="confirm__actions">
        <el-button @click="resetScan">重新扫码</el-button>
        <el-button
          type="primary"
          :disabled="!bagInfo?.available"
          :loading="submitting"
          @click="confirmBorrow"
        >
          确认申领
        </el-button>
      </div>
    </PageCard>
  </div>

  <!-- ⑤ 全屏结果卡（成功绿 / 失败红 + 文案 + 提示音） -->
  <div
    v-if="stage === 'result'"
    class="result"
    :class="borrowResult ? 'is-success' : 'is-fail'"
    role="status"
  >
    <div class="result__card">
      <el-icon :size="56" class="result__icon">
        <CircleCheckFilled v-if="borrowResult" />
        <CircleCloseFilled v-else />
      </el-icon>
      <h2 class="result__title">{{ borrowResult ? '申领成功' : '申领失败' }}</h2>

      <template v-if="borrowResult">
        <p class="result__order">
          订单号
          <span class="sems-mono">{{ borrowResult.orderNo }}</span>
        </p>
        <p v-if="borrowResult.stationName" class="result__line sems-text-muted">
          取袋站点：{{ borrowResult.stationName }}
        </p>
        <p v-if="borrowResult.expiresHint" class="result__line sems-text-muted">
          {{ borrowResult.expiresHint }}
        </p>
        <p class="result__tip">请尽快填写寄件信息，超时未寄件的订单将自动取消</p>
      </template>

      <template v-else>
        <p class="result__reason">{{ borrowError?.message || '申领失败，请重试' }}</p>
        <p v-if="borrowError?.code === 4104" class="result__line sems-text-muted">
          归还闭环完成前额度不会释放，可在「我的订单」中标记归还
        </p>
      </template>

      <div class="result__actions">
        <template v-if="borrowResult">
          <el-button type="primary" @click="goShipping">去填写寄件信息</el-button>
        </template>
        <template v-else>
          <el-button type="primary" :loading="submitting" @click="retryBorrow">重试</el-button>
          <el-button @click="resetScan">重新扫码</el-button>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scan {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-4);
}

/* ---------- 取景框 ---------- */
.viewfinder {
  position: relative;
  overflow: hidden;
  aspect-ratio: 4 / 3;
  margin-bottom: var(--sems-space-4);
  border-radius: var(--sems-radius-base);
  background-color: var(--sems-bg-sidebar);
}

.viewfinder__video {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.viewfinder__frame {
  position: absolute;
  inset: 15%;
  pointer-events: none;
}

.viewfinder__corner {
  position: absolute;
  width: 28px;
  height: 28px;
  border: 3px solid var(--sems-text-inverse);
}

.viewfinder__corner.is-tl {
  top: 0;
  left: 0;
  border-right: none;
  border-bottom: none;
}

.viewfinder__corner.is-tr {
  top: 0;
  right: 0;
  border-left: none;
  border-bottom: none;
}

.viewfinder__corner.is-bl {
  bottom: 0;
  left: 0;
  border-top: none;
  border-right: none;
}

.viewfinder__corner.is-br {
  right: 0;
  bottom: 0;
  border-top: none;
  border-left: none;
}

.viewfinder__start {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sems-space-2);
  border: none;
  background-color: transparent;
  color: var(--sems-text-inverse);
  font-size: var(--sems-font-size-lg);
  cursor: pointer;
}

.scan__camera-note {
  margin: 0 0 var(--sems-space-4);
}

.scan__hint {
  margin: var(--sems-space-2) 0 var(--sems-space-3);
}

.scan__submit {
  width: 100%;
  height: var(--sems-btn-height-touch);
  font-size: var(--sems-font-size-lg);
}

/* ---------- 错误态 ---------- */
.scan__error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-3);
  padding: var(--sems-space-5) 0;
  text-align: center;
  color: var(--sems-text-secondary);
}

.scan__error p {
  margin: 0;
}

.scan__error-icon {
  color: var(--sems-color-warning);
}

.scan__error-actions {
  display: flex;
  gap: var(--sems-space-3);
}

/* ---------- 确认卡 ---------- */
.confirm {
  display: flex;
  flex-direction: column;
}

.confirm__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-3);
  min-height: var(--sems-touch-target);
  border-bottom: 1px solid var(--sems-border);
}

.confirm__row:last-child {
  border-bottom: none;
}

.confirm__label {
  flex: none;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
}

.confirm__value {
  text-align: right;
  color: var(--sems-text-primary);
  word-break: break-all;
}

.confirm__available {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-1);
  font-size: var(--sems-font-size-base);
}

.confirm__available.is-ok {
  color: var(--sems-color-success);
}

.confirm__available.is-no {
  color: var(--sems-color-warning);
}

.confirm__actions {
  display: flex;
  gap: var(--sems-space-3);
  margin-top: var(--sems-space-4);
}

.confirm__actions :deep(.el-button) {
  flex: 1;
  height: var(--sems-btn-height-touch);
  font-size: var(--sems-font-size-lg);
}

.confirm__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

/* ---------- 全屏结果卡 ---------- */
.result {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sems-space-4);
  padding-bottom: calc(var(--sems-space-4) + env(safe-area-inset-bottom));
  background-color: var(--sems-bg-page);
}

.result__card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-3);
  width: 100%;
  max-width: var(--sems-h5-max-width);
  padding: var(--sems-space-6) var(--sems-space-4);
  border-radius: var(--sems-radius-card);
  background-color: var(--sems-bg-card);
  box-shadow: var(--sems-shadow-card);
  text-align: center;
}

.result.is-success .result__icon {
  color: var(--sems-color-success);
}

.result.is-fail .result__icon {
  color: var(--sems-color-danger);
}

.result__title {
  margin: 0;
  font-size: var(--sems-font-size-xl);
  font-weight: var(--sems-font-weight-bold);
  color: var(--sems-text-primary);
}

.result__order {
  margin: 0;
  font-size: var(--sems-font-size-base);
  color: var(--sems-text-primary);
}

.result__line {
  margin: 0;
}

.result__reason {
  margin: 0;
  font-size: var(--sems-font-size-base);
  color: var(--sems-text-primary);
}

.result__tip {
  margin: 0;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
}

.result__actions {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
  width: 100%;
  margin-top: var(--sems-space-2);
}

.result__actions :deep(.el-button) {
  width: 100%;
  height: var(--sems-btn-height-touch);
  font-size: var(--sems-font-size-lg);
}

.result__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
</style>
