<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getBagInfo } from '@/api/bag'
import { getRecycleBin, recycleConfirm } from '@/api/station'
import { BATCH_MAX_SIZE, DEFAULT_PAGE_SIZE } from '@/constants'
import { extractBatch } from '@/utils/batch'
import { BAG_CODE_REGEX, toUpperCase } from '@/utils/validators'
import { useUserStore } from '@/stores/user'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DataPager from '@/components/DataPager.vue'
import StatusTag from '@/components/StatusTag.vue'
import BagCodeInput from '@/components/BagCodeInput.vue'
import BatchResultDialog from '@/components/BatchResultDialog.vue'

/**
 * 站点端 · 回收确认（设计书 4.3 / 5.3，转移 4/5/7/8）。
 *
 * 1) 顶部常驻扫码输入：扫一袋入一行，本地去重，单批上限 500 只（BATCH_MAX_SIZE）；
 * 2) 每袋可勾选「曾寄出」：用户迟寄未登记时勾选，后端按待消毒路径处理并计复用次数；
 * 3) 提交调 recycleConfirm(stationId, { bags: [{ bagCode, shippedBefore }] })，
 *    逐袋结果用 BatchResultDialog 展示；单只失败不影响其余；
 * 4) 提交后清空列表并刷新右侧抽屉的回收箱清单（getRecycleBin）。
 */

const userStore = useUserStore()
const stationId = computed(() => userStore.stationId)

/* ------------------------------ 待确认列表 ------------------------------ */

const scanCode = ref('')
const pendingList = ref([])
const submitting = ref(false)

const resultVisible = ref(false)
/** 逐袋结果明细（后端统一信封 data.items，经 utils/batch.js 解析） */
const resultItems = ref([])
/** 批次计数：来自后端信封（items 可能被后端截断，计数以后端为准） */
const resultTotal = ref(0)
const resultFailed = ref(0)
/** 审计口径补充行：本批被记成的审计动作计数（跨站 / 未标记直投 / 代操作） */
const resultExtraSummary = ref('')

function addBag(raw) {
  if (!stationId.value) return
  const code = toUpperCase(typeof raw === 'string' && raw ? raw : scanCode.value)
  if (!code) {
    ElMessage.warning('请输入或扫描袋码')
    return
  }
  if (!BAG_CODE_REGEX.test(code)) {
    ElMessage.warning('袋码格式不正确（形如 SJB-L-2609-00001-4），请核对后重试')
    return
  }
  if (pendingList.value.some((row) => row.bagCode === code)) {
    ElMessage.warning(`袋码 ${code} 已在待确认列表中`)
    scanCode.value = ''
    return
  }
  if (pendingList.value.length >= BATCH_MAX_SIZE) {
    ElMessage.warning(`单批最多 ${BATCH_MAX_SIZE} 只，请先提交当前批次`)
    return
  }
  // 用 reactive 包装：状态是异步回填的，必须经代理写入才能触发视图更新
  const row = reactive({ bagCode: code, shippedBefore: false, status: '', statusLoading: true })
  pendingList.value.push(row)
  scanCode.value = ''
  loadBagStatus(row)
}

/** 尽力回填袋当前状态（归属关系不足时后端按最小必要集返回，状态可能取不到） */
async function loadBagStatus(row) {
  try {
    const info = await getBagInfo(row.bagCode)
    row.status = info?.status || ''
  } catch {
    // 错误文案由 request.js 统一提示；状态取不到时标签回落为「状态未知」，不阻塞本批提交
    row.status = ''
  } finally {
    row.statusLoading = false
  }
}

function handleScan(scanned) {
  addBag(typeof scanned === 'string' && scanned ? scanned : scanCode.value)
}

function handleManualAdd() {
  addBag(scanCode.value)
}

function removeRow(row) {
  pendingList.value = pendingList.value.filter((item) => item !== row)
}

