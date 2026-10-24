<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { getCaptcha } from '@/api/auth'
import { phoneRules } from '@/utils/validators'
import AppBrand from '@/components/AppBrand.vue'
import AuthCycleVisual from '@/components/AuthCycleVisual.vue'

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

/** 各角色所属的路由分区（与 router/index.js 的三块布局一一对应） */
const SECTION_BY_ROLE = {
  PLATFORM_ADMIN: '/platform',
  STATION_ADMIN: '/station',
  USER: '/m',
}

/**
 * 回跳地址是否落在当前角色自己的分区内。
 *
 * 历史遗留的 `#/login?redirect=/m/home` 会把平台/站点管理员在登录后送进用户端 H5
 * （页面表现为手机竖版：底部 Tab + 480px 窄栏）。跨区回跳一律作废，改走角色首页。
 */
function redirectMatchesRole(target) {
  if (!target) return false
  const section = SECTION_BY_ROLE[userStore.role] || '/m'
  return target === section || target.startsWith(`${section}/`)
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
    // 只用与角色分区一致的回跳目标；跨区（如遗留的 redirect=/m/home）改走角色首页
    const target = readRedirect()
    router.replace(redirectMatchesRole(target) ? target : userStore.homePath)
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
    <aside class="auth-hero sems-desktop-only">
      <div class="auth-hero__inner">
        <AppBrand inverse />
        <div class="auth-hero__story">
          <p class="auth-hero__eyebrow">一只袋子 · 不止一段旅程</p>
          <h1>让包装循环，<br>让美好发生。</h1>
          <p class="auth-hero__desc">连接每一次取用与归还，让快递包装从一次性消耗，成为可持续的共享资源。</p>
        </div>
        <AuthCycleVisual />
        <div class="auth-hero__trust">
          <span>全程留痕</span><i /><span>循环复用</span><i /><span>绿色寄递</span>
        </div>
      </div>
    </aside>

    <main class="auth-main">
      <div class="auth-mobile-brand"><AppBrand small /></div>
      <section class="auth-card" aria-labelledby="login-title">
        <div class="auth-card__heading">
          <p class="auth-card__eyebrow">欢迎回来</p>
          <h2 id="login-title">登录共享快递袋</h2>
          <p>使用手机号登录，继续你的绿色寄递旅程</p>
        </div>
        <el-alert v-if="formError" class="auth-card__alert" type="error" :title="formError" :closable="false" show-icon />
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large" @submit.prevent="handleLogin">
          <el-form-item label="手机号" prop="phone">
            <el-input v-model="form.phone" placeholder="请输入 11 位手机号" maxlength="11" autocomplete="username">
              <template #prefix><el-icon><Iphone /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password autocomplete="current-password">
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item v-if="captchaVisible" label="图形验证码" prop="captchaCode">
            <div class="auth-captcha">
              <el-input v-model="form.captchaCode" class="auth-captcha__input" placeholder="请输入图中字符" maxlength="8" />
              <button class="auth-captcha__image" type="button" title="点击刷新" aria-label="刷新图形验证码" @click="refreshCaptcha">
                <img v-if="captchaImage" :src="captchaImage" alt="图形验证码" @error="onCaptchaImgError">
                <span v-else-if="captchaLoading" class="auth-captcha__hint">加载中…</span>
                <span v-else class="auth-captcha__hint">点击重试</span>
              </button>
            </div>
          </el-form-item>
          <div class="auth-card__helper">
            <el-button v-if="!captchaVisible" link type="primary" @click="openCaptcha">需要验证码？</el-button>
            <span v-else>看不清？点击图片可刷新</span>
          </div>
          <el-button class="auth-card__submit" type="primary" size="large" native-type="submit" :loading="submitting">登录</el-button>
        </el-form>
        <div class="auth-demo">
          <div><strong>快速体验</strong><span>平台管理员演示账号</span></div>
          <el-button size="small" text bg @click="fillDemo">一键填入</el-button>
          <p>13800000000 · Sems@2026<span>首次登录须修改密码</span></p>
        </div>
        <p class="auth-card__footer">还没有账号？<el-button link type="primary" @click="router.push('/register')">免费注册</el-button></p>
      </section>
      <p class="auth-main__foot">共享快递袋系统 · 让包装，循环新生</p>
    </main>
  </div>
</template>

<style scoped>
.auth-page { min-height: 100dvh; background: var(--sems-bg-page); }
.auth-hero { position: relative; overflow: hidden; background: var(--sems-bg-hero); }
.auth-hero::before, .auth-hero::after { content: ''; position: absolute; border: 1px solid color-mix(in srgb, var(--sems-text-inverse) 9%, transparent); border-radius: 50%; }
.auth-hero::before { width: 460px; height: 460px; top: -250px; right: -150px; }
.auth-hero::after { width: 320px; height: 320px; bottom: -190px; left: -130px; }
.auth-hero__inner { position: relative; z-index: 1; display: flex; flex-direction: column; width: min(100%, 660px); min-height: 100dvh; margin: auto; padding: var(--sems-space-6) clamp(32px, 6vw, 80px); box-sizing: border-box; }
.auth-hero__story { margin-top: clamp(40px, 7vh, 80px); }
.auth-hero__eyebrow, .auth-card__eyebrow { margin: 0 0 var(--sems-space-3); color: var(--sems-color-accent-soft); font-size: var(--sems-font-size-xs); font-weight: var(--sems-font-weight-bold); letter-spacing: .18em; }
.auth-hero h1 { margin: 0; color: var(--sems-text-inverse); font-size: var(--sems-font-size-display); line-height: 1.35; letter-spacing: .04em; }
.auth-hero__desc { max-width: 430px; margin: var(--sems-space-4) 0 0; color: var(--sems-text-on-brand-soft); font-size: var(--sems-font-size-lg); line-height: 1.9; }
.auth-hero__trust { display: flex; align-items: center; gap: var(--sems-space-3); margin-top: auto; padding-top: var(--sems-space-5); color: var(--sems-text-on-brand-soft); font-size: var(--sems-font-size-xs); letter-spacing: .1em; }
.auth-hero__trust i { width: 3px; height: 3px; border-radius: 50%; background: currentColor; opacity: .55; }
.auth-main { display: flex; flex-direction: column; justify-content: center; align-items: center; min-width: 0; padding: var(--sems-space-6); box-sizing: border-box; background: var(--sems-bg-highlight), var(--sems-bg-page); }
.auth-card { width: min(100%, 420px); padding: var(--sems-space-6); box-sizing: border-box; border: 1px solid var(--sems-border-soft); border-radius: var(--sems-radius-card); background: var(--sems-bg-card); box-shadow: var(--sems-shadow-popup); }
.auth-card__heading { margin-bottom: var(--sems-space-5); }
.auth-card__eyebrow { margin-bottom: var(--sems-space-2); color: var(--sems-color-primary); }
.auth-card h2 { margin: 0; color: var(--sems-text-primary); font-size: var(--sems-font-size-kpi); line-height: 1.4; }
.auth-card__heading > p:last-child { margin: var(--sems-space-2) 0 0; color: var(--sems-text-secondary); line-height: 1.7; }
.auth-card__alert { margin-bottom: var(--sems-space-4); }
.auth-card :deep(.el-form-item__label) { color: var(--sems-text-regular); font-weight: var(--sems-font-weight-medium); }
.auth-card :deep(.el-input__wrapper) { min-height: var(--sems-touch-target); background: var(--sems-bg-page); box-shadow: 0 0 0 1px var(--sems-border) inset; }
.auth-card :deep(.el-input__wrapper.is-focus) { background: var(--sems-bg-card); box-shadow: 0 0 0 1px var(--sems-color-primary) inset; }
.auth-card :deep(.el-input__prefix) { color: var(--sems-text-secondary); }
.auth-card__helper { display: flex; justify-content: flex-end; min-height: 28px; margin-top: calc(var(--sems-space-2) * -1); color: var(--sems-text-secondary); font-size: var(--sems-font-size-xs); }
.auth-card__submit { width: 100%; min-height: var(--sems-btn-height-touch); margin-top: var(--sems-space-2); border: 0; background: var(--sems-bg-button); box-shadow: var(--sems-shadow-button); font-weight: var(--sems-font-weight-bold); letter-spacing: .16em; }
.auth-captcha { display: flex; gap: var(--sems-space-2); width: 100%; }
.auth-captcha__input { flex: 1; min-width: 0; }
.auth-captcha__image { flex: none; width: 120px; padding: 0; overflow: hidden; border: 1px solid var(--sems-border); border-radius: var(--sems-radius-base); background: var(--sems-bg-page); color: var(--sems-text-secondary); font-size: var(--sems-font-size-xs); cursor: pointer; }
.auth-captcha__image img { display: block; width: 100%; height: 100%; object-fit: cover; }
.auth-demo { display: grid; grid-template-columns: 1fr auto; gap: var(--sems-space-2); margin-top: var(--sems-space-5); padding: var(--sems-space-3); border-radius: var(--sems-radius-base); background: var(--sems-bg-soft); font-size: var(--sems-font-size-xs); }
.auth-demo > div { display: flex; flex-direction: column; gap: var(--sems-space-1); }
.auth-demo strong { color: var(--sems-text-regular); }
.auth-demo span, .auth-demo p span { color: var(--sems-text-secondary); }
.auth-demo p { grid-column: 1 / -1; margin: 0; color: var(--sems-text-regular); font-family: var(--sems-font-family); }
.auth-demo p span { margin-left: var(--sems-space-2); }
.auth-card__footer { margin: var(--sems-space-5) 0 0; color: var(--sems-text-secondary); font-size: var(--sems-font-size-sm); text-align: center; }
.auth-mobile-brand, .auth-main__foot { display: none; }
@media (min-width: 900px) { .auth-page { display: grid; grid-template-columns: minmax(480px, 1.15fr) minmax(440px, .85fr); } }
@media (max-width: 899px) { .auth-main { min-height: 100dvh; justify-content: flex-start; padding: var(--sems-space-5) var(--sems-h5-gutter); } .auth-mobile-brand { display: block; align-self: flex-start; margin-bottom: var(--sems-space-6); } .auth-card { padding: var(--sems-space-5); box-shadow: var(--sems-shadow-card); } .auth-main__foot { display: block; margin: var(--sems-space-6) 0 0; color: var(--sems-text-secondary); font-size: var(--sems-font-size-xs); } }
@media (max-width: 480px) { .auth-main { padding: var(--sems-space-4); } .auth-mobile-brand { margin-bottom: var(--sems-space-5); } .auth-card { padding: var(--sems-space-5) var(--sems-space-4); } .auth-card h2 { font-size: var(--sems-font-size-xl); } .auth-demo p span { display: block; margin: var(--sems-space-1) 0 0; } }
</style>