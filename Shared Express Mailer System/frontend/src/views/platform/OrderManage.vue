<script setup>
/**
 * 平台端 · 订单管理（设计书 4.4 平台端、5.5 订单管理模块）。
 *
 * 两个页签：
 *  1) 全部订单：按状态 / 站点 / 时间区间筛选（listOrders）；
 *  2) 超时未归还：超时标记清单（listTimeoutOrders），展示按 timeout_time 距今计算的超时时长。
 *
 * 确认遗失（confirmLost）处置口径：袋置为已遗失、订单转已遗失（含已取消订单终态覆盖）、
 * 写入责任记录（responsibleUserId 可空 = 责任待定）。提交前必须二次确认。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import StatusTag from '@/components/StatusTag.vue'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DataPager from '@/components/DataPager.vue'
import { DEFAULT_PAGE_SIZE, ORDER_STATUS_META } from '@/constants'
import { confirmLost, listOrders, listTimeoutOrders } from '@/api/order'
import { listStations } from '@/api/station'
import { listUsers } from '@/api/user'

const route = useRoute()

/** 当前页签：all 全部订单 / timeout 超时未归还 */
const activeTab = ref('all')

const allQuery = reactive({ pageNum: 1, pageSize: DEFAULT_PAGE_SIZE, status: '', stationId: null })
const timeoutQuery = reactive({ pageNum: 1, pageSize: DEFAULT_PAGE_SIZE, stationId: null })
const allTimeRange = ref([])
const timeoutTimeRange = ref([])

const allState = reactive({ loading: false, error: '', records: [], total: 0 })
const timeoutState = reactive({ loading: false, error: '', records: [], total: 0 })

/** 站点下拉：站点列表接口为分页接口，一次取 100 条足够本场景使用 */
const stationOptions = ref([])

const statusOptions = computed(() =>
  Object.entries(ORDER_STATUS_META).map(([value, meta]) => ({ value, label: meta.label })),
)

/** 时间区间默认补齐为「当天 00:00:00 至 23:59:59」 */
const defaultTime = [new Date(2000, 1, 1, 0, 0, 0), new Date(2000, 1, 1, 23, 59, 59)]

const LOST_CONFIRM_TEXT = '袋将置为已遗失，订单转已遗失（含已取消订单终态覆盖），并写入责任记录。'
/** 允许发起确认遗失的订单状态（是否到认定时限由后端权威判定，失败时返回中文 message） */
const CONFIRM_LOST_STATUSES = ['IN_USE', 'PENDING_CONFIRM', 'CANCELLED']

/* ------------------------------ 数据加载 ------------------------------ */

async function loadStations() {
  try {
    const res = await listStations({ pageNum: 1, pageSize: 100 })
    stationOptions.value = res?.records || []
  } catch {
    // 站点下拉加载失败不阻断订单查询，用户仍可按状态与时间筛选
    stationOptions.value = []
  }
}

function buildAllParams() {
  const params = { pageNum: allQuery.pageNum, pageSize: allQuery.pageSize }
  if (allQuery.status) params.status = allQuery.status
  if (allQuery.stationId) params.stationId = allQuery.stationId
  const [begin, end] = allTimeRange.value || []
  if (begin) params.beginTime = begin
  if (end) params.endTime = end
  return params
}

function buildTimeoutParams() {
  const params = { pageNum: timeoutQuery.pageNum, pageSize: timeoutQuery.pageSize }
  if (timeoutQuery.stationId) params.stationId = timeoutQuery.stationId
  const [begin, end] = timeoutTimeRange.value || []
  if (begin) params.beginTime = begin
  if (end) params.endTime = end
  return params
}

async function loadAll() {
  allState.loading = true
  allState.error = ''
  try {
    const res = await listOrders(buildAllParams())
    allState.records = res?.records || []
    allState.total = res?.total || 0
  } catch (error) {
    allState.records = []
    allState.total = 0
    allState.error = error?.message || '订单数据加载失败'
  } finally {
    allState.loading = false
  }
}

async function loadTimeout() {
  timeoutState.loading = true
  timeoutState.error = ''
  try {
    const res = await listTimeoutOrders(buildTimeoutParams())
    timeoutState.records = res?.records || []
    timeoutState.total = res?.total || 0
  } catch (error) {
    timeoutState.records = []
    timeoutState.total = 0
    timeoutState.error = error?.message || '超时订单加载失败'
  } finally {
    timeoutState.loading = false
  }
}

