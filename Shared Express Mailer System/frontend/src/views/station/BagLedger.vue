<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listBags, getBagQrcode, repairBag, scrapBag } from '@/api/bag'
import { BAG_STATUS_META, DEFAULT_PAGE_SIZE } from '@/constants'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import DataPager from '@/components/DataPager.vue'
import StatusTag from '@/components/StatusTag.vue'

/**
 * 站点端 · 本站袋台账（设计书 4.3 / 5.4）。
 *
 * 1) 数据范围：后端对本站站点管理员已限本站，本页**不传 stationId**（站点筛选是平台端台账的能力）；
 * 2) 筛选：状态 + 编码关键字；行点击可进袋详情（/station/bags/:bagCode）；
 * 3) 批量打印二维码：勾选多行后逐只调 getBagQrcode 取 PNG，打开新窗口按 A4 网格排版后调用浏览器打印（标签补打）；
 * 4) 转维修 / 报废：状态机 #12 / #15 只允许「空闲 / 待消毒 / 可复用」，其余状态按钮置灰；报废为审计重点核查项，二次确认。
 */

const router = useRouter()

/** 3001 为字段级校验错误，request.js 不弹提示，由页面兜底展示后端给出的原因 */
function notifyFormError(error) {
  if (error?.code !== 3001) return
  const detail = error?.data?.fieldErrors
  const text = Array.isArray(detail)
    ? detail
        .map((item) => (typeof item === 'string' ? item : item?.message))
        .filter(Boolean)
        .join('；')
    : detail && typeof detail === 'object'
      ? Object.values(detail).filter(Boolean).join('；')
      : ''
  ElMessage.error(text || error?.message || '提交内容有误，请检查后重试')
}

/* ------------------------------ 列表 ------------------------------ */

const query = reactive({ pageNum: 1, pageSize: DEFAULT_PAGE_SIZE, status: '', keyword: '' })
const list = ref([])
const total = ref(0)
const loading = ref(true)
const error = ref(false)

async function load() {
  loading.value = true
  error.value = false
  try {
    const data = await listBags({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      status: query.status || undefined,
      keyword: query.keyword.trim() || undefined,
    })
    list.value = data?.records || []
    total.value = Number(data?.total || 0)
  } catch {
    // 具体错误文案由 request.js 统一提示，这里只落到「错误态 + 重试」三态
    list.value = []
    total.value = 0
    error.value = true
  } finally {
    loading.value = false
    selectedRows.value = []
    tableRef.value?.clearSelection()
  }
}

function handleFilterChange() {
  query.pageNum = 1
  load()
}

/** 空态主操作：清空筛选条件后重新查询 */
function resetFilters() {
  query.status = ''
  query.keyword = ''
  handleFilterChange()
}

/* ------------------------------ 行能力与展示 ------------------------------ */

/** 状态机 #12 / #15：只有 空闲 / 待消毒 / 可复用 可转维修或直接报废 */
function canRepairOrScrap(row) {
  return ['IDLE', 'TO_DISINFECT'].includes(row.status)
}

/** 复用次数达到上限：行高亮并提示须转维修/报废（上架入库会被后端以 4108 拒绝） */
function isUseLimit(row) {
  const used = Number(row.useCount ?? 0)
  const max = Number(row.maxUseCount ?? 0)
  return max > 0 && used >= max
}

function rowClassName({ row }) {
  return isUseLimit(row) ? 'is-use-limit' : ''
}

function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

function goDetail(row) {
  router.push(`/station/bags/${encodeURIComponent(row.bagCode)}`)
}

/* ------------------------------ 批量打印二维码 ------------------------------ */

const tableRef = ref(null)
const selectedRows = ref([])
const printLoading = ref(false)
const printProgress = ref('')

function onSelectionChange(rows) {
  selectedRows.value = rows
}

function isSelected(row) {
  return selectedRows.value.some((item) => item.bagCode === row.bagCode)
}

