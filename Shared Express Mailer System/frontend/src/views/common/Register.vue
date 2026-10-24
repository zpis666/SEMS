<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { getCaptcha } from '@/api/auth'
import { nicknameRules, passwordRules, passwordStrength, phoneRules } from '@/utils/validators'
import AppBrand from '@/components/AppBrand.vue'
import AuthCycleVisual from '@/components/AuthCycleVisual.vue'

/**
 * 注册页（契约 4.1）。
 *
 * 手机号 + 图形验证码 + 密码（强度条）+ 确认密码 + 昵称；
 * 提交调 stores/user 的 register()，成功后提示并回登录页。
 */

const router = useRouter()
const userStore = useUserStore()

const formRef = ref(null)
const form = reactive({
  phone: '',
  captchaCode: '',
  password: '',
  confirmPassword: '',
  nickname: '',
})

const submitting = ref(false)

/** 图形验证码：注册必须通过校验，进页即取一张 */
const captchaId = ref('')
const captchaImage = ref('')
const captchaLoading = ref(false)

/**
 * 密码强度档位：passwordStrength 返回 0–3。
 * 0 = 不足 8 位或未含两类字符，与 1 一并按「弱」提示；颜色只走令牌。
 */
const STRENGTH_LEVELS = [
  { label: '弱', token: '--sems-color-danger' },
  { label: '弱', token: '--sems-color-danger' },
  { label: '中', token: '--sems-color-warning' },
  { label: '强', token: '--sems-color-success' },
]
const strength = computed(() => passwordStrength(form.password))
const strengthMeta = computed(() => STRENGTH_LEVELS[strength.value])

const rules = {
  phone: phoneRules,
  captchaCode: [{ required: true, message: '请输入图形验证码', trigger: 'blur' }],
  password: passwordRules,
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (!value || value === form.password) return callback()
        return callback(new Error('两次输入的密码不一致'))
      },
      trigger: 'blur',
    },
  ],
  nickname: nicknameRules,
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
    // 取码失败：留「点击重试」入口（错误态），提交时后端仍会权威校验
    captchaId.value = ''
    captchaImage.value = ''
  } finally {
    captchaLoading.value = false
  }
}

function onCaptchaImgError() {
  captchaImage.value = ''
}

onMounted(refreshCaptcha)

