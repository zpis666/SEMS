<script setup>
/**
 * 站点管理（平台端）——见《系统设计说明书》4.4 / 5.3（FR-M3-01~FR-M3-06）。
 *
 * 功能：站点分页查询（名称 / 状态筛选）、新增、编辑、停用与启用、库存预警站点清单。
 *
 * 停用口径（5.3 / UC-P02）：前置条件 = 可用库存为 0 且（非终态实物为 0 或已指定接收站）。
 * 后端是唯一权威：前置不满足时返回业务错误，页面把后端 message 原样展示在对话框内并保持打开，
 * 便于管理员改选接收站后重试——不要自己在前端编造前置校验结论。
 */
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DataPager from '@/components/DataPager.vue'
import StatusTag from '@/components/StatusTag.vue'
import { DEFAULT_PAGE_SIZE, STATION_STATUS_META } from '@/constants'
import {
  createStation,
  listStations,
  listStockWarningStations,
  updateStation,
  updateStationStatus,
} from '@/api/station'

/* ------------------------------ 列表 ------------------------------ */

const query = reactive({
  pageNum: 1,
  pageSize: DEFAULT_PAGE_SIZE,
  name: '',
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

const hasFilter = computed(() => !!(query.name || query.status))

function buildQuery() {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.name) params.name = query.name.trim()
  if (query.status) params.status = query.status
  return params
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await listStations(buildQuery())
    rows.value = data?.records || []
    total.value = data?.total || 0
  } catch (error) {
    // 失败时 request.js 已统一提示；页面负责错误态与重试入口
    errorMessage.value = error?.message || '站点列表加载失败'
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
  query.name = ''
  query.status = ''
  search()
}

/* --------------------------- 站点下拉选项 --------------------------- */

/** 供停用对话框的「接收站」下拉使用；排除自身与已停用站（接收站须存在且未停用） */
const stationOptions = ref([])
const stationLoading = ref(false)

async function loadStationOptions() {
  if (stationLoading.value) return
  stationLoading.value = true
  try {
    const data = await listStations({ pageNum: 1, pageSize: 100 })
    stationOptions.value = data?.records || []
  } catch {
    // 下拉选项加载失败不阻断主流程；request.js 已统一提示，打开对话框时重试
    stationOptions.value = []
  } finally {
    stationLoading.value = false
  }
}

function ensureStationOptions() {
  if (!stationOptions.value.length) loadStationOptions()
}

/* --------------------------- 新增 / 编辑 --------------------------- */

const formVisible = ref(false)
const formMode = ref('create')
const formSubmitting = ref(false)
const formRef = ref(null)
const form = reactive({
  id: null,
  name: '',
  address: '',
  openTime: '',
  warnThreshold: 0,
})

const formRules = {
  name: [
    { required: true, message: '请输入站点名称', trigger: 'blur' },
    { max: 50, message: '站点名称不超过 50 个字符', trigger: 'blur' },
  ],
  address: [
    { required: true, message: '请输入站点地址', trigger: 'blur' },
    { max: 200, message: '站点地址不超过 200 个字符', trigger: 'blur' },
  ],
  openTime: [{ max: 50, message: '开放时段不超过 50 个字符', trigger: 'blur' }],
  warnThreshold: [
    { required: true, message: '请输入库存预警阈值', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        const num = Number(value)
        if (value === null || value === undefined || value === '' || !Number.isInteger(num) || num < 0) {
          return callback(new Error('预警阈值须为不小于 0 的整数（0 表示不预警）'))
        }
        return callback()
      },
      trigger: 'blur',
    },
  ],
}