/** 移动端卡片勾选（桌面端表格勾选走 selection-change） */
function toggleRow(row) {
  const index = selectedRows.value.findIndex((item) => item.bagCode === row.bagCode)
  if (index >= 0) selectedRows.value.splice(index, 1)
  else selectedRows.value.push(row)
}

function escapeHtml(value) {
  const map = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }
  return String(value ?? '').replace(/[&<>"']/g, (ch) => map[ch])
}

/** A4 网格标签排版：每张含二维码（显示尺寸 160px）、等宽袋码与规格；打印用黑白两色，不引应用内样式 */
function buildPrintHtml(items) {
  const labels = items
    .map(
      (item) => `
        <div class="label">
          <img class="label__qr" src="${item.url}" alt="${escapeHtml(item.bagCode)} 二维码" />
          <div class="label__code">${escapeHtml(item.bagCode)}</div>
          <div class="label__spec">规格：${escapeHtml(item.spec || '—')}</div>
        </div>`,
    )
    .join('')
  return `<!DOCTYPE html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <title>快递袋二维码标签</title>
    <style>
      @page { size: A4; margin: 10mm; }
      * { box-sizing: border-box; }
      body { margin: 0; background: white; color: black; font-family: "Microsoft YaHei", Arial, sans-serif; }
      h1 { margin: 0 0 4mm; font-size: 14px; font-weight: 600; }
      .sheet { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6mm; }
      .label { padding: 3mm; border: 1px dashed black; border-radius: 2mm; text-align: center; page-break-inside: avoid; break-inside: avoid; }
      .label__qr { width: 160px; height: 160px; }
      .label__code { margin-top: 2mm; font-family: Consolas, "Courier New", monospace; font-size: 13px; letter-spacing: 0.5px; }
      .label__spec { margin-top: 1mm; font-size: 12px; }
    </style>
  </head>
  <body>
    <h1>共享快递袋系统 · 快递袋二维码标签（共 ${items.length} 张）</h1>
    <div class="sheet">${labels}</div>
  </body>
</html>`
}

/** 图片就绪后再调起打印；部分浏览器在 document.close() 后不触发 load，用定时器兜底 */
function schedulePrint(win, items) {
  let started = false
  const run = () => {
    if (started) return
    started = true
    win.focus()
    win.print()
  }
  win.addEventListener('load', run)
  setTimeout(run, 1000)
  // 释放 objectURL：打印窗口关闭后回收，另有 5 分钟兜底
  const revoke = () => items.forEach((item) => URL.revokeObjectURL(item.url))
  win.addEventListener('afterprint', revoke)
  setTimeout(revoke, 5 * 60 * 1000)
}

async function handlePrint() {
  const rows = selectedRows.value.filter(Boolean)
  if (!rows.length) {
    ElMessage.warning('请先勾选需要打印二维码的快递袋')
    return
  }
  // 新窗口必须在用户点击的同步链路里打开，否则会被浏览器拦截
  const win = window.open('', '_blank')
  if (!win) {
    ElMessage.error('浏览器拦截了打印窗口，请允许本站弹出窗口后重试')
    return
  }
  printLoading.value = true
  const items = []
  const failed = []
  try {
    for (let i = 0; i < rows.length; i += 1) {
      const row = rows[i]
      printProgress.value = `获取二维码 ${i + 1}/${rows.length}`
      try {
        const res = await getBagQrcode(row.bagCode)
        const blob = res instanceof Blob ? res : res?.data
        if (!(blob instanceof Blob)) throw new Error('二维码数据异常')
        items.push({ bagCode: row.bagCode, spec: row.spec, url: URL.createObjectURL(blob) })
      } catch {
        failed.push(row.bagCode)
      }
    }
    if (!items.length) {
      win.close()
      ElMessage.error('二维码获取失败，请稍后重试')
      return
    }
    win.document.write(buildPrintHtml(items))
    win.document.close()
    schedulePrint(win, items)
    if (failed.length) {
      ElMessage.warning(`已生成 ${items.length} 张，${failed.length} 张获取失败：${failed.join('、')}`)
    } else {
      ElMessage.success(`已生成 ${items.length} 张二维码标签，请在打印窗口确认`)
    }
  } finally {
    printLoading.value = false
    printProgress.value = ''
  }
}

/* ------------------------------ 转维修 / 报废 ------------------------------ */

const repairFormRef = ref(null)
const scrapFormRef = ref(null)

const repairDialog = reactive({ visible: false, bagCode: '', description: '', submitting: false })
const scrapDialog = reactive({ visible: false, bagCode: '', reason: '', submitting: false })

const repairRules = {
  description: [
    { required: true, message: '请填写破损描述', trigger: 'blur' },
    { max: 255, message: '破损描述不超过 255 字', trigger: 'blur' },
  ],
}
const scrapRules = {
  reason: [
    { required: true, message: '请填写报废原因', trigger: 'blur' },
    { max: 255, message: '报废原因不超过 255 字', trigger: 'blur' },
  ],
}

const viewportWidth = ref(window.innerWidth)
function syncViewport() {
  viewportWidth.value = window.innerWidth
}
const dialogWidth = computed(() => (viewportWidth.value < 768 ? '92%' : '480px'))

onMounted(() => {
  syncViewport()
  window.addEventListener('resize', syncViewport)
  load()
})
onUnmounted(() => window.removeEventListener('resize', syncViewport))

function openRepair(row) {
  repairDialog.bagCode = row.bagCode
  repairDialog.description = ''
  repairDialog.visible = true
}

function openScrap(row) {
  scrapDialog.bagCode = row.bagCode
  scrapDialog.reason = ''
  scrapDialog.visible = true
}

async function submitRepair() {
  const valid = await repairFormRef.value?.validate().catch(() => false)
  if (!valid) return
  repairDialog.submitting = true
  try {
    await repairBag(repairDialog.bagCode, { description: repairDialog.description.trim() })
    ElMessage.success('已登记维修，该袋转入维修中')
    repairDialog.visible = false
    load()
  } catch (error) {
    // 其余错误文案由 request.js 统一提示，3001 字段级错误在此兜底
    notifyFormError(error)
  } finally {
    repairDialog.submitting = false
  }
}

async function submitScrap() {
  const valid = await scrapFormRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    await ElMessageBox.confirm(
      '报废为审计重点核查项：操作会写入审计日志并保留报废原因。确认对该袋执行报废？',
      '二次确认',
      { type: 'warning', confirmButtonText: '确认报废', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  scrapDialog.submitting = true
  try {
    await scrapBag(scrapDialog.bagCode, { reason: scrapDialog.reason.trim() })
    ElMessage.success('已完成报废登记')
    scrapDialog.visible = false
    load()
  } catch (error) {
    // 其余错误文案由 request.js 统一提示，3001 字段级错误在此兜底
    notifyFormError(error)
  } finally {
    scrapDialog.submitting = false
  }
}

function handleCommand(command, row) {
  if (command === 'repair') openRepair(row)
  else if (command === 'scrap') openScrap(row)
}
</script>

<template>
  <PageCard
    title="本站袋台账"
    subtitle="仅显示当前归属本站的快递袋；勾选多行后可批量打印二维码标签（换标补打）"
  >
    <template #extra>
      <div class="bag-ledger__toolbar">
        <el-input
          v-model="query.keyword"
          class="bag-ledger__keyword"
          placeholder="袋码 / 关键字"
          clearable
          @keyup.enter="handleFilterChange"
          @clear="handleFilterChange"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-select
          v-model="query.status"
          class="bag-ledger__status"
          placeholder="全部状态"
          clearable
          @change="handleFilterChange"
        >
          <el-option
            v-for="(meta, code) in BAG_STATUS_META"
            :key="code"
            :label="meta.label"
            :value="code"
          />
        </el-select>
        <el-button @click="handleFilterChange">查询</el-button>
        <el-button
          type="primary"
          :disabled="!selectedRows.length"
          :loading="printLoading"
          @click="handlePrint"
        >
          {{ printLoading ? printProgress : `打印二维码（已选 ${selectedRows.length}）` }}
        </el-button>
      </div>
    </template>

    <PageSkeleton v-if="loading" type="table" :rows="6" />

    <div v-else-if="error" class="bag-ledger__error">
      <el-icon class="bag-ledger__error-icon" :size="32"><WarningFilled /></el-icon>
      <p class="bag-ledger__error-text">袋台账加载失败，请检查网络后重试</p>
      <el-button type="primary" @click="load">重新加载</el-button>
    </div>

    <EmptyState v-else-if="!list.length" description="暂无符合条件的快递袋">
      <el-button @click="resetFilters">清空筛选并刷新</el-button>
    </EmptyState>

    <template v-else>
      <!-- 桌面端：表格 -->
      <div class="sems-desktop-only">
        <el-table
          ref="tableRef"
          :data="list"
          row-key="bagCode"
          :row-class-name="rowClassName"
          @selection-change="onSelectionChange"
        >
          <el-table-column type="selection" width="46" />
          <el-table-column label="袋码" min-width="200">
            <template #default="{ row }">
              <span class="sems-mono">{{ row.bagCode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="规格" min-width="90">
            <template #default="{ row }">{{ row.spec || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" min-width="120">
            <template #default="{ row }">
              <StatusTag kind="bag" :value="row.status" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="复用次数 / 上限" min-width="180">
            <template #default="{ row }">
              <span class="sems-num">{{ row.useCount ?? 0 }} / {{ row.maxUseCount ?? '—' }}</span>
              <el-tag
                v-if="isUseLimit(row)"
                class="bag-ledger__limit-tag"
                type="warning"
                size="small"
                effect="plain"
              >
                须转维修/报废
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="所在站点" min-width="140">
            <template #default="{ row }">{{ row.stationName || row.stationId || '—' }}</template>
          </el-table-column>
          <el-table-column label="更新时间" min-width="150">
            <template #default="{ row }">
              <span class="sems-num">{{ formatTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link @click="goDetail(row)">详情</el-button>
              <el-dropdown trigger="click" @command="(command) => handleCommand(command, row)">
                <el-button type="primary" link>
                  更多<el-icon class="bag-ledger__more-icon"><ArrowDown /></el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-if="!canRepairOrScrap(row)" disabled>
                      当前状态不可转维修/报废
                    </el-dropdown-item>
                    <el-dropdown-item command="repair" :disabled="!canRepairOrScrap(row)">
                      转维修
                    </el-dropdown-item>
                    <el-dropdown-item command="scrap" :disabled="!canRepairOrScrap(row)">
                      报废
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 移动端：卡片列表（<768px 不做横向滚动） -->
      <div class="sems-mobile-only bag-ledger__cards">
        <div v-for="row in list" :key="row.bagCode" class="bag-ledger__card">
          <div class="bag-ledger__card-head">
            <el-checkbox :model-value="isSelected(row)" @change="toggleRow(row)" />
            <span class="sems-mono bag-ledger__card-code">{{ row.bagCode }}</span>
            <StatusTag kind="bag" :value="row.status" size="small" />
          </div>
          <div class="bag-ledger__card-line">
            规格：{{ row.spec || '—' }} · 复用：{{ row.useCount ?? 0 }} / {{ row.maxUseCount ?? '—' }}
          </div>
          <div class="bag-ledger__card-line">
            所在站点：{{ row.stationName || row.stationId || '—' }}
          </div>
          <div class="bag-ledger__card-line">更新时间：{{ formatTime(row.updatedAt) }}</div>
          <el-tag v-if="isUseLimit(row)" type="warning" size="small" effect="plain">
            复用次数已达上限，须转维修/报废
          </el-tag>
          <div class="bag-ledger__card-actions">
            <el-button size="small" @click="goDetail(row)">详情</el-button>
            <el-button size="small" :disabled="!canRepairOrScrap(row)" @click="openRepair(row)">
              转维修
            </el-button>
            <el-button
              size="small"
              type="danger"
              plain
              :disabled="!canRepairOrScrap(row)"
              @click="openScrap(row)"
            >
              报废
            </el-button>
          </div>
        </div>
      </div>

      <DataPager
        v-model:page="query.pageNum"
        v-model:size="query.pageSize"
        :total="total"
        @change="load"
      />
    </template>
  </PageCard>

  <!-- 转维修 -->
  <el-dialog
    v-model="repairDialog.visible"
    title="转维修"
    :width="dialogWidth"
    :close-on-click-modal="false"
  >
    <p class="sems-text-muted bag-ledger__dialog-bag">
      袋码：<span class="sems-mono">{{ repairDialog.bagCode }}</span>
    </p>
    <el-form
      ref="repairFormRef"
      :model="repairDialog"
      :rules="repairRules"
      label-position="top"
    >
      <el-form-item label="破损描述" prop="description">
        <el-input
          v-model="repairDialog.description"
          type="textarea"
          :rows="3"
          maxlength="255"
          show-word-limit
          placeholder="请描述破损情况，如：封口拉链断裂、袋体划伤"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="repairDialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="repairDialog.submitting" @click="submitRepair">
        确认转维修
      </el-button>
    </template>
  </el-dialog>

  <!-- 报废：二次确认并标注审计重点核查 -->
  <el-dialog
    v-model="scrapDialog.visible"
    title="报废"
    :width="dialogWidth"
    :close-on-click-modal="false"
  >
    <p class="sems-text-muted bag-ledger__dialog-bag">
      袋码：<span class="sems-mono">{{ scrapDialog.bagCode }}</span>
    </p>
    <el-form ref="scrapFormRef" :model="scrapDialog" :rules="scrapRules" label-position="top">
      <el-form-item label="报废原因" prop="reason">
        <el-input
          v-model="scrapDialog.reason"
          type="textarea"
          :rows="3"
          maxlength="255"
          show-word-limit
          placeholder="如：严重破损无法修复 / 复用次数已达上限"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="scrapDialog.visible = false">取消</el-button>
      <el-button type="danger" :loading="scrapDialog.submitting" @click="submitScrap">
        提交报废
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.bag-ledger__toolbar {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  flex-wrap: wrap;
  justify-content: flex-end;
}
.bag-ledger__keyword {
  width: 200px;
}
.bag-ledger__status {
  width: 150px;
}

.bag-ledger__limit-tag {
  margin-left: var(--sems-space-2);
}
.bag-ledger__more-icon {
  margin-left: var(--sems-space-1);
}

.bag-ledger__error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sems-space-3);
  padding: var(--sems-space-6) 0;
  color: var(--sems-text-secondary);
}
.bag-ledger__error-icon {
  color: var(--sems-color-warning);
}
.bag-ledger__error-text {
  margin: 0;
}

/* 复用次数达到上限：行高亮（提示文案由行内标签给出，不靠颜色单独承载语义） */
:deep(.el-table__body tr.is-use-limit > td.el-table__cell) {
  background-color: var(--sems-color-warning-bg);
}
:deep(.el-table__body tr.is-use-limit:hover > td.el-table__cell) {
  background-color: var(--sems-color-warning-bg);
}

/* ---------------- 移动端卡片 ---------------- */
.bag-ledger__cards {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-3);
}
.bag-ledger__card {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
  padding: var(--sems-space-4);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-card);
  background-color: var(--sems-bg-card);
}
.bag-ledger__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  flex-wrap: wrap;
}
.bag-ledger__card-code {
  flex: 1;
  font-weight: var(--sems-font-weight-medium);
  color: var(--sems-text-primary);
}
.bag-ledger__card-line {
  color: var(--sems-text-regular);
}
.bag-ledger__card-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--sems-space-2);
  margin-top: var(--sems-space-1);
}

.bag-ledger__dialog-bag {
  margin: 0 0 var(--sems-space-3);
}
</style>
