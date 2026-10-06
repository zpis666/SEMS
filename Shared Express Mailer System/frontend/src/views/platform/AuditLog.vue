<script setup>
/**
 * 平台端 · 操作审计（设计书 4.4 平台端、5.6 后台管理）。
 *
 * 按操作人 / 模块 / 动作（action）/ 时间区间筛选；提供「审计重点核查项」快捷筛选
 * （报废、直接报废、状态修正、回收确认族、越权尝试）。
 *
 * 隐私口径（设计书 7.6）：IP 列默认掩码展示；点「查看完整」在对话框中查看完整值，
 * 且查看行为会被记录到审计日志——对话框内必须明示该提示。
 * 展开行展示脱敏后的请求参数（params_masked），审计表只插不改。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DataPager from '@/components/DataPager.vue'
import { DEFAULT_PAGE_SIZE, ROLE_LABEL } from '@/constants'
import { getAuditLogIp, listAuditLogs } from '@/api/stats'

const route = useRoute()

const query = reactive({
  pageNum: 1,
  pageSize: DEFAULT_PAGE_SIZE,
  operatorId: null,
  module: '',
  action: '',
})
const timeRange = ref([])
const state = reactive({ loading: false, error: '', records: [], total: 0 })

/** 时间区间默认补齐为「当天 00:00:00 至 23:59:59」 */
const defaultTime = [new Date(2000, 1, 1, 0, 0, 0), new Date(2000, 1, 1, 23, 59, 59)]

/**
 * 审计重点核查项（设计书 7.6 / 9.1）：报废与状态修正、回收确认族（按上下文分直投/跨站）、
 * 越权拦截事件。点击即把动作筛选设为该值，再次点击取消。
 */
const QUICK_ACTIONS = ['报废', '直接报废', '状态修正', '回收确认（直投）', '回收确认（跨站）', '越权尝试']

/** 动作下拉的常用建议值（允许输入任意动作名） */
const ACTION_SUGGESTIONS = [
  '报废',
  '直接报废',
  '状态修正',
  '回收确认（直投）',
  '回收确认（跨站）',
  '消毒登记',
  '上架入库',
  '激活上架',
  '未使用回收',
  '找回',
  '越权尝试',
]

/* ------------------------------ 数据加载 ------------------------------ */

function buildParams() {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.operatorId) params.operatorId = query.operatorId
  if (query.module) params.module = query.module.trim()
  if (query.action) params.action = query.action
  const [begin, end] = timeRange.value || []
  if (begin) params.beginTime = begin
  if (end) params.endTime = end
  return params
}

async function load() {
  state.loading = true
  state.error = ''
  try {
    const res = await listAuditLogs(buildParams())
    state.records = res?.records || []
    state.total = res?.total || 0
  } catch (error) {
    state.records = []
    state.total = 0
    state.error = error?.message || '审计日志加载失败'
  } finally {
    state.loading = false
  }
}

function search() {
  query.pageNum = 1
  load()
}

function reset() {
  query.operatorId = null
  query.module = ''
  query.action = ''
  timeRange.value = []
  search()
}

function toggleAction(action) {
  query.action = query.action === action ? '' : action
  search()
}

/* ------------------------------ 展示辅助 ------------------------------ */

function formatTime(value) {
  if (!value) return '—'
  const text = String(value).replace('T', ' ')
  return text.length > 19 ? text.slice(0, 19) : text
}

function roleText(row) {
  return ROLE_LABEL[row?.operatorRole] || row?.operatorRole || '—'
}

function operatorText(row) {
  return row?.operatorId != null ? String(row.operatorId) : '—'
}

function isSuccess(row) {
  return row?.result === 1 || row?.result === true || row?.result === '1'
}

function errorCodeText(row) {
  if (isSuccess(row)) return '—'
  return row?.errorCode != null ? String(row.errorCode) : '—'
}

function costText(row) {
  return row?.costMs != null ? `${row.costMs} ms` : '—'
}

function rowKey(row) {
  return row?.id ?? `${row?.createdAt}-${row?.operatorId}-${row?.action}`
}