async function clearAll() {
  if (!pendingList.value.length) return
  try {
    await ElMessageBox.confirm(
      `确认清空当前待确认列表？已扫描的 ${pendingList.value.length} 只袋码将被移除（尚未提交，不产生任何回收记录）。`,
      '清空确认',
      { confirmButtonText: '清空', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  pendingList.value = []
}

/** 审计计数字段容错：信封未给或非数字时按 0 显示，避免 NaN 渗进文案 */
function readAuditCount(value) {
  const num = Number(value)
  return Number.isFinite(num) ? num : 0
}

async function handleSubmit() {
  if (!stationId.value) return
  if (pendingList.value.length === 0) {
    ElMessage.warning('请先扫码添加待确认的快递袋')
    return
  }
  if (pendingList.value.length > BATCH_MAX_SIZE) {
    ElMessage.warning(`单批最多 ${BATCH_MAX_SIZE} 只，请分批提交`)
    return
  }
  submitting.value = true
  try {
    const payload = {
      bags: pendingList.value.map((row) => ({
        bagCode: row.bagCode,
        shippedBefore: !!row.shippedBefore,
      })),
    }
    const data = await recycleConfirm(stationId.value, payload)
    const { items, total, success, failed } = extractBatch(data)
    resultItems.value = items
    resultTotal.value = total
    resultFailed.value = failed
    // 设计书 7.6：回收确认按上下文记三种审计动作——跨站 / 未标记直投 / 代操作（代操作计入跨站档），
    // 计数只统计确认成功的袋；在结果弹窗里明示，让管理员提交后立刻知道本批被记成了哪种审计动作
    resultExtraSummary.value =
      `其中：跨站 ${readAuditCount(data?.crossStation)} · 未标记直投 ` +
      `${readAuditCount(data?.directWithoutMark)} · 代操作 ${readAuditCount(data?.proxy)}`
    resultVisible.value = true

    if (total === 0) {
      ElMessage.success('回收确认已提交')
    } else if (failed === 0) {
      ElMessage.success(`回收确认完成：成功 ${success} 只`)
    } else {
      ElMessage.warning(`回收确认完成：成功 ${success} 只，失败 ${failed} 只，详见结果明细`)
    }
    pendingList.value = []
    loadBin()
  } catch (error) {
    // 3001（参数/批量超限等）由 request.js 按表单场景跳过提示，这里补一条；其余错误码已统一提示
    if (error?.code === 3001) {
      ElMessage.error(error.message || '提交参数有误，请检查袋码与数量后重试')
    }
  } finally {
    submitting.value = false
  }
}

/* ------------------------------ 回收箱清单抽屉 ------------------------------ */

const drawerVisible = ref(false)
const binLoading = ref(false)
const binError = ref('')
const binList = ref([])
const binTotal = ref(0)
const binQuery = reactive({ pageNum: 1, pageSize: DEFAULT_PAGE_SIZE })

const viewportWidth = ref(window.innerWidth)
function syncViewport() {
  viewportWidth.value = window.innerWidth
}
/** 移动端抽屉占满屏宽，桌面端固定 440px */
const drawerSize = computed(() => (viewportWidth.value < 768 ? '100%' : '440px'))

async function loadBin() {
  if (!stationId.value) return
  binLoading.value = true
  binError.value = ''
  try {
    const data = await getRecycleBin(stationId.value, { ...binQuery })
    // 回收箱清单接口返回裸数组（Result<List<RecycleBinVo>>）：只认数组，不再用 records/total 猜测形态兜底
    const list = Array.isArray(data) ? data : []
    binList.value = list
    binTotal.value = list.length
  } catch (error) {
    binError.value = error?.message || '回收箱清单加载失败，请重试'
    binList.value = []
    binTotal.value = 0
  } finally {
    binLoading.value = false
  }
}

function handleBinPageChange(payload) {
  if (payload && typeof payload.page === 'number') binQuery.pageNum = payload.page
  if (payload && typeof payload.size === 'number') binQuery.pageSize = payload.size
  loadBin()
}

function openDrawer() {
  drawerVisible.value = true
  loadBin()
}

/* ------------------------------ 生命周期 ------------------------------ */

onMounted(() => {
  syncViewport()
  window.addEventListener('resize', syncViewport)
  if (stationId.value) loadBin()
})

onUnmounted(() => window.removeEventListener('resize', syncViewport))
</script>

<template>
  <div class="recycle">
    <PageCard v-if="!stationId" title="回收确认">
      <EmptyState
        icon="WarningFilled"
        description="当前账号未绑定站点，无法办理回收确认。请使用站点管理员账号登录后再操作。"
      />
    </PageCard>

    <PageCard
      v-else
      title="回收确认"
      :subtitle="`扫码逐只确认用户投回的快递袋，单批最多 ${BATCH_MAX_SIZE} 只；提交后由后端按绑定订单状态自动路由转移`"
    >
      <template #extra>
        <el-button @click="openDrawer">
          <el-icon><Box /></el-icon>
          <span>回收箱清单（{{ binTotal }}）</span>
        </el-button>
      </template>

      <!-- 顶部常驻扫码输入：扫一袋入一行 -->
      <div class="recycle__scan">
        <BagCodeInput v-model="scanCode" class="recycle__scan-input" @scan="handleScan" />
        <el-button type="primary" @click="handleManualAdd">添加</el-button>
      </div>
      <p class="recycle__scan-hint sems-text-muted">
        扫码枪回车或手动输入袋码后回车即加入下方清单；手输请核对袋码格式（形如
        <span class="sems-mono">SJB-L-2609-00001-4</span>）。
      </p>

      <!-- 空态：尚未扫描任何袋 -->
      <EmptyState
        v-if="pendingList.length === 0"
        icon="Box"
        description="暂无待确认袋，请扫描投回回收箱的快递袋"
      />

      <template v-else>
        <div class="recycle__toolbar">
          <span class="recycle__count">
            已扫 <b class="sems-num">{{ pendingList.length }}</b> 只 / 单批上限
            <b class="sems-num">{{ BATCH_MAX_SIZE }}</b> 只
          </span>
          <el-button link type="danger" @click="clearAll">清空</el-button>
        </div>

        <!-- 桌面端：表格 -->
        <div class="sems-desktop-only">
          <el-table :data="pendingList" row-key="bagCode">
            <el-table-column label="袋码" min-width="220">
              <template #default="{ row }">
                <span class="sems-mono">{{ row.bagCode }}</span>
              </template>
            </el-table-column>
            <el-table-column label="当前状态" min-width="130">
              <template #default="{ row }">
                <span v-if="row.statusLoading" class="sems-text-muted">查询中…</span>
                <StatusTag v-else-if="row.status" kind="bag" :value="row.status" size="small" />
                <StatusTag v-else kind="bag" value="" label="状态未知" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="曾寄出" min-width="150">
              <template #default="{ row }">
                <span class="recycle__shipped">
                  <el-checkbox v-model="row.shippedBefore">曾寄出</el-checkbox>
                  <el-tooltip
                    content="用户迟寄未登记时勾选：该袋按待消毒处理并计复用次数（迟寄补救）"
                    placement="top"
                  >
                    <el-icon class="recycle__tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="danger" @click="removeRow(row)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 移动端：卡片列表（<768px 不做横向滚动） -->
        <ul class="sems-mobile-only recycle__cards">
          <li v-for="row in pendingList" :key="row.bagCode" class="recycle__card">
            <div class="recycle__card-head">
              <span class="sems-mono recycle__card-code">{{ row.bagCode }}</span>
              <span v-if="row.statusLoading" class="sems-text-muted">查询中…</span>
              <StatusTag v-else-if="row.status" kind="bag" :value="row.status" size="small" />
              <StatusTag v-else kind="bag" value="" label="状态未知" size="small" />
            </div>
            <div class="recycle__card-foot">
              <span class="recycle__shipped">
                <el-checkbox v-model="row.shippedBefore">曾寄出</el-checkbox>
                <el-tooltip
                  content="用户迟寄未登记时勾选：该袋按待消毒处理并计复用次数（迟寄补救）"
                  placement="top"
                >
                  <el-icon class="recycle__tip-icon"><QuestionFilled /></el-icon>
                </el-tooltip>
              </span>
              <el-button link type="danger" @click="removeRow(row)">移除</el-button>
            </div>
          </li>
        </ul>

        <div class="recycle__submit">
          <el-button type="primary" :loading="submitting" @click="handleSubmit">
            提交回收确认（{{ pendingList.length }} 只）
          </el-button>
        </div>
      </template>
    </PageCard>

    <!-- 回收箱清单：右侧抽屉，分页展示 -->
    <el-drawer
      v-model="drawerVisible"
      title="回收箱待确认清单"
      :size="drawerSize"
      :close-on-click-modal="false"
    >
      <PageSkeleton v-if="binLoading && !binList.length" type="table" :rows="6" />

      <EmptyState v-else-if="binError" icon="WarningFilled" :description="binError">
        <el-button type="primary" @click="loadBin">重试</el-button>
      </EmptyState>

      <EmptyState
        v-else-if="!binList.length"
        icon="Box"
        description="回收箱中暂无待确认的快递袋"
      >
        <el-button @click="loadBin">刷新</el-button>
      </EmptyState>

      <template v-else>
        <el-table :data="binList" row-key="bagCode" size="small">
          <el-table-column label="袋码" min-width="190">
            <template #default="{ row }">
              <span class="sems-mono">{{ row.bagCode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="当前状态" min-width="110">
            <template #default="{ row }">
              <StatusTag kind="bag" :value="row.status" size="small" />
            </template>
          </el-table-column>
        </el-table>

        <DataPager
          v-model:page="binQuery.pageNum"
          v-model:size="binQuery.pageSize"
          :total="binTotal"
          :disabled="binLoading"
          @change="handleBinPageChange"
        />
      </template>
    </el-drawer>

    <BatchResultDialog
      v-model="resultVisible"
      :results="resultItems"
      :total="resultTotal"
      :failed="resultFailed"
      :extra-summary="resultExtraSummary"
    />
  </div>
</template>

<style scoped>
.recycle {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-4);
}

.recycle__scan {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
}
.recycle__scan-input {
  flex: 1;
  min-width: 0;
}
.recycle__scan-hint {
  margin: var(--sems-space-2) 0 var(--sems-space-4);
  line-height: 1.6;
}

.recycle__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  margin-bottom: var(--sems-space-3);
}
.recycle__count {
  color: var(--sems-text-regular);
}
.recycle__count b {
  color: var(--sems-text-primary);
}

.recycle__shipped {
  display: inline-flex;
  align-items: center;
  gap: var(--sems-space-1);
}
.recycle__tip-icon {
  color: var(--sems-text-secondary);
}

.recycle__submit {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--sems-space-4);
}

/* ---------------- 移动端卡片 ---------------- */
.recycle__cards {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}
.recycle__card {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
  padding: var(--sems-space-3);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-card);
}
.recycle__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  flex-wrap: wrap;
}
.recycle__card-code {
  font-weight: var(--sems-font-weight-medium);
  color: var(--sems-text-primary);
}
.recycle__card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
}

@media (max-width: 767px) {
  .recycle__scan {
    flex-wrap: wrap;
  }
  .recycle__submit .el-button {
    width: 100%;
    min-height: var(--sems-btn-height-touch);
  }
}
</style>
