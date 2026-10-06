<script setup>
/**
 * 平台端 · 登录日志（设计书 4.4 平台端、5.6 后台管理）。
 *
 * 按登录结果 / 手机号掩码 / 时间区间筛选；成功与失败带图标区分（非仅颜色）。
 * IP 默认掩码展示，「查看完整」在对话框内查看并明示该行为会被记录到审计日志；
 * UA 超长省略、悬停显示全文。
 */
import { onMounted, reactive, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DataPager from '@/components/DataPager.vue'
import { DEFAULT_PAGE_SIZE } from '@/constants'
import { listLoginLogs } from '@/api/stats'

const query = reactive({
  pageNum: 1,
  pageSize: DEFAULT_PAGE_SIZE,
  result: null,
  phone: '',
})
const timeRange = ref([])
const state = reactive({ loading: false, error: '', records: [], total: 0 })

const RESULT_OPTIONS = [
  { label: '成功', value: 1 },
  { label: '失败', value: 0 },
]

/** 时间区间默认补齐为「当天 00:00:00 至 23:59:59」 */
const defaultTime = [new Date(2000, 1, 1, 0, 0, 0), new Date(2000, 1, 1, 23, 59, 59)]

/* ------------------------------ 数据加载 ------------------------------ */

function buildParams() {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.result === 0 || query.result === 1) params.result = query.result
  if (query.phone) params.phone = query.phone.trim()
  const [begin, end] = timeRange.value || []
  if (begin) params.beginTime = begin
  if (end) params.endTime = end
  return params
}

async function load() {
  state.loading = true
  state.error = ''
  try {
    const res = await listLoginLogs(buildParams())
    state.records = res?.records || []
    state.total = res?.total || 0
  } catch (error) {
    state.records = []
    state.total = 0
    state.error = error?.message || '登录日志加载失败'
  } finally {
    state.loading = false
  }
}

function search() {
  query.pageNum = 1
  load()
}

function reset() {
  query.result = null
  query.phone = ''
  timeRange.value = []
  search()
}

/* ------------------------------ 展示辅助 ------------------------------ */

function formatTime(value) {
  if (!value) return '—'
  const text = String(value).replace('T', ' ')
  return text.length > 19 ? text.slice(0, 19) : text
}

function isSuccess(row) {
  return row?.result === 1 || row?.result === true || row?.result === '1'
}

function phoneText(row) {
  return row?.phoneMask || row?.phone || '—'
}

function userIdText(row) {
  return row?.userId != null ? String(row.userId) : '—'
}

function failReasonText(row) {
  return row?.failReason || '—'
}

/** IPv4 保留前两段（如 192.168.1.23 显示为 192.168.*.*）；已掩码的值原样返回 */
function maskIp(ip) {
  if (!ip) return '—'
  const text = String(ip)
  if (text.includes('*')) return text
  if (text.includes(':')) {
    const groups = text.split(':')
    return groups.length >= 2 ? `${groups[0]}:${groups[1]}:*:*:*:*:*:*` : `${groups[0]}:*`
  }
  const parts = text.split('.')
  if (parts.length === 4) return `${parts[0]}.${parts[1]}.*.*`
  return '***'
}

/** 完整 IP：优先取专用字段；若列表只回掩码，则对话框展示掩码值本身 */
function fullIpOf(row) {
  return row?.ipFull || row?.fullIp || row?.ip || ''
}

const ipDialog = reactive({ visible: false, ip: '' })

function openIp(row) {
  ipDialog.ip = fullIpOf(row) || '—'
  ipDialog.visible = true
}

onMounted(load)
</script>