/** 脱敏参数：兼容 camelCase 与下划线两种字段名，可解析的 JSON 做缩进展示 */
function paramsText(row) {
  const raw = row?.paramsMasked ?? row?.params_masked
  if (raw == null || raw === '') return '（无参数记录）'
  if (typeof raw === 'object') return JSON.stringify(raw, null, 2)
  const text = String(raw)
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

/* ------------------------------ IP 掩码与查看 ------------------------------ */

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

/** 列表 IP：后端 VO 只回掩码值 ipMasked（7.6 列表一律掩码），完整值须逐条查看 */
function listIpOf(row) {
  return row?.ipMasked || ''
}

const ipDialog = reactive({ visible: false, ip: '', loading: false })

/**
 * 查看完整 IP：调受控端点 GET /audit-logs/{id}/ip（7.6：详情可看完整值，查看行为记审计）。
 * 列表不携带完整 IP，避免整页批量泄露；一次查看只取一条。
 */
async function openIp(row) {
  if (!row?.id) return
  ipDialog.visible = true
  ipDialog.loading = true
  ipDialog.ip = ''
  try {
    ipDialog.ip = (await getAuditLogIp(row.id)) || '—'
  } catch {
    // 失败提示由 request.js 统一弹出；对话框内以占位表达
    ipDialog.ip = '—'
  } finally {
    ipDialog.loading = false
  }
}

onMounted(() => {
  // 支持从「异常与风控」页带参数跳转：?action=xxx 与 ?operatorId=xxx
  if (typeof route.query.action === 'string' && route.query.action) query.action = route.query.action
  if (typeof route.query.module === 'string' && route.query.module) query.module = route.query.module
  const operatorId = Number(route.query.operatorId)
  if (Number.isFinite(operatorId) && operatorId > 0) query.operatorId = operatorId
  load()
})
</script>

<template>
  <PageCard title="操作审计" subtitle="关键事件强制留痕；IP 默认掩码展示，查看完整值的行为同样会被记录到审计日志">
    <template #extra>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </template>

    <el-form class="filter-bar" inline @submit.prevent>
      <el-form-item label="操作人 ID">
        <el-input-number
          v-model="query.operatorId"
          :min="1"
          :controls="false"
          placeholder="如 10023"
          style="width: 130px"
        />
      </el-form-item>
      <el-form-item label="模块">
        <el-input v-model="query.module" clearable placeholder="如：快递袋管理" style="width: 180px" @keyup.enter="search" />
      </el-form-item>
      <el-form-item label="动作">
        <el-select
          v-model="query.action"
          clearable
          filterable
          allow-create
          default-first-option
          placeholder="如：报废 / 状态修正"
          style="width: 200px"
          @change="search"
        >
          <el-option v-for="item in ACTION_SUGGESTIONS" :key="item" :label="item" :value="item" />
        </el-select>
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

    <div class="quick-bar">
      <span class="sems-text-muted">审计重点核查项：</span>
      <el-button
        v-for="action in QUICK_ACTIONS"
        :key="action"
        size="small"
        :type="query.action === action ? 'primary' : ''"
        @click="toggleAction(action)"
      >
        {{ action }}
      </el-button>
    </div>

    <el-alert
      class="mask-tip"
      type="info"
      :closable="false"
      show-icon
      title="IP 列表默认按 192.168.*.* 掩码展示；查看完整 IP 的行为会被记录到审计日志。"
    />

    <PageSkeleton v-if="state.loading" type="table" :rows="8" />

    <div v-else-if="state.error" class="state-block">
      <p class="state-block__title">审计日志加载失败</p>
      <p class="sems-text-muted">{{ state.error }}</p>
      <el-button type="primary" @click="load">重试</el-button>
    </div>

    <EmptyState v-else-if="!state.records.length" description="暂无符合条件的审计记录">
      <el-button type="primary" @click="reset">重置筛选条件</el-button>
    </EmptyState>

    <template v-else>
      <div class="sems-desktop-only">
        <el-table :data="state.records" :row-key="rowKey" stripe>
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="detail-block">
                <p class="detail-block__title">请求参数（脱敏后）</p>
                <pre class="detail-block__pre sems-mono">{{ paramsText(row) }}</pre>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="时间" min-width="160">
            <template #default="{ row }">
              <span class="sems-num">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作人" min-width="90">
            <template #default="{ row }">
              <span class="sems-num">{{ operatorText(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="角色" min-width="110">
            <template #default="{ row }">{{ roleText(row) }}</template>
          </el-table-column>
          <el-table-column label="模块" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.module || '—' }}</template>
          </el-table-column>
          <el-table-column label="动作" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.action || '—' }}</template>
          </el-table-column>
          <el-table-column label="请求 URI" min-width="190" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="sems-mono">{{ row.requestUri || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" min-width="90">
            <template #default="{ row }">
              <span v-if="isSuccess(row)" class="result result--success">
                <el-icon><CircleCheckFilled /></el-icon>成功
              </span>
              <span v-else class="result result--danger">
                <el-icon><CircleCloseFilled /></el-icon>失败
              </span>
            </template>
          </el-table-column>
          <el-table-column label="错误码" min-width="80">
            <template #default="{ row }">
              <span class="sems-num">{{ errorCodeText(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="耗时" min-width="90">
            <template #default="{ row }">
              <span class="sems-num">{{ costText(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="IP" min-width="180">
            <template #default="{ row }">
              <span class="ip-cell">
                <span class="sems-mono">{{ maskIp(listIpOf(row)) }}</span>
                <el-button v-if="listIpOf(row)" link type="primary" size="small" @click="openIp(row)">
                  查看完整
                </el-button>
              </span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="sems-mobile-only">
        <article v-for="row in state.records" :key="rowKey(row)" class="list-card">
          <header class="list-card__head">
            <span class="sems-mono">{{ formatTime(row.createdAt) }}</span>
            <span v-if="isSuccess(row)" class="result result--success">
              <el-icon><CircleCheckFilled /></el-icon>成功
            </span>
            <span v-else class="result result--danger">
              <el-icon><CircleCloseFilled /></el-icon>失败
            </span>
          </header>
          <p class="list-card__line"><span class="sems-text-muted">操作人</span><span>{{ operatorText(row) }}（{{ roleText(row) }}）</span></p>
          <p class="list-card__line"><span class="sems-text-muted">模块 / 动作</span><span>{{ row.module || '—' }} / {{ row.action || '—' }}</span></p>
          <p class="list-card__line"><span class="sems-text-muted">请求 URI</span><span class="sems-mono">{{ row.requestUri || '—' }}</span></p>
          <p class="list-card__line"><span class="sems-text-muted">错误码 / 耗时</span><span>{{ errorCodeText(row) }} / {{ costText(row) }}</span></p>
          <p class="list-card__line">
            <span class="sems-text-muted">IP</span>
            <span class="ip-cell">
              <span class="sems-mono">{{ maskIp(listIpOf(row)) }}</span>
              <el-button v-if="listIpOf(row)" link type="primary" size="small" @click="openIp(row)">查看完整</el-button>
            </span>
          </p>
          <p class="list-card__line list-card__line--block">
            <span class="sems-text-muted">请求参数（脱敏）</span>
            <span class="sems-mono detail-inline">{{ paramsText(row) }}</span>
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
      <p class="ip-full sems-mono">{{ ipDialog.loading ? '获取中…' : ipDialog.ip || '—' }}</p>
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
  margin-bottom: var(--sems-space-2);
}

.quick-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-3);
}

.mask-tip {
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

.detail-block {
  padding: var(--sems-space-2) var(--sems-space-4);
}
.detail-block__title {
  margin: 0 0 var(--sems-space-1);
  font-size: var(--sems-font-size-sm);
  color: var(--sems-text-secondary);
}
.detail-block__pre {
  margin: 0;
  max-height: 240px;
  overflow: auto;
  padding: var(--sems-space-2);
  background-color: var(--sems-bg-page);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-base);
  font-size: var(--sems-font-size-xs);
  color: var(--sems-text-regular);
  white-space: pre-wrap;
  word-break: break-all;
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
.detail-inline {
  font-size: var(--sems-font-size-xs);
  color: var(--sems-text-secondary);
  word-break: break-all;
  white-space: pre-wrap;
}
</style>