function loadCurrent() {
  return activeTab.value === 'timeout' ? loadTimeout() : loadAll()
}

function onTabChange(name) {
  if (name === 'timeout') loadTimeout()
  else loadAll()
}

function searchAll() {
  allQuery.pageNum = 1
  loadAll()
}

function resetAll() {
  allQuery.status = ''
  allQuery.stationId = null
  allTimeRange.value = []
  allQuery.pageNum = 1
  loadAll()
}

function searchTimeout() {
  timeoutQuery.pageNum = 1
  loadTimeout()
}

function resetTimeout() {
  timeoutQuery.stationId = null
  timeoutTimeRange.value = []
  timeoutQuery.pageNum = 1
  loadTimeout()
}

/* ------------------------------ 展示辅助 ------------------------------ */

function formatTime(value) {
  if (!value) return '—'
  const text = String(value).replace('T', ' ')
  return text.length > 16 ? text.slice(0, 16) : text
}

function isTimeout(row) {
  // 兼容驼峰与下划线两种字段命名（后端 VO 序列化以驼峰为主）
  const flag = row?.timeoutFlag ?? row?.timeout_flag
  return flag === 1 || flag === true || flag === '1'
}

function timeoutTimeOf(row) {
  return row?.timeoutTime || row?.timeout_time || ''
}

/**
 * 超时时长：按 timeout_time 距当前时间计算，中文表述（如「已超时 3 天」）。
 * 超时是标记不是状态（设计书 3.6），这里只做展示换算，不改订单状态。
 */
