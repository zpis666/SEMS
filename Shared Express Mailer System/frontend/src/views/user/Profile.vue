<script setup>
/**
 * 用户端 · 个人中心（《系统设计说明书》4.2）。
 *
 * - 手机号后端出参已脱敏（138****1234），只读展示；昵称可编辑（PATCH /users/me 白名单字段）；
 * - 角色按 ROLE_LABEL 展示为只读标签；站点管理员额外展示绑定站点；
 * - 「修改密码」跳 /change-password（改密后旧凭证全部失效）；
 * - 「退出登录」二次确认后调 userStore.logout() 并回登录页；
 * - 展示 WebSocket 连接状态（wsStore.statusText），提供「重新连接」（wsStore.ensureConnected）。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import { listNearbyStations } from '@/api/station'
import { ROLE_LABEL } from '@/constants'
import { nicknameRules } from '@/utils/validators'
import { useUserStore } from '@/stores/user'
import { useWebSocketStore } from '@/stores/websocket'

const router = useRouter()
const userStore = useUserStore()
const wsStore = useWebSocketStore()

const user = computed(() => userStore.user)
const loading = ref(false)
const loadError = ref('')
const stationName = ref('')

const roleText = computed(() => ROLE_LABEL[userStore.role] || '—')
const avatarText = computed(() => String(user.value?.nickname || '同学').trim().slice(0, 1) || '同')

/** 站点管理员的绑定站点：优先展示站点名，取不到时回退站点编号 */
const stationText = computed(() => {
  if (!userStore.isStationAdmin) return '—'
  if (stationName.value) return stationName.value
  return userStore.stationId != null ? `站点编号 ${userStore.stationId}` : '未绑定站点'
})

/** 无数据时才显示骨架屏/错误态；已有 store 数据时静默刷新，避免页面闪烁 */
const showSkeleton = computed(() => !user.value && loading.value)
const showError = computed(() => !user.value && !loading.value && !!loadError.value)

/* ------------------------------ 数据加载 ------------------------------ */

async function loadProfile() {
  loading.value = true
  loadError.value = ''
  try {
    await userStore.fetchMe()
    form.nickname = userStore.user?.nickname || ''
  } catch (error) {
    loadError.value = error?.message || '个人信息加载失败，请重试'
  } finally {
    loading.value = false
  }
}

async function loadStationName() {
  if (!userStore.isStationAdmin || userStore.stationId == null) return
  try {
    const data = await listNearbyStations()
    const list = Array.isArray(data) ? data : data?.records || []
    const hit = list.find((item) => String(item.id) === String(userStore.stationId))
    stationName.value = hit?.name || hit?.stationName || ''
  } catch {
    // 站点名解析失败不阻断页面，回退展示站点编号
  }
}

/* ------------------------------ 修改昵称 ------------------------------ */

const nicknameFormRef = ref(null)
const form = reactive({ nickname: '' })
const rules = { nickname: nicknameRules }
const savingNickname = ref(false)
const nicknameError = ref('')

function pickNicknameError(data) {
  const map = data?.fieldErrors || data
  const value = map?.nickname
  if (!value) return ''
  return Array.isArray(value) ? value.join('；') : String(value)
}

async function saveNickname() {
  if (!nicknameFormRef.value || savingNickname.value) return
  try {
    await nicknameFormRef.value.validate()
  } catch {
    return // 本地校验未通过，提示由表单自身展示
  }
  savingNickname.value = true
  nicknameError.value = ''
  try {
    await userStore.updateProfile({ nickname: form.nickname.trim() })
    ElMessage.success('昵称已更新')
  } catch (error) {
    if (error?.code === 3001) {
      nicknameError.value = pickNicknameError(error.data)
      if (!nicknameError.value) ElMessage.error('昵称未通过校验，请检查后重试')
    }
    // 其余错误码的提示已由 request.js 统一弹出
  } finally {
    savingNickname.value = false
  }
}

// 用户改动输入即清掉上一次的字段级服务端错误
watch(form, () => {
  if (nicknameError.value) nicknameError.value = ''
})

/* ------------------------------ 退出登录 ------------------------------ */

async function confirmLogout() {
  try {
    await ElMessageBox.confirm('退出登录后需要重新验证身份才能继续使用，确认退出吗？', '退出登录', {
      type: 'warning',
      confirmButtonText: '退出登录',
      cancelButtonText: '暂不退出',
    })
  } catch {
    return // 用户取消
  }
  // logout 内部保证无论如何都会清本地会话
  await userStore.logout()
  ElMessage.success('已退出登录')
  router.replace('/login')
}