function openCreate() {
  formMode.value = 'create'
  form.id = null
  form.name = ''
  form.address = ''
  form.openTime = ''
  form.warnThreshold = 0
  formVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

function openEdit(row) {
  formMode.value = 'edit'
  form.id = row.id
  form.name = row.name || ''
  form.address = row.address || ''
  form.openTime = row.openTime || ''
  form.warnThreshold = Number(row.warnThreshold) || 0
  formVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

async function submitForm() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  formSubmitting.value = true
  try {
    const payload = {
      name: form.name.trim(),
      address: form.address.trim(),
      openTime: form.openTime.trim(),
      warnThreshold: Number(form.warnThreshold),
    }
    if (formMode.value === 'create') {
      await createStation(payload)
      ElMessage.success('站点已创建')
    } else {
      await updateStation(form.id, payload)
      ElMessage.success('站点信息已保存，预警阈值即时生效')
    }
    formVisible.value = false
    load()
    // 阈值变更直接影响预警判定（FR-M3-02），同步刷新预警区块
    loadWarnings()
  } catch (error) {
    reportSubmitError(error)
  } finally {
    formSubmitting.value = false
  }
}

/* ------------------------------ 停用 / 启用 ------------------------------ */

const disableVisible = ref(false)
const disableSubmitting = ref(false)
const disableTarget = ref(null)
const handoverStationId = ref(null)
const disableError = ref('')

/** 接收站候选：排除自身与已停用站（接收站须存在且未停用，见 UC-P02） */
const handoverOptions = computed(() =>
  stationOptions.value.filter(
    (item) => item.id !== disableTarget.value?.id && item.status !== 'DISABLED',
  ),
)

function openDisableDialog(row) {
  disableTarget.value = row
  handoverStationId.value = null
  disableError.value = ''
  disableVisible.value = true
  ensureStationOptions()
}

function clearDisableError() {
  disableError.value = ''
}

async function submitDisable() {
  if (!disableTarget.value) return
  disableSubmitting.value = true
  disableError.value = ''
  try {
    await updateStationStatus(disableTarget.value.id, {
      status: 'DISABLED',
      // 可空：留空即按「实物清零」方式停用，由后端前置校验把关
      handoverStationId: handoverStationId.value || null,
    })
    ElMessage.success('站点已停用，该站不再出现在就近站点列表中')
    disableVisible.value = false
    load()
    loadWarnings()
  } catch (error) {
    // 前置不满足（可用库存非 0 / 非终态实物未清零且未指定接收站）：原样展示后端 message，保持对话框打开
    disableError.value = error?.message || '停用失败，请稍后重试'
  } finally {
    disableSubmitting.value = false
  }
}

async function enableStation(row) {
  try {
    await updateStationStatus(row.id, { status: 'ENABLED' })
    ElMessage.success('站点已启用')
    load()
    loadWarnings()
  } catch (error) {
    reportSubmitError(error)
  }
}

/* --------------------------- 库存预警站点区块 --------------------------- */

const warnings = ref([])
const warningLoading = ref(false)
const warningError = ref('')

async function loadWarnings() {
  warningLoading.value = true
  warningError.value = ''
  try {
    const data = await listStockWarningStations()
    // 兼容数组与分页结构两种出参形态
    warnings.value = Array.isArray(data) ? data : data?.records || []
  } catch (error) {
    warningError.value = error?.message || '预警站点加载失败'
    warnings.value = []
  } finally {
    warningLoading.value = false
  }
}

function warningNameOf(item) {
  return item?.stationName || item?.name || (item?.stationId ? `#${item.stationId}` : '—')
}

function warningStockOf(item) {
  const value = item?.availableStock ?? item?.available ?? item?.stock
  return value === null || value === undefined ? '—' : value
}

function warningThresholdOf(item) {
  const value = item?.warnThreshold ?? item?.threshold
  return value === null || value === undefined ? '—' : value
}

/* ------------------------------ 结果提示 ------------------------------ */

/** 3001 的字段级明细由表单侧展示（request.js 对 3001 不弹提示，见 6.6）；其余错误码已由 request.js 提示 */
function reportSubmitError(error) {
  if (error?.code !== 3001) return
  const fieldErrors = error.data?.fieldErrors || {}
  const first = Object.values(fieldErrors)[0]
  const tip = Array.isArray(first) ? first[0] : first
  ElMessage.warning(tip || error.message || '提交内容有误，请检查后重试')
}

/* ------------------------------ 生命周期 ------------------------------ */

onMounted(() => {
  syncLayout()
  window.addEventListener('resize', syncLayout)
  load()
  loadWarnings()
})

onUnmounted(() => {
  window.removeEventListener('resize', syncLayout)
})
</script>

<template>
  <div class="station-manage">
    <PageCard title="站点管理" subtitle="共享站点信息维护、停用移交与库存预警">
      <template #extra>
        <el-button type="primary" @click="openCreate">新增站点</el-button>
      </template>

      <!-- 筛选：名称（模糊）/ 状态 -->
      <el-form class="station-manage__filter" :inline="true" @submit.prevent>
        <el-form-item label="名称">
          <el-input
            v-model="query.name"
            class="station-manage__filter-input"
            placeholder="站点名称模糊查询"
            clearable
            maxlength="50"
            @keyup.enter="search"
            @clear="search"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="query.status"
            class="station-manage__filter-select"
            placeholder="全部状态"
            clearable
            @change="search"
          >
            <el-option
              v-for="(meta, code) in STATION_STATUS_META"
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

      <!-- 加载态：骨架屏 -->
      <PageSkeleton v-if="loading" type="table" :rows="6" />

      <!-- 错误态：给出原因与重试 -->
      <div v-else-if="errorMessage" class="station-manage__error">
        <el-icon class="station-manage__error-icon"><WarningFilled /></el-icon>
        <p class="station-manage__error-text">{{ errorMessage }}</p>
        <el-button type="primary" @click="load">重试</el-button>
      </div>

      <!-- 空态：一句话 + 主操作 -->
      <EmptyState
        v-else-if="!rows.length"
        :description="hasFilter ? '没有符合筛选条件的站点' : '暂时还没有站点，先创建一个吧'"
      >
        <el-button v-if="hasFilter" type="primary" @click="resetQuery">清空筛选条件</el-button>
        <el-button v-else type="primary" @click="openCreate">新增站点</el-button>
      </EmptyState>

      <template v-else>
        <!-- 移动端（<768px）：卡片列表，不出现横向滚动 -->
        <div v-if="isMobile" class="station-manage__cards">
          <article v-for="row in rows" :key="row.id" class="station-manage__card">
            <header class="station-manage__card-head">
              <span class="station-manage__card-title">{{ row.name || '—' }}</span>
              <StatusTag kind="station" :value="row.status" size="small" />
            </header>
            <dl class="station-manage__card-body">
              <div class="station-manage__card-row">
                <dt>地址</dt>
                <dd>{{ row.address || '—' }}</dd>
              </div>
              <div class="station-manage__card-row">
                <dt>开放时段</dt>
                <dd>{{ row.openTime || '—' }}</dd>
              </div>
              <div class="station-manage__card-row">
                <dt>预警阈值</dt>
                <dd class="sems-num">{{ row.warnThreshold ?? '—' }}</dd>
              </div>
            </dl>
            <footer class="station-manage__card-actions">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button
                v-if="row.status === 'ENABLED'"
                link
                type="danger"
                @click="openDisableDialog(row)"
              >
                停用
              </el-button>
              <el-button v-else link type="primary" @click="enableStation(row)">启用</el-button>
            </footer>
          </article>
        </div>

        <!-- 桌面端：表格（行高由全局样式统一 48px）；行内 2 个操作 -->
        <el-table v-else :data="rows" row-key="id" class="station-manage__table">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
          <el-table-column prop="address" label="地址" min-width="220" show-overflow-tooltip />
          <el-table-column label="开放时段" min-width="140">
            <template #default="{ row }">{{ row.openTime || '—' }}</template>
          </el-table-column>
          <el-table-column label="预警阈值" width="110" align="right">
            <template #default="{ row }">
              <span class="sems-num">{{ row.warnThreshold ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <StatusTag kind="station" :value="row.status" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button
                v-if="row.status === 'ENABLED'"
                link
                type="danger"
                size="small"
                @click="openDisableDialog(row)"
              >
                停用
              </el-button>
              <el-button v-else link type="primary" size="small" @click="enableStation(row)">
                启用
              </el-button>
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

    <!-- 库存预警：低于预警阈值的站点清单（FR-M3-06，调拨调度依据） -->
    <PageCard
      class="station-manage__warning"
      title="低于预警阈值站点"
      subtitle="可用库存低于站点预警阈值的站点清单，可作为站点间调拨的调度依据"
    >
      <template #extra>
        <el-button text type="primary" @click="loadWarnings">刷新</el-button>
      </template>

      <PageSkeleton v-if="warningLoading" type="card" :rows="2" />

      <div v-else-if="warningError" class="station-manage__error">
        <el-icon class="station-manage__error-icon"><WarningFilled /></el-icon>
        <p class="station-manage__error-text">{{ warningError }}</p>
        <el-button type="primary" @click="loadWarnings">重试</el-button>
      </div>

      <EmptyState v-else-if="!warnings.length" icon="CircleCheck" description="当前没有低于预警阈值的站点">
        <el-button @click="loadWarnings">刷新</el-button>
      </EmptyState>

      <ul v-else class="station-manage__warning-list">
        <li v-for="(item, index) in warnings" :key="item.stationId || item.id || index" class="station-manage__warning-item">
          <span class="station-manage__warning-name">
            <el-icon class="station-manage__warning-icon"><WarningFilled /></el-icon>
            {{ warningNameOf(item) }}
          </span>
          <span class="station-manage__warning-num sems-num">
            可用库存 {{ warningStockOf(item) }} / 预警阈值 {{ warningThresholdOf(item) }}
          </span>
        </li>
      </ul>
    </PageCard>

    <!-- 新增 / 编辑站点 -->
    <el-dialog
      v-model="formVisible"
      :title="formMode === 'create' ? '新增站点' : '编辑站点'"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="88px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="50" placeholder="站点名称，全平台唯一" />
        </el-form-item>
        <el-form-item label="地址" prop="address">
          <el-input v-model="form.address" maxlength="200" placeholder="站点地址（展示与就近引导）" />
        </el-form-item>
        <el-form-item label="开放时段" prop="openTime">
          <el-input v-model="form.openTime" maxlength="50" placeholder="展示文本，如 08:00-22:00（不做取用硬校验）" />
        </el-form-item>
        <el-form-item label="预警阈值" prop="warnThreshold">
          <el-input-number
            v-model="form.warnThreshold"
            :min="0"
            :step="1"
            :precision="0"
            controls-position="right"
          />
          <span class="station-manage__field-tip">可用库存低于该值时进入预警列表；0 表示不预警</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="formSubmitting" @click="submitForm">
          {{ formMode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 停用站点：前置不满足时展示后端原因并保持对话框打开 -->
    <el-dialog v-model="disableVisible" title="停用站点" width="560px" :close-on-click-modal="false">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="停用前置条件：可用库存为 0，且非终态实物为 0 或已指定接收站"
        description="非终态实物指待消毒/可复用/维修中/空闲的袋；可用库存不为 0 时请先调拨移交。"
      />
      <p class="station-manage__dialog-desc">待停用站点：{{ disableTarget?.name || '—' }}</p>
      <el-form label-width="88px" @submit.prevent>
        <el-form-item label="接收站">
          <el-select
            v-model="handoverStationId"
            class="station-manage__dialog-select"
            clearable
            filterable
            placeholder="可空：留空表示按「实物清零」方式停用"
            :loading="stationLoading"
            @change="clearDisableError"
          >
            <el-option
              v-for="station in handoverOptions"
              :key="station.id"
              :label="station.name"
              :value="station.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="disableError"
        class="station-manage__dialog-error"
        type="error"
        :closable="false"
        show-icon
        :title="disableError"
      />
      <template #footer>
        <el-button @click="disableVisible = false">取消</el-button>
        <el-button type="danger" :loading="disableSubmitting" @click="submitDisable">
          确认停用
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.station-manage__filter {
  margin-bottom: var(--sems-space-2);
}
.station-manage__filter-input {
  width: 200px;
}
.station-manage__filter-select {
  width: 140px;
}
.station-manage__dialog-select {
  width: 100%;
}
.station-manage__dialog-desc {
  margin: var(--sems-space-4) 0 var(--sems-space-4);
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-base);
}
.station-manage__dialog-error {
  margin-top: var(--sems-space-3);
}
.station-manage__field-tip {
  margin-left: var(--sems-space-3);
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-sm);
}

/* 错误态 */
.station-manage__error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-3);
  padding: var(--sems-space-6) 0;
}
.station-manage__error-icon {
  font-size: var(--sems-font-size-kpi);
  color: var(--sems-color-warning);
}
.station-manage__error-text {
  margin: 0;
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-base);
}