function overdueText(row) {
  const base = timeoutTimeOf(row)
  if (!base) return '—'
  const diff = Date.now() - new Date(String(base).replace(' ', 'T')).getTime()
  if (Number.isNaN(diff)) return '—'
  if (diff <= 0) return '刚刚超时'
  const minutes = Math.floor(diff / 60000)
  if (minutes < 60) return `已超时 ${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `已超时 ${hours} 小时`
  const days = Math.floor(hours / 24)
  if (days < 30) return `已超时 ${days} 天`
  return `已超时 ${Math.floor(days / 30)} 个月`
}

function userLabel(row) {
  return (
    row?.userName ||
    row?.userNickname ||
    row?.userPhoneMask ||
    row?.userPhone ||
    (row?.userId != null ? `用户 ID ${row.userId}` : '—')
  )
}

function stationText(row) {
  return row?.stationName || (row?.stationId != null ? `站点 ID ${row.stationId}` : '—')
}

function returnStationText(row) {
  return row?.returnStationName || (row?.returnStationId != null ? `站点 ID ${row.returnStationId}` : '—')
}

function expressText(row) {
  const parts = [row?.expressCompany, row?.trackingNo].filter(Boolean)
  return parts.length ? parts.join(' / ') : '—'
}

function stationName(station) {
  return station?.name || station?.stationName || `站点 ID ${station?.id ?? ''}`
}

function canConfirmLost(row) {
  return CONFIRM_LOST_STATUSES.includes(row?.status)
}

/* ------------------------------ 确认遗失 ------------------------------ */

const lostDialog = reactive({
  visible: false,
  submitting: false,
  orderNo: '',
  conclusion: '',
  responsibleUserId: null,
})

/** 责任用户下拉：默认带上订单申领用户，支持按手机号远程检索 */
const userOptions = ref([])
const userLoading = ref(false)

function userOptionLabel(user) {
  const name = user?.nickname || '用户'
  const extra = user?.phone || user?.phoneMask || (user?.id != null ? `ID ${user.id}` : '')
  return extra ? `${name}（${extra}）` : name
}

async function searchUsers(keyword) {
  userLoading.value = true
  try {
    const res = await listUsers({ pageNum: 1, pageSize: 20, phone: keyword || undefined })
    const merged = [...userOptions.value]
    for (const user of res?.records || []) {
      if (!merged.some((item) => item.id === user.id)) merged.push(user)
    }
    userOptions.value = merged
  } catch {
    // 远程检索失败时保留已选项，不阻断确认遗失主流程
  } finally {
    userLoading.value = false
  }
}

function openLost(row) {
  lostDialog.visible = true
  lostDialog.orderNo = row?.orderNo || ''
  lostDialog.conclusion = ''
  lostDialog.responsibleUserId = row?.userId ?? null
  userOptions.value = []
  if (row?.userId != null) {
    userOptions.value.push({
      id: row.userId,
      nickname: row.userName || row.userNickname,
      phone: row.userPhoneMask || row.userPhone,
    })
  }
  searchUsers('')
}

async function submitLost() {
  if (!lostDialog.conclusion.trim()) {
    ElMessage.warning('请填写处置结论')
    return
  }
  try {
    await ElMessageBox.confirm(`${LOST_CONFIRM_TEXT}确认继续？`, '二次确认', {
      type: 'warning',
      confirmButtonText: '确认遗失',
      cancelButtonText: '再想想',
    })
  } catch {
    return
  }
  lostDialog.submitting = true
  try {
    await confirmLost(lostDialog.orderNo, {
      conclusion: lostDialog.conclusion.trim(),
      responsibleUserId: lostDialog.responsibleUserId || null,
    })
    ElMessage.success('已确认遗失，责任记录已写入')
    lostDialog.visible = false
    loadCurrent()
  } catch (error) {
    // 其余错误码的提示已由 request.js 统一弹出，这里只补 3001 的字段级提示
    if (error?.code === 3001) {
      ElMessage.error(error.message || '提交内容未通过校验，请检查后重试')
    }
  } finally {
    lostDialog.submitting = false
  }
}

onMounted(() => {
  // 支持从「异常与风控」页带参数跳转：?tab=timeout 或 ?status=xxx
  if (route.query.tab === 'timeout') activeTab.value = 'timeout'
  if (typeof route.query.status === 'string' && route.query.status) allQuery.status = route.query.status
  loadStations()
  loadAll()
  if (activeTab.value === 'timeout') loadTimeout()
})
</script>

<template>
  <PageCard title="订单管理" subtitle="全量订单查询与超时未归还处置；确认遗失会写入责任记录并列入审计重点核查项">
    <template #extra>
      <el-button :icon="Refresh" @click="loadCurrent">刷新</el-button>
    </template>

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- ============================ 全部订单 ============================ -->
      <el-tab-pane label="全部订单" name="all">
        <el-form class="filter-bar" inline @submit.prevent>
          <el-form-item label="状态">
            <el-select
              v-model="allQuery.status"
              clearable
              placeholder="全部状态"
              style="width: 160px"
              @change="searchAll"
            >
              <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="站点">
            <el-select
              v-model="allQuery.stationId"
              clearable
              filterable
              placeholder="全部站点"
              style="width: 200px"
              @change="searchAll"
            >
              <el-option v-for="station in stationOptions" :key="station.id" :label="stationName(station)" :value="station.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="时间区间">
            <el-date-picker
              v-model="allTimeRange"
              type="datetimerange"
              :default-time="defaultTime"
              value-format="YYYY-MM-DD HH:mm:ss"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              style="width: 360px"
              @change="searchAll"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="searchAll">查询</el-button>
            <el-button @click="resetAll">重置</el-button>
          </el-form-item>
        </el-form>

        <PageSkeleton v-if="allState.loading" type="table" :rows="6" />

        <div v-else-if="allState.error" class="state-block">
          <p class="state-block__title">订单数据加载失败</p>
          <p class="sems-text-muted">{{ allState.error }}</p>
          <el-button type="primary" @click="loadAll">重试</el-button>
        </div>

        <EmptyState v-else-if="!allState.records.length" description="暂无符合条件的订单">
          <el-button type="primary" @click="resetAll">重置筛选条件</el-button>
        </EmptyState>

        <template v-else>
          <div class="sems-desktop-only">
            <el-table :data="allState.records" stripe>
              <el-table-column label="订单号" min-width="170">
                <template #default="{ row }">
                  <span class="sems-mono">{{ row.orderNo || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="用户" min-width="130">
                <template #default="{ row }">{{ userLabel(row) }}</template>
              </el-table-column>
              <el-table-column label="袋码" min-width="140">
                <template #default="{ row }">
                  <span class="sems-mono">{{ row.bagCode || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="取袋站点" min-width="130">
                <template #default="{ row }">{{ stationText(row) }}</template>
              </el-table-column>
              <el-table-column label="归还站点" min-width="130">
                <template #default="{ row }">{{ returnStationText(row) }}</template>
              </el-table-column>
              <el-table-column label="状态" min-width="160">
                <template #default="{ row }">
                  <span class="status-cell">
                    <StatusTag kind="order" :value="row.status" size="small" />
                    <el-tooltip v-if="isTimeout(row)" :content="`超时标记时间：${formatTime(timeoutTimeOf(row))}`">
                      <el-tag size="small" type="warning" effect="plain">超时</el-tag>
                    </el-tooltip>
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="快递公司 / 运单号" min-width="170">
                <template #default="{ row }">{{ expressText(row) }}</template>
              </el-table-column>
              <el-table-column label="创建 / 寄件 / 确认" min-width="160">
                <template #default="{ row }">
                  <span class="time-cell">
                    <span>创建 {{ formatTime(row.createdAt) }}</span>
                    <span>寄件 {{ formatTime(row.shippedAt) }}</span>
                    <span>确认 {{ formatTime(row.confirmedAt) }}</span>
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button v-if="canConfirmLost(row)" link type="danger" size="small" @click="openLost(row)">
                    确认遗失
                  </el-button>
                  <span v-else class="sems-text-muted">—</span>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="sems-mobile-only">
            <article v-for="row in allState.records" :key="row.id || row.orderNo" class="list-card">
              <header class="list-card__head">
                <span class="sems-mono">{{ row.orderNo || '—' }}</span>
                <span class="status-cell">
                  <StatusTag kind="order" :value="row.status" size="small" />
                  <el-tag v-if="isTimeout(row)" size="small" type="warning" effect="plain">超时</el-tag>
                </span>
              </header>
              <p class="list-card__line"><span class="sems-text-muted">用户</span><span>{{ userLabel(row) }}</span></p>
              <p class="list-card__line"><span class="sems-text-muted">袋码</span><span class="sems-mono">{{ row.bagCode || '—' }}</span></p>
              <p class="list-card__line"><span class="sems-text-muted">取袋站点</span><span>{{ stationText(row) }}</span></p>
              <p class="list-card__line"><span class="sems-text-muted">归还站点</span><span>{{ returnStationText(row) }}</span></p>
              <p class="list-card__line"><span class="sems-text-muted">快递</span><span>{{ expressText(row) }}</span></p>
              <p class="list-card__line">
                <span class="sems-text-muted">时间</span>
                <span>创建 {{ formatTime(row.createdAt) }}；寄件 {{ formatTime(row.shippedAt) }}；确认 {{ formatTime(row.confirmedAt) }}</span>
              </p>
              <div v-if="canConfirmLost(row)" class="list-card__actions">
                <el-button link type="danger" size="small" @click="openLost(row)">确认遗失</el-button>
              </div>
            </article>
          </div>

          <DataPager
            v-model:page="allQuery.pageNum"
            v-model:size="allQuery.pageSize"
            :total="allState.total"
            :disabled="allState.loading"
            @change="loadAll"
          />
        </template>
      </el-tab-pane>

      <!-- ========================== 超时未归还 ========================== -->
      <el-tab-pane label="超时未归还" name="timeout">
        <el-form class="filter-bar" inline @submit.prevent>
          <el-form-item label="站点">
            <el-select
              v-model="timeoutQuery.stationId"
              clearable
              filterable
              placeholder="全部站点"
              style="width: 200px"
              @change="searchTimeout"
            >
              <el-option v-for="station in stationOptions" :key="station.id" :label="stationName(station)" :value="station.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="时间区间">
            <el-date-picker
              v-model="timeoutTimeRange"
              type="datetimerange"
              :default-time="defaultTime"
              value-format="YYYY-MM-DD HH:mm:ss"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              style="width: 360px"
              @change="searchTimeout"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="searchTimeout">查询</el-button>
            <el-button @click="resetTimeout">重置</el-button>
          </el-form-item>
        </el-form>

        <PageSkeleton v-if="timeoutState.loading" type="table" :rows="6" />

        <div v-else-if="timeoutState.error" class="state-block">
          <p class="state-block__title">超时订单加载失败</p>
          <p class="sems-text-muted">{{ timeoutState.error }}</p>
          <el-button type="primary" @click="loadTimeout">重试</el-button>
        </div>

        <EmptyState v-else-if="!timeoutState.records.length" description="当前没有超时未归还的订单">
          <el-button type="primary" @click="resetTimeout">重置筛选条件</el-button>
        </EmptyState>

        <template v-else>
          <div class="sems-desktop-only">
            <el-table :data="timeoutState.records" stripe>
              <el-table-column label="订单号" min-width="170">
                <template #default="{ row }">
                  <span class="sems-mono">{{ row.orderNo || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="用户" min-width="130">
                <template #default="{ row }">{{ userLabel(row) }}</template>
              </el-table-column>
              <el-table-column label="袋码" min-width="140">
                <template #default="{ row }">
                  <span class="sems-mono">{{ row.bagCode || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="取袋站点" min-width="130">
                <template #default="{ row }">{{ stationText(row) }}</template>
              </el-table-column>
              <el-table-column label="归还站点" min-width="130">
                <template #default="{ row }">{{ returnStationText(row) }}</template>
              </el-table-column>
              <el-table-column label="状态" min-width="150">
                <template #default="{ row }">
                  <span class="status-cell">
                    <StatusTag kind="order" :value="row.status" size="small" />
                    <el-tag size="small" type="warning" effect="plain">超时</el-tag>
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="超时时长" min-width="150">
                <template #default="{ row }">
                  <span class="overdue-text">{{ overdueText(row) }}</span>
                  <span class="sems-text-muted overdue-base">标记于 {{ formatTime(timeoutTimeOf(row)) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button v-if="canConfirmLost(row)" link type="danger" size="small" @click="openLost(row)">
                    确认遗失
                  </el-button>
                  <span v-else class="sems-text-muted">—</span>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="sems-mobile-only">
            <article v-for="row in timeoutState.records" :key="row.id || row.orderNo" class="list-card">
              <header class="list-card__head">
                <span class="sems-mono">{{ row.orderNo || '—' }}</span>
                <span class="status-cell">
                  <StatusTag kind="order" :value="row.status" size="small" />
                  <el-tag size="small" type="warning" effect="plain">超时</el-tag>
                </span>
              </header>
              <p class="list-card__line"><span class="sems-text-muted">用户</span><span>{{ userLabel(row) }}</span></p>
              <p class="list-card__line"><span class="sems-text-muted">袋码</span><span class="sems-mono">{{ row.bagCode || '—' }}</span></p>
              <p class="list-card__line"><span class="sems-text-muted">取袋站点</span><span>{{ stationText(row) }}</span></p>
              <p class="list-card__line"><span class="sems-text-muted">归还站点</span><span>{{ returnStationText(row) }}</span></p>
              <p class="list-card__line">
                <span class="sems-text-muted">超时时长</span>
                <span class="overdue-text">{{ overdueText(row) }}（标记于 {{ formatTime(timeoutTimeOf(row)) }}）</span>
              </p>
              <div v-if="canConfirmLost(row)" class="list-card__actions">
                <el-button link type="danger" size="small" @click="openLost(row)">确认遗失</el-button>
              </div>
            </article>
          </div>

          <DataPager
            v-model:page="timeoutQuery.pageNum"
            v-model:size="timeoutQuery.pageSize"
            :total="timeoutState.total"
            :disabled="timeoutState.loading"
            @change="loadTimeout"
          />
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- ========================== 确认遗失对话框 ========================== -->
    <el-dialog v-model="lostDialog.visible" title="确认遗失" width="520px" :close-on-click-modal="false">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        :title="LOST_CONFIRM_TEXT"
        description="确认后不可撤销；责任用户留空表示责任待定，可由平台管理员后续认定。"
      />
      <el-form class="lost-form" label-width="88px">
        <el-form-item label="订单号">
          <span class="sems-mono">{{ lostDialog.orderNo || '—' }}</span>
        </el-form-item>
        <el-form-item label="处置结论" required>
          <el-input
            v-model="lostDialog.conclusion"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="请填写责任认定与处置结论（必填）"
          />
        </el-form-item>
        <el-form-item label="责任用户">
          <el-select
            v-model="lostDialog.responsibleUserId"
            clearable
            filterable
            remote
            :remote-method="searchUsers"
            :loading="userLoading"
            placeholder="留空 = 责任待定"
            style="width: 100%"
          >
            <el-option v-for="user in userOptions" :key="user.id" :label="userOptionLabel(user)" :value="user.id" />
          </el-select>
          <span class="sems-text-muted">默认取订单申领用户；清空表示责任待定。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lostDialog.visible = false">取消</el-button>
        <el-button type="danger" :loading="lostDialog.submitting" @click="submitLost">确认遗失</el-button>
      </template>
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

.status-cell {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-1);
}

.time-cell {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-1);
  font-size: var(--sems-font-size-xs);
  color: var(--sems-text-secondary);
  font-variant-numeric: tabular-nums;
}

.overdue-text {
  display: block;
  color: var(--sems-color-warning);
  font-weight: var(--sems-font-weight-medium);
  font-variant-numeric: tabular-nums;
}
.overdue-base {
  display: block;
  font-size: var(--sems-font-size-xs);
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
.list-card__actions {
  display: flex;
  justify-content: flex-end;
  padding-top: var(--sems-space-1);
}

.lost-form {
  margin-top: var(--sems-space-4);
}
</style>