<template>
  <PageCard title="登录日志" subtitle="登录成功与失败均留痕（含失败原因）；IP 默认掩码，查看完整值的行为会被记录到审计日志">
    <template #extra>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </template>

    <el-form class="filter-bar" inline @submit.prevent>
      <el-form-item label="结果">
        <el-select v-model="query.result" clearable placeholder="全部" style="width: 120px" @change="search">
          <el-option v-for="item in RESULT_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="手机号掩码">
        <el-input
          v-model="query.phone"
          clearable
          placeholder="如 138****1234"
          style="width: 180px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="时间区间">
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          :default-time="defaultTime"
          value-format="YYYY-MM-DD HH:mm:ss"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 360px"
          @change="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <PageSkeleton v-if="state.loading" type="table" :rows="8" />

    <div v-else-if="state.error" class="state-block">
      <p class="state-block__title">登录日志加载失败</p>
      <p class="sems-text-muted">{{ state.error }}</p>
      <el-button type="primary" @click="load">重试</el-button>
    </div>

    <EmptyState v-else-if="!state.records.length" description="暂无符合条件的登录记录">
      <el-button type="primary" @click="reset">重置筛选条件</el-button>
    </EmptyState>

    <template v-else>
      <div class="sems-desktop-only">
        <el-table :data="state.records" stripe>
          <el-table-column label="时间" min-width="160">
            <template #default="{ row }">
              <span class="sems-num">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="用户 ID" min-width="90">
            <template #default="{ row }">
              <span class="sems-num">{{ userIdText(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="手机号掩码" min-width="130">
            <template #default="{ row }">
              <span class="sems-mono">{{ phoneText(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" min-width="100">
            <template #default="{ row }">
              <span v-if="isSuccess(row)" class="result result--success">
                <el-icon><CircleCheckFilled /></el-icon>成功
              </span>
              <span v-else class="result result--danger">
                <el-icon><CircleCloseFilled /></el-icon>失败
              </span>
            </template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ isSuccess(row) ? '—' : failReasonText(row) }}</template>
          </el-table-column>
          <el-table-column label="IP" min-width="190">
            <template #default="{ row }">
              <span class="ip-cell">
                <span class="sems-mono">{{ maskIp(fullIpOf(row)) }}</span>
                <el-button v-if="fullIpOf(row)" link type="primary" size="small" @click="openIp(row)">
                  查看完整
                </el-button>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="UA（悬停查看全文）" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ row.ua || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="sems-mobile-only">
        <article v-for="row in state.records" :key="row.id || `${row.createdAt}-${row.userId}`" class="list-card">
          <header class="list-card__head">
            <span class="sems-mono">{{ formatTime(row.createdAt) }}</span>
            <span v-if="isSuccess(row)" class="result result--success">
              <el-icon><CircleCheckFilled /></el-icon>成功
            </span>
            <span v-else class="result result--danger">
              <el-icon><CircleCloseFilled /></el-icon>失败
            </span>
          </header>
          <p class="list-card__line"><span class="sems-text-muted">用户 ID</span><span>{{ userIdText(row) }}</span></p>
          <p class="list-card__line"><span class="sems-text-muted">手机号掩码</span><span class="sems-mono">{{ phoneText(row) }}</span></p>
          <p class="list-card__line"><span class="sems-text-muted">失败原因</span><span>{{ isSuccess(row) ? '—' : failReasonText(row) }}</span></p>
          <p class="list-card__line">
            <span class="sems-text-muted">IP</span>
            <span class="ip-cell">
              <span class="sems-mono">{{ maskIp(fullIpOf(row)) }}</span>
              <el-button v-if="fullIpOf(row)" link type="primary" size="small" @click="openIp(row)">查看完整</el-button>
            </span>
          </p>
          <p class="list-card__line list-card__line--block">
            <span class="sems-text-muted">User-Agent</span>
            <span class="ua-text">{{ row.ua || '—' }}</span>
          </p>
        </article>
      </div>

      <DataPager
        v-model:page="query.pageNum"
        v-model:size="query.pageSize"
        :total="state.total"
        :disabled="state.loading"
        @change="load"
      />
    </template>

    <!-- ========================== 完整 IP 对话框 ========================== -->
    <el-dialog v-model="ipDialog.visible" title="完整 IP" width="420px">
      <p class="ip-full sems-mono">{{ ipDialog.ip }}</p>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="查看完整 IP 的行为会被记录到审计日志"
      />
    </el-dialog>
  </PageCard>
</template>

<style scoped>
.filter-bar {
  margin-bottom: var(--sems-space-3);
}

.state-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-2);
  padding: var(--sems-space-6) 0;
}
.state-block__title {
  margin: 0;
  font-size: var(--sems-font-size-base);
  color: var(--sems-text-primary);
}

.result {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-1);
  font-weight: var(--sems-font-weight-medium);
}
.result--success {
  color: var(--sems-color-success);
}
.result--danger {
  color: var(--sems-color-danger);
}

.ip-cell {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-1);
}

.ip-full {
  margin: 0 0 var(--sems-space-3);
  font-size: var(--sems-font-size-lg);
  color: var(--sems-text-primary);
}

.list-card {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-1);
  padding: var(--sems-space-3);
  margin-bottom: var(--sems-space-3);
  background-color: var(--sems-bg-card);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-card);
}
.list-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  padding-bottom: var(--sems-space-2);
  border-bottom: 1px solid var(--sems-border);
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-primary);
}
.list-card__line {
  display: flex;
  justify-content: space-between;
  gap: var(--sems-space-3);
  margin: 0;
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-regular);
}
.list-card__line--block {
  flex-direction: column;
  gap: var(--sems-space-1);
}
.ua-text {
  font-size: var(--sems-font-size-xs);
  color: var(--sems-text-secondary);
  word-break: break-all;
}
</style>
