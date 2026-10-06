<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { getCaptcha } from '@/api/auth'
import { phoneRules } from '@/utils/validators'

/**
 * 登录页（契约 4.1）。
 *
 * 要点：
 *  1) 手机号 + 密码登录，会话写入走 stores/user 的 login()；
 *  2) 错误码 1002 = 账号已锁定，剩余锁定时间由后端 message 携带，原样展示；
 *  3) 「连续失败 5 次、锁定期满后的首次登录」须一次性图形验证码：验证码行默认收起，
 *     当后端返回需要验证码的业务错误时自动展开并拉取图片，也可点「需要验证码？」手动展开；
 *     登录请求仅在验证码行展开时才携带 captchaId / captchaCode；
 *  4) 登录成功后：强制改密账号先跳 /change-password，否则回跳 redirect 或角色首页 homePath。
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref(null)
const form = reactive({ phone: '', password: '', captchaCode: '' })

/** 验证码行状态（默认不展示） */
const captchaVisible = ref(false)
const captchaId = ref('')
const captchaImage = ref('')
const captchaLoading = ref(false)

const submitting = ref(false)
/** 表单级常驻错误：账号锁定、需要验证码等场景（轻提示由 request.js 统一弹，这里负责停留展示） */
const formError = ref('')

/** 登录只校验「已填写」；密码强度由注册/改密把关，避免历史账号被前端规则误挡 */
const rules = computed(() => ({
  phone: phoneRules,
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: captchaVisible.value
    ? [{ required: true, message: '请输入图形验证码', trigger: 'blur' }]
    : [],
}))

/** 登录后回跳地址：只接受站内路径，拒绝 //evil.com 这类协议相对地址 */
function readRedirect() {
  const raw = route.query.redirect
  if (typeof raw !== 'string') return ''
  if (!raw.startsWith('/') || raw.startsWith('//')) return ''
  return raw
}

/** 验证码图片：后端返回裸 base64，这里兼容已带 data: 前缀的情况 */
function normalizeCaptcha(base64) {
  if (!base64) return ''
  return base64.startsWith('data:') ? base64 : `data:image/png;base64,${base64}`
}

async function refreshCaptcha() {
  if (captchaLoading.value) return
  captchaLoading.value = true
  try {
    const data = await getCaptcha()
    if (!data?.captchaId || !data?.imageBase64) {
      throw new Error('验证码数据不完整')
    }
    captchaId.value = data.captchaId
    captchaImage.value = normalizeCaptcha(data.imageBase64)
    form.captchaCode = ''
  } catch {
    // 取码失败：留「点击重试」入口（错误态），不阻塞账号密码登录路径
    captchaId.value = ''
    captchaImage.value = ''
  } finally {
    captchaLoading.value = false
  }
}

async function openCaptcha() {
  captchaVisible.value = true
  if (!captchaImage.value) await refreshCaptcha()
}

function onCaptchaImgError() {
  captchaImage.value = ''
}

/**
 * 判断后端错误是否在要求图形验证码。
 * 「锁定期满后首登」需先过验证码，当前错误码表未单列此码，故按三个信号综合判定
 * （任一命中即视为需要），待后端定稿后收敛为单一错误码：
 *  1) error.data.captchaRequired === true（后端补该标志时优先命中）；
 *  2) error.data（3001 字段级明细）里出现 captcha 相关字段；
 *  3) 错误文案包含「验证码」。
 */
function needsCaptcha(error) {
  if (!error) return false
  const data = error.data
  if (data && typeof data === 'object') {
    if (data.captchaRequired === true) return true
    if (Object.keys(data).some((key) => key.toLowerCase().includes('captcha'))) return true
  }
  return typeof error.message === 'string' && error.message.includes('验证码')
}

function handleLoginError(error) {
  if (!error) return

  // 1002：账号已锁定。剩余锁定时间由后端 message 携带，原样展示
  if (error.code === 1002) {
    formError.value = error.message || '账号已锁定，请稍后再试'
    return
  }

  // 后端要求图形验证码：展开验证码行并立即取一张图
  if (needsCaptcha(error)) {
    formError.value = error.message || '本次登录需要输入图形验证码'
    captchaVisible.value = true
    refreshCaptcha()
    return
  }

  // 验证码一次性：只要本轮用过验证码，失败后一律换图，避免复用被拒绝
  if (captchaVisible.value) refreshCaptcha()

  // 3001 由 request.js 留给表单侧处理（它不弹全局提示），这里补一条轻提示
  if (error.code === 3001 && error.message) ElMessage.error(error.message)
}

