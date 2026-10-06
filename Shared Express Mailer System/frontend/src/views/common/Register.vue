<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { getCaptcha } from '@/api/auth'
import { nicknameRules, passwordRules, passwordStrength, phoneRules } from '@/utils/validators'

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
    <!-- 桌面端品牌区：<768px 由全局 .sems-desktop-only 隐藏，移动端只看表单 -->
    <aside class="auth-page__brand sems-desktop-only">
      <div class="auth-brand">
        <span class="auth-brand__badge">
          <el-icon class="auth-brand__badge-icon"><Van /></el-icon>
        </span>
        <p class="auth-brand__en">SEMS</p>
        <h1 class="auth-brand__title">共享快递袋系统</h1>
        <p class="auth-brand__desc">注册后即可扫码取袋、线上寄件，并把袋子归还到任意共享站点。</p>
        <ul class="auth-brand__points">
          <li>
            <el-icon class="auth-brand__point-icon"><CircleCheckFilled /></el-icon>
            手机号注册，扫码即可取袋
          </li>
          <li>
            <el-icon class="auth-brand__point-icon"><CircleCheckFilled /></el-icon>
            寄件与归还进度随时可查
          </li>
          <li>
            <el-icon class="auth-brand__point-icon"><CircleCheckFilled /></el-icon>
            站点库存、消毒记录线上留痕
          </li>
        </ul>
      </div>
    </aside>

    <main class="auth-page__panel">
      <section class="auth-card">
        <h2 class="auth-card__title">注册</h2>
        <p class="auth-card__subtitle sems-text-muted">仅需一个手机号，即可加入共享快递袋循环</p>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="handleRegister"
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

          <el-form-item label="图形验证码" prop="captchaCode">
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
            <span class="sems-text-muted">看不清？点击图片可刷新</span>
          </div>

          <el-form-item label="密码" prop="password">
            <div class="pwd-field">
              <el-input
                v-model="form.password"
                type="password"
                placeholder="设置 8 位以上、含两类字符的密码"
                show-password
                autocomplete="new-password"
              >
                <template v-slot:prefix>
                  <el-icon><Lock /></el-icon>
                </template>
              </el-input>
              <div v-if="form.password" class="pwd-strength">
                <span class="pwd-strength__bars">
                  <i
                    v-for="n in 3"
                    :key="n"
                    class="pwd-strength__bar"
                    :style="{
                      backgroundColor: n <= strength ? `var(${strengthMeta.token})` : 'var(--sems-border)',
                    }"
                  />
                </span>
                <span class="pwd-strength__label" :style="{ color: `var(${strengthMeta.token})` }">
                  {{ strengthMeta.label }}
                </span>
              </div>
            </div>
          </el-form-item>

          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              placeholder="请再次输入密码"
              show-password
              autocomplete="new-password"
            />
          </el-form-item>

          <el-form-item label="昵称" prop="nickname">
            <el-input v-model="form.nickname" placeholder="2–16 个字符，将展示在个人中心" maxlength="16">
              <template v-slot:prefix>
                <el-icon><User /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-button class="auth-card__submit" type="primary" size="large" native-type="submit" :loading="submitting">
            注 册
          </el-button>
        </el-form>

        <p class="auth-card__footer">
          已有账号？
          <el-button link type="primary" @click="router.push('/login')">去登录</el-button>
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

.auth-card__helper {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  margin-top: calc(var(--sems-space-2) * -1);
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

.pwd-field {
  width: 100%;
}

.pwd-strength {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  width: 100%;
  margin-top: var(--sems-space-2);
}

.pwd-strength__bars {
  display: flex;
  flex: 1;
  gap: var(--sems-space-1);
}

.pwd-strength__bar {
  flex: 1;
  height: var(--sems-space-1);
  border-radius: var(--sems-radius-tag);
  background-color: var(--sems-border);
}

.pwd-strength__label {
  flex: none;
  font-size: var(--sems-font-size-xs);
}

.auth-card__footer {
  margin: var(--sems-space-4) 0 0;
  text-align: center;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
}
</style>