async function handleRegister() {
  if (submitting.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await userStore.register({
      phone: form.phone.trim(),
      password: form.password,
      nickname: form.nickname.trim(),
      captchaId: captchaId.value,
      captchaCode: form.captchaCode.trim(),
    })
    ElMessage.success('注册成功，请使用新账号登录')
    router.push('/login')
  } catch (error) {
    // 验证码一次性：任何失败都换一张，避免拿已消费的验证码反复重试
    refreshCaptcha()
    // 3001（含验证码错误、手机号已注册等）不会由 request.js 弹提示，这里补一条
    if (error?.code === 3001 && error.message) {
      ElMessage.error(error.message)
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <aside class="auth-hero sems-desktop-only">
      <div class="auth-hero__inner">
        <AppBrand inverse />
        <div class="auth-hero__story">
          <p class="auth-hero__eyebrow">加入循环 · 从这一袋开始</p>
          <h1>少一点一次性，<br>多一份可持续。</h1>
          <p class="auth-hero__desc">注册一个账号，连接附近共享站点。每次扫码、寄件与归还，都在为绿色生活积累改变。</p>
        </div>
        <AuthCycleVisual />
        <div class="auth-hero__trust"><span>便捷取用</span><i /><span>随处可还</span><i /><span>全程可溯</span></div>
      </div>
    </aside>

    <main class="auth-main">
      <div class="auth-mobile-brand"><AppBrand small /></div>
      <section class="auth-card" aria-labelledby="register-title">
        <div class="auth-card__heading">
          <p class="auth-card__eyebrow">创建账号</p>
          <h2 id="register-title">开启循环之旅</h2>
          <p>一个手机号，即可使用共享快递袋服务</p>
        </div>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large" @submit.prevent="handleRegister">
          <el-form-item label="手机号" prop="phone">
            <el-input v-model="form.phone" placeholder="请输入 11 位手机号" maxlength="11" autocomplete="username">
              <template #prefix><el-icon><Iphone /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="图形验证码" prop="captchaCode">
            <div class="auth-captcha">
              <el-input v-model="form.captchaCode" class="auth-captcha__input" placeholder="请输入图中字符" maxlength="8" />
              <button class="auth-captcha__image" type="button" title="点击刷新" aria-label="刷新图形验证码" @click="refreshCaptcha">
                <img v-if="captchaImage" :src="captchaImage" alt="图形验证码" @error="onCaptchaImgError">
                <span v-else-if="captchaLoading" class="auth-captcha__hint">加载中…</span>
                <span v-else class="auth-captcha__hint">点击重试</span>
              </button>
            </div>
            <p class="auth-field-note">看不清？点击图片可刷新</p>
          </el-form-item>
          <el-form-item label="设置密码" prop="password">
            <div class="pwd-field">
              <el-input v-model="form.password" type="password" placeholder="8 位以上，包含两类字符" show-password autocomplete="new-password">
                <template #prefix><el-icon><Lock /></el-icon></template>
              </el-input>
              <div v-if="form.password" class="pwd-strength">
                <span class="pwd-strength__bars"><i v-for="n in 3" :key="n" class="pwd-strength__bar" :style="{ backgroundColor: n <= strength ? `var(${strengthMeta.token})` : 'var(--sems-border)' }" /></span>
                <span class="pwd-strength__label" :style="{ color: `var(${strengthMeta.token})` }">{{ strengthMeta.label }}</span>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input v-model="form.confirmPassword" type="password" placeholder="请再次输入密码" show-password autocomplete="new-password" />
          </el-form-item>
          <el-form-item label="你的昵称" prop="nickname">
            <el-input v-model="form.nickname" placeholder="2–16 个字符，将展示在个人中心" maxlength="16">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-button class="auth-card__submit" type="primary" size="large" native-type="submit" :loading="submitting">注册并加入循环</el-button>
        </el-form>
        <p class="auth-card__footer">已有账号？<el-button link type="primary" @click="router.push('/login')">返回登录</el-button></p>
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
.auth-hero__story { margin-top: clamp(32px, 5vh, 56px); }
.auth-hero__eyebrow, .auth-card__eyebrow { margin: 0 0 var(--sems-space-3); color: var(--sems-color-accent-soft); font-size: var(--sems-font-size-xs); font-weight: var(--sems-font-weight-bold); letter-spacing: .18em; }
.auth-hero h1 { margin: 0; color: var(--sems-text-inverse); font-size: var(--sems-font-size-display); line-height: 1.35; letter-spacing: .04em; }
.auth-hero__desc { max-width: 440px; margin: var(--sems-space-4) 0 0; color: var(--sems-text-on-brand-soft); font-size: var(--sems-font-size-lg); line-height: 1.9; }
.auth-hero__trust { display: flex; align-items: center; gap: var(--sems-space-3); margin-top: auto; padding-top: var(--sems-space-5); color: var(--sems-text-on-brand-soft); font-size: var(--sems-font-size-xs); letter-spacing: .1em; }
.auth-hero__trust i { width: 3px; height: 3px; border-radius: 50%; background: currentColor; opacity: .55; }
.auth-main { display: flex; flex-direction: column; justify-content: center; align-items: center; min-width: 0; padding: var(--sems-space-6); box-sizing: border-box; background: var(--sems-bg-highlight), var(--sems-bg-page); }
.auth-card { width: min(100%, 440px); margin: auto; padding: var(--sems-space-5) var(--sems-space-6); box-sizing: border-box; border: 1px solid var(--sems-border-soft); border-radius: var(--sems-radius-card); background: var(--sems-bg-card); box-shadow: var(--sems-shadow-popup); }
.auth-card__heading { margin-bottom: var(--sems-space-4); }
.auth-card__eyebrow { margin-bottom: var(--sems-space-2); color: var(--sems-color-primary); }
.auth-card h2 { margin: 0; color: var(--sems-text-primary); font-size: var(--sems-font-size-kpi); line-height: 1.4; }
.auth-card__heading > p:last-child { margin: var(--sems-space-2) 0 0; color: var(--sems-text-secondary); line-height: 1.7; }
.auth-card :deep(.el-form-item) { margin-bottom: var(--sems-space-4); }
.auth-card :deep(.el-form-item__label) { color: var(--sems-text-regular); font-weight: var(--sems-font-weight-medium); }
.auth-card :deep(.el-input__wrapper) { min-height: var(--sems-touch-target); background: var(--sems-bg-page); box-shadow: 0 0 0 1px var(--sems-border) inset; }
.auth-card :deep(.el-input__wrapper.is-focus) { background: var(--sems-bg-card); box-shadow: 0 0 0 1px var(--sems-color-primary) inset; }
.auth-card :deep(.el-input__prefix) { color: var(--sems-text-secondary); }
.auth-field-note { margin: var(--sems-space-1) 0 0; color: var(--sems-text-secondary); font-size: var(--sems-font-size-xs); line-height: 1.5; }
.auth-card__submit { width: 100%; min-height: var(--sems-btn-height-touch); margin-top: var(--sems-space-2); border: 0; background: var(--sems-bg-button); box-shadow: var(--sems-shadow-button); font-weight: var(--sems-font-weight-bold); }
.auth-captcha { display: flex; gap: var(--sems-space-2); width: 100%; }
.auth-captcha__input { flex: 1; min-width: 0; }
.auth-captcha__image { flex: none; width: 120px; padding: 0; overflow: hidden; border: 1px solid var(--sems-border); border-radius: var(--sems-radius-base); background: var(--sems-bg-page); color: var(--sems-text-secondary); font-size: var(--sems-font-size-xs); cursor: pointer; }
.auth-captcha__image img { display: block; width: 100%; height: 100%; object-fit: cover; }
.pwd-field { width: 100%; }
.pwd-strength { display: flex; align-items: center; gap: var(--sems-space-2); margin-top: var(--sems-space-2); }
.pwd-strength__bars { display: flex; flex: 1; gap: var(--sems-space-1); }
.pwd-strength__bar { flex: 1; height: var(--sems-space-1); border-radius: var(--sems-radius-tag); }
.pwd-strength__label { flex: none; font-size: var(--sems-font-size-xs); }
.auth-card__footer { margin: var(--sems-space-4) 0 0; color: var(--sems-text-secondary); font-size: var(--sems-font-size-sm); text-align: center; }
.auth-mobile-brand, .auth-main__foot { display: none; }
@media (min-width: 900px) { .auth-page { display: grid; grid-template-columns: minmax(480px, 1.15fr) minmax(440px, .85fr); } }
@media (min-width: 900px) and (max-height: 850px) { .auth-hero__story { margin-top: var(--sems-space-6); } .auth-hero :deep(.cycle-visual) { width: 390px; margin-top: 0; } }
@media (max-width: 899px) { .auth-main { min-height: 100dvh; justify-content: flex-start; padding: var(--sems-space-5) var(--sems-h5-gutter); } .auth-mobile-brand { display: block; align-self: flex-start; margin-bottom: var(--sems-space-5); } .auth-card { margin: 0; box-shadow: var(--sems-shadow-card); } .auth-main__foot { display: block; margin: var(--sems-space-6) 0 0; color: var(--sems-text-secondary); font-size: var(--sems-font-size-xs); } }
@media (max-width: 480px) { .auth-main { padding: var(--sems-space-4); } .auth-card { padding: var(--sems-space-5) var(--sems-space-4); } .auth-card h2 { font-size: var(--sems-font-size-xl); } }
</style>