async function handleLogin() {
  if (submitting.value) return
  formError.value = ''
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload = {
      phone: form.phone.trim(),
      password: form.password,
    }
    // 验证码仅在已展开时携带，未展开不传这两个字段
    if (captchaVisible.value) {
      payload.captchaId = captchaId.value
      payload.captchaCode = form.captchaCode.trim()
    }
    await userStore.login(payload)
    ElMessage.success('登录成功')
    if (userStore.mustChangePassword) {
      router.replace('/change-password')
      return
    }
    router.replace(readRedirect() || userStore.homePath)
  } catch (error) {
    handleLoginError(error)
  } finally {
    submitting.value = false
  }
}

/** 一键填入演示账号：初始化脚本预置的平台管理员（13800000000 / Sems@2026，首次登录须改密） */
function fillDemo() {
  form.phone = '13800000000'
  form.password = 'Sems@2026'
  formError.value = ''
}
</script>

<template>
  <div class="auth-page">
    <!-- 桌面端品牌区：<768px 由全局 .sems-desktop-only 隐藏，移动端只看表单 -->
    <aside class="auth-page__brand sems-desktop-only">
      <div class="auth-brand">
        <span class="auth-brand__badge">
          <el-icon class="auth-brand__badge-icon"><Van /></el-icon>
        </span>
        <p class="auth-brand__en">SEMS</p>
        <h1 class="auth-brand__title">共享快递袋系统</h1>
        <p class="auth-brand__desc">让快递袋在「取—用—还—洗—调」中循环起来，一次投放、多次复用。</p>
        <ul class="auth-brand__points">
          <li>
            <el-icon class="auth-brand__point-icon"><CircleCheckFilled /></el-icon>
            扫码取袋、一键寄件，全流程线上留痕
          </li>
          <li>
            <el-icon class="auth-brand__point-icon"><CircleCheckFilled /></el-icon>
            站点出入库、消毒、维修与调拨，闭环管理每只袋
          </li>
          <li>
            <el-icon class="auth-brand__point-icon"><CircleCheckFilled /></el-icon>
            库存与在途实时可见，超时、异常自动预警
          </li>
        </ul>
      </div>
    </aside>

    <main class="auth-page__panel">
      <section class="auth-card">
        <h2 class="auth-card__title">登录</h2>
        <p class="auth-card__subtitle sems-text-muted">欢迎回来，请使用手机号登录</p>

        <el-alert
          v-if="formError"
          class="auth-card__alert"
          type="error"
          :title="formError"
          :closable="false"
          show-icon
        />

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="handleLogin"
        >
          <el-form-item label="手机号" prop="phone">
            <el-input
              v-model="form.phone"
              placeholder="请输入 11 位手机号"
              maxlength="11"
              autocomplete="username"
            >
              <template v-slot:prefix>
                <el-icon><Iphone /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              show-password
              autocomplete="current-password"
            >
              <template v-slot:prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <!-- 图形验证码：默认收起；后端要求（或点「需要验证码？」）时展开 -->
          <el-form-item v-if="captchaVisible" label="图形验证码" prop="captchaCode">
            <div class="auth-captcha">
              <el-input
                v-model="form.captchaCode"
                class="auth-captcha__input"
                placeholder="请输入图中字符"
                maxlength="8"
              />
              <button
                class="auth-captcha__image"
                type="button"
                title="点击刷新"
                aria-label="刷新图形验证码"
                @click="refreshCaptcha"
              >
                <img v-if="captchaImage" :src="captchaImage" alt="图形验证码" @error="onCaptchaImgError" />
                <span v-else-if="captchaLoading" class="auth-captcha__hint">加载中…</span>
                <span v-else class="auth-captcha__hint">点击重试</span>
              </button>
            </div>
          </el-form-item>

          <div class="auth-card__helper">
            <el-button v-if="!captchaVisible" link type="primary" @click="openCaptcha">需要验证码？</el-button>
            <span v-else class="sems-text-muted">看不清？点击图片可刷新</span>
          </div>

          <el-button class="auth-card__submit" type="primary" size="large" native-type="submit" :loading="submitting">
            登 录
          </el-button>
        </el-form>

        <div class="auth-demo">
          <p class="auth-demo__title">演示账号（答辩演示用）</p>
          <p class="auth-demo__line">手机号 13800000000 · 初始口令 Sems@2026</p>
          <p class="auth-demo__note sems-text-muted">初始化脚本预置的平台管理员，首次登录须修改密码。</p>
          <el-button size="small" @click="fillDemo">填入演示账号</el-button>
        </div>

        <p class="auth-card__footer">
          还没有账号？
          <el-button link type="primary" @click="router.push('/register')">去注册</el-button>
        </p>
      </section>
    </main>
  </div>
