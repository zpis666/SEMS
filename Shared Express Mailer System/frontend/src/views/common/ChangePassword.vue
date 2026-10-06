<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { passwordRules, passwordStrength } from '@/utils/validators'

/**
 * 修改密码页（契约 4.1）。
 *
 * 首次登录（mustChangePassword）或被管理员重置密码的账号，未改密前会被路由守卫强制拉到本页。
 * 改密成功触发 token_version 自增，本人全部登录凭证立即失效——
 * 因此提示「密码已修改，请重新登录」后必须回登录页。
 */

const router = useRouter()
const userStore = useUserStore()

const formRef = ref(null)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const submitting = ref(false)

/** 新密码强度档位：passwordStrength 返回 0–3；0 与 1 都按「弱」提示 */
const STRENGTH_LEVELS = [
  { label: '弱', token: '--sems-color-danger' },
  { label: '弱', token: '--sems-color-danger' },
  { label: '中', token: '--sems-color-warning' },
  { label: '强', token: '--sems-color-success' },
]
const strength = computed(() => passwordStrength(form.newPassword))
const strengthMeta = computed(() => STRENGTH_LEVELS[strength.value])

const rules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: passwordRules,
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (!value || value === form.newPassword) return callback()
        return callback(new Error('两次输入的新密码不一致'))
      },
      trigger: 'blur',
    },
  ],
}

async function handleSubmit() {
  if (submitting.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    // 字段名与后端改密入参对齐：oldPassword / newPassword
    await userStore.changePassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword,
    })
    await ElMessageBox.alert('密码已修改，请重新登录', '修改成功', {
      confirmButtonText: '去登录',
      type: 'success',
      showClose: false,
    }).catch(() => {})
    router.push('/login')
  } catch (error) {
    // 3001（旧密码错误、新密码不合规等）由 request.js 留给表单侧处理，这里补轻提示
    if (error?.code === 3001 && error.message) ElMessage.error(error.message)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <main class="auth-page__panel">
      <section class="auth-card">
        <h2 class="auth-card__title">修改密码</h2>
        <p class="auth-card__subtitle sems-text-muted">为保障账号安全，请设置一个新密码</p>

        <el-alert
          class="auth-card__alert"
          type="warning"
          title="首次登录或被管理员重置密码后必须修改密码"
          description="修改成功后当前登录凭证会立即失效，需要用新密码重新登录。"
          :closable="false"
          show-icon
        />

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="handleSubmit"
        >
          <el-form-item label="旧密码" prop="oldPassword">
            <el-input
              v-model="form.oldPassword"
              type="password"
              placeholder="请输入当前密码"
              show-password
              autocomplete="current-password"
            >
              <template v-slot:prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item label="新密码" prop="newPassword">
            <div class="pwd-field">
              <el-input
                v-model="form.newPassword"
                type="password"
                placeholder="设置 8 位以上、含两类字符的新密码"
                show-password
                autocomplete="new-password"
              >
                <template v-slot:prefix>
                  <el-icon><Key /></el-icon>
                </template>
              </el-input>
              <div v-if="form.newPassword" class="pwd-strength">
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

          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              placeholder="请再次输入新密码"
              show-password
              autocomplete="new-password"
            />
          </el-form-item>

          <el-button class="auth-card__submit" type="primary" size="large" native-type="submit" :loading="submitting">
            确认修改
          </el-button>
        </el-form>
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
  margin: var(--sems-space-1) 0 var(--sems-space-4);
}

.auth-card__alert {
  margin-bottom: var(--sems-space-4);
}

.auth-card__submit {
  width: 100%;
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
</style>
