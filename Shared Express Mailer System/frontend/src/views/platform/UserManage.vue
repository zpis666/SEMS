<script setup>
/**
 * 用户管理（平台端）——见《系统设计说明书》4.4 / 5.1（FR-M1-12~FR-M1-17）。
 *
 * 功能：分页查询（手机号模糊 / 角色 / 状态筛选）、创建账号（多角色，站点管理员必须绑定站点）、
 *      调整角色、绑定站点、启用/禁用、重置密码。
 *
 * 关键口径（7.1）：改角色 / 换绑站点 / 改状态 / 重置密码都会自增该用户的 token_version，
 * 其已签发的全部 token 立即失效——每次操作成功后必须提示管理员，避免误以为「改完即生效且无需重登」。
 */
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DataPager from '@/components/DataPager.vue'
import StatusTag from '@/components/StatusTag.vue'
import { DEFAULT_PAGE_SIZE, ROLE_LABEL, USER_STATUS_META } from '@/constants'
import {
  createUser,
  listUsers,
  resetPassword,
  updateRoles,
  updateStation,
  updateStatus,
} from '@/api/user'
import { listStations } from '@/api/station'
import { nicknameRules, passwordRules, phoneRules } from '@/utils/validators'

/* ------------------------------ 列表 ------------------------------ */

const query = reactive({
  pageNum: 1,
  pageSize: DEFAULT_PAGE_SIZE,
  phone: '',
  role: '',
  status: '',
})
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')

/** <768px 表格转卡片列表（10.5：禁止横向滚动） */
const isMobile = ref(false)
function syncLayout() {
  isMobile.value = window.innerWidth < 768
}

const hasFilter = computed(() => !!(query.phone || query.role || query.status))

function buildQuery() {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.phone) params.phone = query.phone.trim()
  if (query.role) params.role = query.role
  if (query.status) params.status = query.status
  return params
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await listUsers(buildQuery())
    rows.value = data?.records || []
    total.value = data?.total || 0
  } catch (error) {
    // 失败时 request.js 已统一提示；页面负责错误态与重试入口
    errorMessage.value = error?.message || '用户列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search() {
  query.pageNum = 1
  load()
}

function resetQuery() {
  query.phone = ''
  query.role = ''
  query.status = ''
  search()
}

/* --------------------------- 站点下拉选项 --------------------------- */

const stationOptions = ref([])
const stationLoading = ref(false)

async function loadStationOptions() {
  if (stationLoading.value) return
  stationLoading.value = true
  try {
    const data = await listStations({ pageNum: 1, pageSize: 100 })
    stationOptions.value = data?.records || []
  } catch {
    // 站点选项加载失败不阻断用户管理主流程；下次打开对话框会重试
    stationOptions.value = []
  } finally {
    stationLoading.value = false
  }
}

function ensureStationOptions() {
  if (!stationOptions.value.length) loadStationOptions()
}

const stationNameMap = computed(() => {
  const map = new Map()
  stationOptions.value.forEach((item) => map.set(item.id, item.name))
  return map
})

/* ------------------------------ 行数据展示 ------------------------------ */

/** 角色兼容数组与单值两种出参形态（多对多授权，主要形态是数组） */
function roleCodesOf(row) {
  if (!row) return []
  const raw = Array.isArray(row.roles) ? row.roles : row.role ? [row.role] : []
  return raw
    .map((item) => (typeof item === 'string' ? item : item?.roleCode || item?.code || ''))
    .filter(Boolean)
}

function roleTextOf(row) {
  const codes = roleCodesOf(row)
  if (!codes.length) return '—'
  return codes.map((code) => ROLE_LABEL[code] || code).join('、')
}

function isStationAdminRow(row) {
  return roleCodesOf(row).includes('STATION_ADMIN')
}

function stationTextOf(row) {
  if (!row?.stationId) return '—'
  return row.stationName || stationNameMap.value.get(row.stationId) || `#${row.stationId}`
}