</template>

<style scoped>
.auth-page {
  display: grid;
  min-height: 100dvh;
  background-color: var(--sems-bg-page);
}

/* 桌面端：左品牌 + 右表单；移动端单列（品牌区由全局 .sems-desktop-only 隐藏） */
@media (min-width: 768px) {
  .auth-page {
    grid-template-columns: minmax(0, 1fr) 480px;
  }
}

.auth-page__brand {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sems-space-6);
  background-color: var(--sems-bg-card);
  border-right: 1px solid var(--sems-border);
}

.auth-brand {
  max-width: 420px;
}

.auth-brand__badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: var(--sems-touch-target);
  height: var(--sems-touch-target);
  border-radius: var(--sems-radius-base);
  background-color: var(--sems-color-primary-bg);
  color: var(--sems-color-primary);
}

.auth-brand__badge-icon {
  font-size: var(--sems-font-size-xl);
}

.auth-brand__en {
  margin: var(--sems-space-4) 0 0;
  font-size: var(--sems-font-size-xl);
  font-weight: var(--sems-font-weight-bold);
  letter-spacing: 0.08em;
  color: var(--sems-color-primary);
}

.auth-brand__title {
  margin: var(--sems-space-1) 0 0;
  font-size: var(--sems-font-size-kpi);
  font-weight: var(--sems-font-weight-bold);
  color: var(--sems-text-primary);
}

.auth-brand__desc {
  margin: var(--sems-space-3) 0 0;
  color: var(--sems-text-secondary);
  line-height: 1.7;
}

.auth-brand__points {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
  margin: var(--sems-space-5) 0 0;
  padding: 0;
  list-style: none;
}

.auth-brand__points li {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  color: var(--sems-text-regular);
}

.auth-brand__point-icon {
  color: var(--sems-color-success);
}

.auth-page__panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sems-space-4);
}

@media (min-width: 768px) {
  .auth-page__panel {
    padding: var(--sems-space-6);
  }
}

.auth-card {
  width: 100%;
  max-width: 400px;
  padding: var(--sems-space-5);
  background-color: var(--sems-bg-card);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-card);
  box-shadow: var(--sems-shadow-card);
}

.auth-card__title {
  margin: 0;
  font-size: var(--sems-font-size-xl);
  font-weight: var(--sems-font-weight-bold);
  color: var(--sems-text-primary);
}

.auth-card__subtitle {
  margin: var(--sems-space-1) 0 var(--sems-space-5);
}

.auth-card__alert {
  margin-bottom: var(--sems-space-4);
}

.auth-card__helper {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  margin-bottom: var(--sems-space-2);
}

.auth-card__submit {
  width: 100%;
}

.auth-captcha {
  display: flex;
  align-items: stretch;
  gap: var(--sems-space-2);
  width: 100%;
}

.auth-captcha__input {
  flex: 1;
  min-width: 0;
}

.auth-captcha__image {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 120px;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-base);
  background-color: var(--sems-bg-page);
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-xs);
  cursor: pointer;
}

.auth-captcha__image:hover {
  border-color: var(--sems-color-primary);
}

.auth-captcha__image img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.auth-captcha__hint {
  padding: 0 var(--sems-space-1);
}

.auth-demo {
  margin-top: var(--sems-space-5);
  padding: var(--sems-space-3);
  border: 1px dashed var(--sems-border);
  border-radius: var(--sems-radius-base);
  background-color: var(--sems-bg-page);
  font-size: var(--sems-font-size-sm);
}

.auth-demo__title {
  margin: 0 0 var(--sems-space-1);
  font-size: var(--sems-font-size-xs);
  color: var(--sems-text-secondary);
}

.auth-demo__line {
  margin: 0;
  color: var(--sems-text-regular);
  font-weight: var(--sems-font-weight-medium);
}

.auth-demo__note {
  margin: var(--sems-space-1) 0 var(--sems-space-2);
}

.auth-card__footer {
  margin: var(--sems-space-4) 0 0;
  text-align: center;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
}
</style>