/* ------------------------------ 实时推送 ------------------------------ */

const reconnecting = ref(false)

async function reconnect() {
  reconnecting.value = true
  try {
    await wsStore.ensureConnected()
    ElMessage.info('已发起重新连接，连接状态将自动刷新')
  } finally {
    reconnecting.value = false
  }
}

onMounted(() => {
  // 先用 store 中已有数据渲染，再拉一次最新信息
  form.nickname = userStore.user?.nickname || ''
  loadProfile()
  loadStationName()
})
</script>

<template>
  <div class="profile">
    <PageSkeleton v-if="showSkeleton" :rows="4" type="card" />

    <EmptyState v-else-if="showError" icon="Warning" :description="loadError">
      <el-button type="primary" @click="loadProfile">重试</el-button>
    </EmptyState>

    <template v-else>
      <section class="profile-hero">
        <div class="profile-hero__top">
          <span class="profile-hero__avatar" aria-hidden="true">{{ avatarText }}</span>
          <div class="profile-hero__identity">
            <p class="profile-hero__eyebrow">绿色生活，与你同行</p>
            <h2 class="profile-hero__name">{{ user?.nickname || '同学' }}</h2>
            <span class="profile-hero__role">
              <el-icon><UserFilled /></el-icon>
              {{ roleText }}
            </span>
          </div>
        </div>
        <div class="profile-hero__meta">
          <div>
            <span class="profile-hero__label">登录手机号</span>
            <strong class="sems-mono">{{ user?.phone || '—' }}</strong>
          </div>
          <el-icon class="profile-hero__leaf" :size="30"><Promotion /></el-icon>
        </div>
      </section>

      <PageCard title="账号信息" subtitle="管理你的昵称与账号资料">
        <el-form
          ref="nicknameFormRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent
        >
          <el-form-item label="昵称" prop="nickname" :error="nicknameError">
            <el-input v-model="form.nickname" maxlength="16" placeholder="请输入昵称" clearable />
          </el-form-item>
          <el-button class="profile-save" type="primary" :loading="savingNickname" @click="saveNickname">
            <el-icon><Check /></el-icon>
            <span>保存昵称</span>
          </el-button>
        </el-form>

        <div class="info-row">
          <span class="info-label">手机号</span>
          <span class="info-value sems-mono">{{ user?.phone || '—' }}</span>
        </div>
        <p class="field-hint sems-text-muted">手机号为账号唯一标识，不支持自助修改。</p>
        <div class="info-row">
          <span class="info-label">角色</span>
          <span class="info-value">{{ roleText }}</span>
        </div>
        <div v-if="userStore.isStationAdmin" class="info-row">
          <span class="info-label">绑定站点</span>
          <span class="info-value">{{ stationText }}</span>
        </div>
      </PageCard>

      <PageCard title="安全设置">
        <div class="security-actions">
          <button class="security-action" type="button" @click="router.push('/change-password')">
            <span class="security-action__icon"><el-icon><Lock /></el-icon></span>
            <span class="security-action__copy">
              <strong>修改密码</strong>
              <small>定期更新密码，保护账号安全</small>
            </span>
            <el-icon><ArrowRight /></el-icon>
          </button>
          <button class="security-action is-danger" type="button" @click="confirmLogout">
            <span class="security-action__icon"><el-icon><SwitchButton /></el-icon></span>
            <span class="security-action__copy">
              <strong>退出登录</strong>
              <small>退出后需重新验证身份</small>
            </span>
            <el-icon><ArrowRight /></el-icon>
          </button>
        </div>
        <p class="field-hint sems-text-muted">修改密码后所有登录凭证立即失效，需要重新登录。</p>
      </PageCard>

      <PageCard title="实时推送">
        <div class="action-row">
          <span class="ws-status">
            <el-icon :class="{ 'is-on': wsStore.connected && wsStore.authed }">
              <CircleCheckFilled v-if="wsStore.connected && wsStore.authed" />
              <Connection v-else />
            </el-icon>
            {{ wsStore.statusText }}
          </span>
          <el-button :loading="reconnecting" @click="reconnect">
            <el-icon><RefreshRight /></el-icon>
            <span>重新连接</span>
          </el-button>
        </div>
        <p class="field-hint sems-text-muted">
          实时推送用于库存与订单状态刷新，断开不影响页面手动刷新数据。
        </p>
      </PageCard>
    </template>
  </div>
</template>