function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = (num) => String(num).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`
}

/* ------------------------------ 结果提示 ------------------------------ */

/** 凭证失效提示：改角色 / 换站点 / 改状态 / 重置密码后统一调用 */
function notifyTokenInvalidated() {
  ElMessage.warning('该用户的旧登录凭证已立即失效（token_version 自增）')
}

/** 3001 的字段级明细由表单侧展示（request.js 对 3001 不弹提示，见 6.6）；其余错误码已由 request.js 提示 */
function reportSubmitError(error) {
  if (error?.code !== 3001) return
  // 后端 3001 的 data 直接就是「字段 → 消息」映射（GlobalExceptionHandler 把 fieldErrors 作为 data 返回），
  // 兼容带 fieldErrors 包装的形态，否则明细永远取空、只剩通用文案「参数校验失败」（6.6 要求字段级提示）
  const raw = error.data?.fieldErrors || error.data
  const fieldErrors = raw && typeof raw === 'object' ? raw : {}
  const first = Object.values(fieldErrors)[0]
  const tip = Array.isArray(first) ? first[0] : first
  ElMessage.warning(tip || error.message || '提交内容有误，请检查后重试')
}

/* --------------------------- 新建账号对话框 --------------------------- */

const createVisible = ref(false)
const createSubmitting = ref(false)
const createFormRef = ref(null)
const createForm = reactive({
  phone: '',
  nickname: '',
  password: '',
  roles: ['USER'],
  stationId: null,
})

const createRules = {
  phone: phoneRules,
  nickname: nicknameRules,
  password: passwordRules,
  roles: [{ required: true, type: 'array', min: 1, message: '请至少选择一个角色', trigger: 'change' }],
  stationId: [
    {
      validator: (_rule, value, callback) => {
        if (createForm.roles.includes('STATION_ADMIN') && !value) {
          return callback(new Error('站点管理员必须绑定站点'))
        }
        return callback()
      },
      trigger: 'change',
    },
  ],
}

function openCreate() {
  createForm.phone = ''
  createForm.nickname = ''
  createForm.password = ''
  createForm.roles = ['USER']
  createForm.stationId = null
  createVisible.value = true
  ensureStationOptions()
  nextTick(() => createFormRef.value?.clearValidate())
}

/** 角色变化时清理无意义的站点绑定，避免切走站点管理员后仍把 stationId 提交上去 */
function handleCreateRolesChange() {
  if (!createForm.roles.includes('STATION_ADMIN')) {
    createForm.stationId = null
  }
}

async function submitCreate() {
  if (!createFormRef.value) return
  try {
    await createFormRef.value.validate()
  } catch {
    return
  }
  createSubmitting.value = true
  try {
    const payload = {
      phone: createForm.phone.trim(),
      nickname: createForm.nickname.trim(),
      password: createForm.password,
      roles: [...createForm.roles],
    }
    if (createForm.roles.includes('STATION_ADMIN')) payload.stationId = createForm.stationId
    await createUser(payload)
    ElMessage.success('账号已创建，初始密码请线下交付本人')
    createVisible.value = false
    search()
  } catch (error) {
    reportSubmitError(error)
  } finally {
    createSubmitting.value = false
  }
}

/* ---------------------------- 调整角色对话框 ---------------------------- */

const rolesVisible = ref(false)
const rolesSubmitting = ref(false)
const rolesFormRef = ref(null)
const rolesTarget = ref(null)
const rolesForm = reactive({ roles: [] })
const rolesRules = {
  roles: [{ required: true, type: 'array', min: 1, message: '请至少选择一个角色', trigger: 'change' }],
}

function openRolesDialog(row) {
  rolesTarget.value = row
  rolesForm.roles = roleCodesOf(row)
  rolesVisible.value = true
  nextTick(() => rolesFormRef.value?.clearValidate())
}

async function submitRoles() {
  if (!rolesFormRef.value || !rolesTarget.value) return
  try {
    await rolesFormRef.value.validate()
  } catch {
    return
  }
  rolesSubmitting.value = true
  try {
    await updateRoles(rolesTarget.value.id, { roles: [...rolesForm.roles] })
    ElMessage.success('角色已调整')
    notifyTokenInvalidated()
    rolesVisible.value = false
    load()
  } catch (error) {
    reportSubmitError(error)
  } finally {
    rolesSubmitting.value = false
  }
}

/* ---------------------------- 绑定站点对话框 ---------------------------- */

const stationVisible = ref(false)
const stationSubmitting = ref(false)
const stationFormRef = ref(null)
const stationTarget = ref(null)
const stationForm = reactive({ stationId: null })
const stationRules = {
  stationId: [{ required: true, message: '请选择绑定站点', trigger: 'change' }],
}

function openStationDialog(row) {
  if (!isStationAdminRow(row)) {
    // 下拉项已置灰，这里是兜底防线
    ElMessage.info('仅站点管理员需要绑定站点')
    return
  }
  stationTarget.value = row
  stationForm.stationId = row.stationId || null
  stationVisible.value = true
  ensureStationOptions()
  nextTick(() => stationFormRef.value?.clearValidate())
}

async function submitStation() {
  if (!stationFormRef.value || !stationTarget.value) return
  try {
    await stationFormRef.value.validate()
  } catch {
    return
  }
  stationSubmitting.value = true
  try {
    await updateStation(stationTarget.value.id, { stationId: stationForm.stationId })
    ElMessage.success('站点绑定已更新')
    notifyTokenInvalidated()
    stationVisible.value = false
    load()
  } catch (error) {
    reportSubmitError(error)
  } finally {
    stationSubmitting.value = false
  }
}

/* ---------------------------- 启用 / 禁用 ---------------------------- */

async function toggleStatus(row) {
  const disabling = row.status === 'ENABLED'
  if (disabling) {
    try {
      await ElMessageBox.confirm(
        `禁用后「${row.nickname || row.phone}」将无法登录，其全部登录凭证立即失效。确认禁用？`,
        '禁用账号',
        { confirmButtonText: '禁用', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }
  try {
    await updateStatus(row.id, { status: disabling ? 'DISABLED' : 'ENABLED' })
    ElMessage.success(disabling ? '账号已禁用' : '账号已启用')
    notifyTokenInvalidated()
    load()
  } catch (error) {
    reportSubmitError(error)
  }
}

/* ----------------------------- 重置密码 ----------------------------- */

async function handleResetPassword(row) {
  try {
    await ElMessageBox.confirm(
      `确认重置「${row.nickname || row.phone}」的登录密码？重置后其旧凭证立即失效。`,
      '重置密码',
      { confirmButtonText: '重置', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await resetPassword(row.id)
  } catch (error) {
    reportSubmitError(error)
    return
  }
  notifyTokenInvalidated()
  try {
    await ElMessageBox.alert('已重置为初始密码，该用户下次登录必须改密', '重置成功', {
      confirmButtonText: '知道了',
      type: 'success',
    })
  } catch {
    // 通过右上角关闭 / Esc 关闭提示也视为已知悉，不影响后续列表刷新
  }
  load()
}

/* ------------------------------ 生命周期 ------------------------------ */

onMounted(() => {
  syncLayout()
  window.addEventListener('resize', syncLayout)
  load()
  loadStationOptions()
})

onUnmounted(() => {
  window.removeEventListener('resize', syncLayout)
})
</script>

<template>
  <div class="user-manage">
    <PageCard title="用户管理" subtitle="平台账号、角色授权与站点绑定维护">
      <template #extra>
        <el-button type="primary" @click="openCreate">新建账号</el-button>
      </template>

      <!-- 筛选：手机号（模糊）/ 角色 / 状态 -->
      <el-form class="user-manage__filter" :inline="true" @submit.prevent>
        <el-form-item label="手机号">
          <el-input
            v-model="query.phone"
            class="user-manage__filter-input"
            placeholder="手机号模糊查询"
            clearable
            maxlength="11"
            @keyup.enter="search"
            @clear="search"
          />
        </el-form-item>
        <el-form-item label="角色">
          <el-select
            v-model="query.role"
            class="user-manage__filter-select"
            placeholder="全部角色"
            clearable
            @change="search"
          >
            <el-option v-for="(label, code) in ROLE_LABEL" :key="code" :label="label" :value="code" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="query.status"
            class="user-manage__filter-select"
            placeholder="全部状态"
            clearable
            @change="search"
          >
            <el-option
              v-for="(meta, code) in USER_STATUS_META"
              :key="code"
              :label="meta.label"
              :value="code"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 加载态：骨架屏（占位尺寸与真实表格一致） -->
      <PageSkeleton v-if="loading" type="table" :rows="6" />

      <!-- 错误态：给出原因与重试 -->
      <div v-else-if="errorMessage" class="user-manage__error">
        <el-icon class="user-manage__error-icon"><WarningFilled /></el-icon>
        <p class="user-manage__error-text">{{ errorMessage }}</p>
        <el-button type="primary" @click="load">重试</el-button>
      </div>

      <!-- 空态：一句话 + 主操作 -->
      <EmptyState
        v-else-if="!rows.length"
        :description="hasFilter ? '没有符合筛选条件的用户' : '暂时还没有用户账号'"
      >
        <el-button v-if="hasFilter" type="primary" @click="resetQuery">清空筛选条件</el-button>
        <el-button v-else type="primary" @click="openCreate">新建账号</el-button>
      </EmptyState>

      <template v-else>
        <!-- 移动端（<768px）：卡片列表，不出现横向滚动 -->
        <div v-if="isMobile" class="user-manage__cards">
          <article v-for="row in rows" :key="row.id" class="user-manage__card">
            <header class="user-manage__card-head">
              <span class="user-manage__card-title">{{ row.nickname || '—' }}</span>
              <StatusTag kind="user" :value="row.status" size="small" />
            </header>
            <dl class="user-manage__card-body">
              <div class="user-manage__card-row">
                <dt>手机号</dt>
                <dd class="sems-num">{{ row.phone || '—' }}</dd>
              </div>
              <div class="user-manage__card-row">
                <dt>角色</dt>
                <dd>{{ roleTextOf(row) }}</dd>
              </div>
              <div class="user-manage__card-row">
                <dt>绑定站点</dt>
                <dd>{{ stationTextOf(row) }}</dd>
              </div>
              <div class="user-manage__card-row">
                <dt>最后登录</dt>
                <dd class="sems-num">{{ formatTime(row.lastLoginAt) }}</dd>
              </div>
            </dl>
            <footer class="user-manage__card-actions">
              <el-button link type="primary" @click="openRolesDialog(row)">调整角色</el-button>
              <el-button link type="primary" @click="toggleStatus(row)">
                {{ row.status === 'ENABLED' ? '禁用' : '启用' }}
              </el-button>
              <el-dropdown trigger="click">
                <el-button link type="primary">
                  更多
                  <el-icon class="user-manage__more-icon"><ArrowDown /></el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-tooltip
                      :disabled="isStationAdminRow(row)"
                      content="仅站点管理员需要绑定站点"
                      placement="top"
                    >
                      <el-dropdown-item
                        :disabled="!isStationAdminRow(row)"
                        @click="openStationDialog(row)"
                      >
                        绑定站点
                      </el-dropdown-item>
                    </el-tooltip>
                    <el-dropdown-item @click="handleResetPassword(row)">重置密码</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </footer>
          </article>
        </div>

        <!-- 桌面端：表格（行高由全局样式统一 48px）；行内 2 个主操作，其余收进「更多」 -->
        <el-table v-else :data="rows" row-key="id" class="user-manage__table">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="phone" label="手机号" min-width="130">
            <template #default="{ row }">
              <span class="sems-num">{{ row.phone || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="nickname" label="昵称" min-width="110" show-overflow-tooltip />
          <el-table-column label="角色" min-width="160">
            <template #default="{ row }">{{ roleTextOf(row) }}</template>
          </el-table-column>
          <el-table-column label="绑定站点" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ stationTextOf(row) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <StatusTag kind="user" :value="row.status" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="最后登录时间" min-width="150">
            <template #default="{ row }">
              <span class="sems-num">{{ formatTime(row.lastLoginAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="216">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openRolesDialog(row)">
                调整角色
              </el-button>
              <el-button link type="primary" size="small" @click="toggleStatus(row)">
                {{ row.status === 'ENABLED' ? '禁用' : '启用' }}
              </el-button>
              <el-dropdown trigger="click">
                <el-button link type="primary" size="small">
                  更多
                  <el-icon class="user-manage__more-icon"><ArrowDown /></el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-tooltip
                      :disabled="isStationAdminRow(row)"
                      content="仅站点管理员需要绑定站点"
                      placement="left"
                    >
                      <el-dropdown-item
                        :disabled="!isStationAdminRow(row)"
                        @click="openStationDialog(row)"
                      >
                        绑定站点
                      </el-dropdown-item>
                    </el-tooltip>
                    <el-dropdown-item @click="handleResetPassword(row)">重置密码</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
        </el-table>

        <DataPager
          v-if="total > 0"
          v-model:page="query.pageNum"
          v-model:size="query.pageSize"
          :total="total"
          :disabled="loading"
          @change="load"
        />
      </template>
    </PageCard>

    <!-- 新建账号：站点管理员必须绑定站点（FR-M1-12） -->
    <el-dialog v-model="createVisible" title="新建账号" width="520px" :close-on-click-modal="false">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="96px">
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="createForm.phone" maxlength="11" placeholder="11 位手机号，作为登录账号" />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="createForm.nickname" maxlength="16" placeholder="2–16 个字符" />
        </el-form-item>
        <el-form-item label="初始密码" prop="password">
          <el-input
            v-model="createForm.password"
            type="password"
            show-password
            placeholder="不少于 8 位，含字母与数字/符号两类字符"
          />
        </el-form-item>
        <el-form-item label="角色" prop="roles">
          <el-select
            v-model="createForm.roles"
            multiple
            class="user-manage__dialog-select"
            placeholder="可多选"
            @change="handleCreateRolesChange"
          >
            <el-option
              v-for="(label, code) in ROLE_LABEL"
              :key="code"
              :label="label"
              :value="code"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="createForm.roles.includes('STATION_ADMIN')" label="绑定站点" prop="stationId">
          <el-select
            v-model="createForm.stationId"
            filterable
            class="user-manage__dialog-select"
            placeholder="站点管理员必须绑定站点（一岗一站）"
            :loading="stationLoading"
          >
            <el-option
              v-for="station in stationOptions"
              :key="station.id"
              :label="station.name"
              :value="station.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 调整角色：保存即 token_version 自增，旧凭证全部失效 -->
    <el-dialog v-model="rolesVisible" title="调整角色" width="480px" :close-on-click-modal="false">
      <p class="user-manage__dialog-desc">
        目标账号：{{ rolesTarget?.nickname || '—' }}（{{ rolesTarget?.phone || '—' }}）
      </p>
      <el-form ref="rolesFormRef" :model="rolesForm" :rules="rolesRules" label-width="72px">
        <el-form-item label="角色" prop="roles">
          <el-select v-model="rolesForm.roles" multiple class="user-manage__dialog-select">
            <el-option
              v-for="(label, code) in ROLE_LABEL"
              :key="code"
              :label="label"
              :value="code"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="保存后该用户的旧登录凭证将立即失效，需重新登录后按新角色生效"
      />
      <template #footer>
        <el-button @click="rolesVisible = false">取消</el-button>
        <el-button type="primary" :loading="rolesSubmitting" @click="submitRoles">保存</el-button>
      </template>
    </el-dialog>

    <!-- 绑定站点：仅站点管理员有意义，换绑同样使旧凭证失效（FR-M1-13） -->
    <el-dialog v-model="stationVisible" title="绑定站点" width="480px" :close-on-click-modal="false">
      <p class="user-manage__dialog-desc">
        目标账号：{{ stationTarget?.nickname || '—' }}（{{ stationTarget?.phone || '—' }}）
      </p>
      <el-form ref="stationFormRef" :model="stationForm" :rules="stationRules" label-width="88px">
        <el-form-item label="绑定站点" prop="stationId">
          <el-select
            v-model="stationForm.stationId"
            filterable
            class="user-manage__dialog-select"
            placeholder="请选择本站点管理员负责的站点"
            :loading="stationLoading"
          >
            <el-option
              v-for="station in stationOptions"
              :key="station.id"
              :label="station.name"
              :value="station.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="换绑后该用户的旧登录凭证将立即失效，新凭证将携带新的站点身份"
      />
      <template #footer>
        <el-button @click="stationVisible = false">取消</el-button>
        <el-button type="primary" :loading="stationSubmitting" @click="submitStation">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.user-manage__filter {
  margin-bottom: var(--sems-space-2);
}
.user-manage__filter-input {
  width: 172px;
}
.user-manage__filter-select {
  width: 140px;
}
.user-manage__dialog-select {
  width: 100%;
}
.user-manage__dialog-desc {
  margin: 0 0 var(--sems-space-4);
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-base);
}

/* 错误态 */
.user-manage__error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-3);
  padding: var(--sems-space-6) 0;
}
.user-manage__error-icon {
  font-size: var(--sems-font-size-kpi);
  color: var(--sems-color-warning);
}
.user-manage__error-text {
  margin: 0;
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-base);
}

.user-manage__more-icon {
  margin-left: var(--sems-space-1);
}

/* 移动端卡片列表 */
.user-manage__cards {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
}
.user-manage__card {
  padding: var(--sems-space-4);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-card);
  background-color: var(--sems-bg-card);
}
.user-manage__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-3);
}
.user-manage__card-title {
  font-size: var(--sems-font-size-lg);
  font-weight: var(--sems-font-weight-bold);
  color: var(--sems-text-primary);
}
.user-manage__card-body {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
}
.user-manage__card-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sems-space-4);
}
.user-manage__card-row dt {
  flex: none;
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-sm);
}
.user-manage__card-row dd {
  margin: 0;
  text-align: right;
  color: var(--sems-text-regular);
  font-size: var(--sems-font-size-base);
  word-break: break-all;
}
.user-manage__card-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sems-space-2);
  margin-top: var(--sems-space-3);
  padding-top: var(--sems-space-3);
  border-top: 1px solid var(--sems-border);
}
</style>