/* 移动端卡片列表 */
.station-manage__cards {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
}
.station-manage__card {
  padding: var(--sems-space-4);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-card);
  background-color: var(--sems-bg-card);
}
.station-manage__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-3);
}
.station-manage__card-title {
  font-size: var(--sems-font-size-lg);
  font-weight: var(--sems-font-weight-bold);
  color: var(--sems-text-primary);
}
.station-manage__card-body {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
}
.station-manage__card-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sems-space-4);
}
.station-manage__card-row dt {
  flex: none;
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-sm);
}
.station-manage__card-row dd {
  margin: 0;
  text-align: right;
  color: var(--sems-text-regular);
  font-size: var(--sems-font-size-base);
  word-break: break-all;
}
.station-manage__card-actions {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  margin-top: var(--sems-space-3);
  padding-top: var(--sems-space-3);
  border-top: 1px solid var(--sems-border);
}

/* 预警区块：与上方的站点列表保持同一节奏的间距 */
.station-manage__warning {
  margin-top: var(--sems-space-5);
}
.station-manage__warning-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
}
.station-manage__warning-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-4);
  padding: var(--sems-space-3) 0;
  border-bottom: 1px solid var(--sems-border);
}
.station-manage__warning-item:last-child {
  border-bottom: none;
}
.station-manage__warning-name {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-2);
  color: var(--sems-text-primary);
  font-weight: var(--sems-font-weight-medium);
}
.station-manage__warning-icon {
  color: var(--sems-color-warning);
}
.station-manage__warning-num {
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-sm);
}

@media (max-width: 767px) {
  .station-manage__warning-item {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--sems-space-1);
  }
}
</style>