<style scoped>
.profile {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-4);
  padding-top: var(--sems-space-1);
}

.profile :deep(.page-card) {
  border: 1px solid var(--sems-border-soft);
}

.profile-hero {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-5);
  padding: var(--sems-space-5);
  overflow: hidden;
  border-radius: var(--sems-radius-card);
  background: var(--sems-bg-hero);
  box-shadow: var(--sems-shadow-button);
  color: var(--sems-text-inverse);
}

.profile-hero__top {
  display: flex;
  align-items: center;
  gap: var(--sems-space-4);
}

.profile-hero__avatar {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  border: 1px solid color-mix(in srgb, var(--sems-text-inverse) 24%, transparent);
  border-radius: 50%;
  background-color: color-mix(in srgb, var(--sems-text-inverse) 12%, transparent);
  color: var(--sems-text-inverse);
  font-size: var(--sems-font-size-kpi);
  font-weight: var(--sems-font-weight-bold);
}

.profile-hero__identity {
  min-width: 0;
}

.profile-hero__eyebrow {
  margin: 0 0 var(--sems-space-1);
  color: var(--sems-text-on-brand-soft);
  font-size: var(--sems-font-size-xs);
}

.profile-hero__name {
  margin: 0 0 var(--sems-space-2);
  overflow: hidden;
  color: var(--sems-text-inverse);
  font-size: var(--sems-font-size-xl);
  font-weight: var(--sems-font-weight-bold);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-hero__role {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-1);
  padding: var(--sems-space-1) var(--sems-space-2);
  border-radius: var(--sems-radius-tag);
  background-color: color-mix(in srgb, var(--sems-text-inverse) 10%, transparent);
  color: var(--sems-text-on-brand-soft);
  font-size: var(--sems-font-size-xs);
}

.profile-hero__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-3);
  padding-top: var(--sems-space-4);
  border-top: 1px solid color-mix(in srgb, var(--sems-text-inverse) 14%, transparent);
}

.profile-hero__meta > div {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-1);
}

.profile-hero__label {
  color: var(--sems-text-on-brand-soft);
  font-size: var(--sems-font-size-xs);
}

.profile-hero__leaf {
  color: var(--sems-text-on-brand-soft);
}

.info-row {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  padding: var(--sems-space-2) 0;
}

.info-row + .info-row {
  border-top: 1px solid var(--sems-border);
}

.info-label {
  flex: none;
  width: 84px;
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-sm);
}

.info-value {
  flex: 1;
  color: var(--sems-text-primary);
  word-break: break-all;
}

.field-hint {
  margin: 0 0 var(--sems-space-3);
  line-height: 1.5;
}

.profile-save {
  min-height: var(--sems-touch-target);
  margin-bottom: var(--sems-space-2);
}

.security-actions {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-3);
}

.security-action {
  display: flex;
  align-items: center;
  gap: var(--sems-space-3);
  width: 100%;
  min-height: 64px;
  padding: var(--sems-space-3);
  border: 1px solid var(--sems-border-soft);
  border-radius: var(--sems-radius-base);
  background-color: var(--sems-bg-soft);
  color: var(--sems-text-primary);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--sems-duration-fast), background-color var(--sems-duration-fast);
}

.security-action:active {
  border-color: var(--sems-border-strong);
  background-color: var(--sems-bg-hover);
}

.security-action > .el-icon {
  color: var(--sems-color-primary);
}

.security-action__icon {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: var(--sems-touch-target);
  height: var(--sems-touch-target);
  border-radius: var(--sems-radius-base);
  background-color: var(--sems-color-primary-bg);
  color: var(--sems-color-primary);
}

.security-action__copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: var(--sems-space-1);
  min-width: 0;
}

.security-action__copy strong {
  font-size: var(--sems-font-size-base);
  font-weight: var(--sems-font-weight-medium);
}

.security-action__copy small {
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-xs);
}

.security-action.is-danger .security-action__icon,
.security-action.is-danger > .el-icon {
  background-color: var(--sems-color-danger-bg);
  color: var(--sems-color-danger);
}

.security-action.is-danger > .el-icon {
  background-color: transparent;
}

.action-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-3);
  margin-bottom: var(--sems-space-3);
}

.ws-status {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-2);
  color: var(--sems-text-regular);
  font-size: var(--sems-font-size-base);
}

.ws-status .el-icon {
  color: var(--sems-color-info);
  font-size: var(--sems-font-size-lg);
}

.ws-status .el-icon.is-on {
  color: var(--sems-color-success);
}
</style>
