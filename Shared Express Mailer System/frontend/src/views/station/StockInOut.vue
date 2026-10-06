<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { activateBag, stockIn, stockOut } from '@/api/bag'
import { BATCH_MAX_SIZE } from '@/constants'
import { extractBatch } from '@/utils/batch'
import { BAG_CODE_REGEX, toUpperCase } from '@/utils/validators'
import PageCard from '@/components/PageCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import BagCodeInput from '@/components/BagCodeInput.vue'
import BatchResultDialog from '@/components/BatchResultDialog.vue'

/**
 * 站点端 · 出入库 / 激活上架（设计书 4.3 / 5.4）。
 *
 * ① 激活上架：单只，未激活 → 空闲（转移 1），调 activateBag(bagCode)；
 * ② 上架入库：可复用 → 空闲（转移 11），扫码累加后批量调 stockIn，
 *    复用次数已达上限的袋后端报 4108，结果里必须明确提示「请转维修/报废」；
 * ③ 出库：仅用于报废袋实物出库与经批准的盘点差异出库，扫码累加 + 必填备注后批量调 stockOut。
 *
 * 三个入口的批量上限均为 BATCH_MAX_SIZE（500 只/批），超限前端先拦一道，后端 3001 才是权威。
 *
 * 三态说明：本页是操作型页面，首屏不拉取服务端数据，因此没有骨架屏——
 * 「加载态」落在提交按钮的 loading、「空态」落在各清单的 EmptyState、
 * 「错误态」落在批量结果明细（逐袋失败原因）与顶部轻提示上。
 */

const router = useRouter()

const activeTab = ref('activate')

/* ------------------------------ 逐袋结果 ------------------------------ */

const resultVisible = ref(false)
/** 逐袋结果明细（后端统一信封 data.items，经 utils/batch.js 解析） */
const resultItems = ref([])
/** 批次计数：来自后端信封（items 可能被后端截断，计数以后端为准） */
const resultTotal = ref(0)
const resultFailed = ref(0)

/* ------------------------------ ① 激活上架（单只） ------------------------------ */

const activateCode = ref('')
const activating = ref(false)

async function handleActivate(scanned) {
  const code = toUpperCase(typeof scanned === 'string' && scanned ? scanned : activateCode.value)
  if (!code) {
    ElMessage.warning('请输入或扫描袋码')
    return
  }
  if (!BAG_CODE_REGEX.test(code)) {
    ElMessage.warning('袋码格式不正确（形如 SJB-L-2609-00001-4），请核对后重试')
    return
  }
  if (activating.value) return
  activating.value = true
  try {
    await activateBag(code)
    ElMessage.success(`袋 ${code} 已激活上架（未激活 → 空闲）`)
    activateCode.value = ''
  } catch (error) {
    // 3001 由 request.js 按表单场景跳过提示，这里补一条；其余错误码（4102/4106/4107 等）已统一提示
    if (error?.code === 3001) {
      ElMessage.error(error.message || '参数有误，请检查袋码格式后重试')
    }
  } finally {
    activating.value = false
  }
}

/* ------------------------------ 扫码累加清单（②③ 共用） ------------------------------ */

const stockInCode = ref('')
const stockInList = ref([])
const stockInSubmitting = ref(false)
const stockInExceedCount = ref(0)

const stockOutCode = ref('')
const stockOutList = ref([])
const stockOutRemark = ref('')
const stockOutSubmitting = ref(false)

function appendBag(listRef, codeRef, raw, listName) {
  const code = toUpperCase(typeof raw === 'string' && raw ? raw : codeRef.value)
  if (!code) {
    ElMessage.warning('请输入或扫描袋码')
    return
  }
  if (!BAG_CODE_REGEX.test(code)) {
    ElMessage.warning('袋码格式不正确（形如 SJB-L-2609-00001-4），请核对后重试')
    return
  }
  if (listRef.value.some((item) => item.bagCode === code)) {
    ElMessage.warning(`袋码 ${code} 已在${listName}中`)
    codeRef.value = ''
    return
  }
  if (listRef.value.length >= BATCH_MAX_SIZE) {
    ElMessage.warning(`单批最多 ${BATCH_MAX_SIZE} 只，请先提交当前批次`)
    return
  }
  listRef.value.push({ bagCode: code })
  codeRef.value = ''
}

function removeBag(listRef, bagCode) {
  listRef.value = listRef.value.filter((item) => item.bagCode !== bagCode)
}

/* ------------------------------ ② 上架入库 ------------------------------ */

function handleStockInScan(scanned) {
  appendBag(stockInList, stockInCode, scanned, '入库清单')
}

async function handleStockIn() {
  if (stockInList.value.length === 0) {
    ElMessage.warning('请先扫码添加入库袋')
    return
  }
  if (stockInList.value.length > BATCH_MAX_SIZE) {
    ElMessage.warning(`单批最多 ${BATCH_MAX_SIZE} 只，请分批提交`)
    return
  }
  stockInSubmitting.value = true
  try {
    const data = await stockIn({ bagCodes: stockInList.value.map((item) => item.bagCode) })
    const { items, total, success, failed } = extractBatch(data)
    // 4108＝复用次数已达上限：结果里给出明确处置指引，不能只留一个错误码（设计书 5.4）
    for (const row of items) {
      if (row && Number(row.code) === 4108) {
        row.message = row.message
          ? `${row.message}，请转维修/报废`
          : '复用次数已达上限，请转维修/报废'
      }
    }
    stockInExceedCount.value = items.filter((row) => Number(row.code) === 4108).length
    resultItems.value = items
    resultTotal.value = total
    resultFailed.value = failed
    resultVisible.value = true

    if (total === 0) {
      ElMessage.success('上架入库已提交')
    } else if (failed === 0) {
      ElMessage.success(`上架入库完成：成功 ${success} 只`)
    } else {
      ElMessage.warning(`上架入库完成：成功 ${success} 只，失败 ${failed} 只，详见结果明细`)
    }
    stockInList.value = []
  } catch (error) {
    if (error?.code === 3001) {
      ElMessage.error(error.message || '提交参数有误，请检查袋码与数量后重试')
    }
  } finally {
    stockInSubmitting.value = false
  }
}

/* ------------------------------ ③ 出库 ------------------------------ */

function handleStockOutScan(scanned) {
  appendBag(stockOutList, stockOutCode, scanned, '出库清单')
}

async function handleStockOut() {
  if (stockOutList.value.length === 0) {
    ElMessage.warning('请先扫码添加出库袋')
    return
  }
  if (stockOutList.value.length > BATCH_MAX_SIZE) {
    ElMessage.warning(`单批最多 ${BATCH_MAX_SIZE} 只，请分批提交`)
    return
  }
  const remark = (stockOutRemark.value || '').trim()
  if (!remark) {
    ElMessage.warning('请填写出库备注（盘点差异单号或报废说明）')
    return
  }
  stockOutSubmitting.value = true
  try {
    const data = await stockOut({
      bagCodes: stockOutList.value.map((item) => item.bagCode),
      remark,
    })
    const { items, total, success, failed } = extractBatch(data)
    resultItems.value = items
    resultTotal.value = total
    resultFailed.value = failed
    resultVisible.value = true

    if (total === 0) {
      ElMessage.success('出库已提交')
    } else if (failed === 0) {
      ElMessage.success(`出库完成：成功 ${success} 只`)
    } else {
      ElMessage.warning(`出库完成：成功 ${success} 只，失败 ${failed} 只，详见结果明细`)
    }
    stockOutList.value = []
    stockOutRemark.value = ''
  } catch (error) {
    if (error?.code === 3001) {
      ElMessage.error(error.message || '提交参数有误，请检查袋码、数量与备注后重试')
    }
  } finally {
    stockOutSubmitting.value = false
  }
}

/* ------------------------------ 其他 ------------------------------ */

const stockInHint = computed(
  () => `已扫 ${stockInList.value.length} 只 / 单批上限 ${BATCH_MAX_SIZE} 只`,
)
const stockOutHint = computed(
  () => `已扫 ${stockOutList.value.length} 只 / 单批上限 ${BATCH_MAX_SIZE} 只`,
)

function goRepair() {
  router.push('/station/repair')
}
</script>

<template>
  <div class="stock">
    <PageCard title="出入库 / 激活上架" subtitle="激活上架（单只）、上架入库与出库（批量，单批不超过 500 只）">
      <el-tabs v-model="activeTab">
        <!-- ① 激活上架：未激活 → 空闲（转移 1），单只 -->
        <el-tab-pane label="激活上架" name="activate">
          <p class="stock__note sems-text-muted">
            用于新袋首次上架：未激活 → 空闲，单只办理，按袋的登记站点校验归属（批量入口单批上限 500 只）。
          </p>
          <div class="stock__scan">
            <BagCodeInput v-model="activateCode" class="stock__scan-input" @scan="handleActivate" />
            <el-button type="primary" :loading="activating" @click="handleActivate">
              激活上架
            </el-button>
          </div>
        </el-tab-pane>

        <!-- ② 上架入库：可复用 → 空闲（转移 11），批量 -->
        <el-tab-pane label="上架入库" name="stock-in">
          <el-alert
            class="stock__alert"
            type="info"
            show-icon
            :closable="false"
            title="可复用袋上架回空闲（转移 11）。复用次数已达上限的袋会被拒绝（4108），须转维修/报废处理。"
          />

          <div class="stock__scan">
            <BagCodeInput v-model="stockInCode" class="stock__scan-input" @scan="handleStockInScan" />
            <el-button @click="handleStockInScan(stockInCode)">添加</el-button>
          </div>

          <p class="stock__count sems-text-muted">{{ stockInHint }}</p>

          <EmptyState
            v-if="stockInList.length === 0"
            icon="Box"
            description="尚未录入袋码，扫码或手输后回车加入入库清单"
          />

          <template v-else>
            <ul class="stock__list">
              <li v-for="item in stockInList" :key="item.bagCode" class="stock__list-item">
                <span class="sems-mono">{{ item.bagCode }}</span>
                <el-button link type="danger" @click="removeBag(stockInList, item.bagCode)">
                  移除
                </el-button>
              </li>
            </ul>
            <div class="stock__actions">
              <el-button :disabled="stockInSubmitting" @click="stockInList = []">清空</el-button>
              <el-button type="primary" :loading="stockInSubmitting" @click="handleStockIn">
                提交上架入库（{{ stockInList.length }} 只）
              </el-button>
            </div>
          </template>

          <el-alert
            v-if="stockInExceedCount > 0"
            class="stock__alert"
            type="warning"
            show-icon
            title="上架入库结果提示"
            @close="stockInExceedCount = 0"
          >
            <template #default>
              有 {{ stockInExceedCount }} 只袋复用次数已达上限未能入库，请转维修/报废处理。
              <el-button link type="primary" @click="goRepair">前往维修报废</el-button>
            </template>
          </el-alert>
        </el-tab-pane>

        <!-- ③ 出库：报废袋实物出库 / 经批准的盘点差异出库，批量 -->
        <el-tab-pane label="出库" name="stock-out">
          <el-alert
            class="stock__alert"
            type="warning"
            show-icon
            :closable="false"
            title="出库仅用于报废袋实物出库与经批准的盘点差异出库；维修请走「维修报废」。袋状态不允许出库时将返回失败明细（4105）。"
          />

          <div class="stock__scan">
            <BagCodeInput v-model="stockOutCode" class="stock__scan-input" @scan="handleStockOutScan" />
            <el-button @click="handleStockOutScan(stockOutCode)">添加</el-button>
          </div>

          <p class="stock__count sems-text-muted">{{ stockOutHint }}</p>

          <EmptyState
            v-if="stockOutList.length === 0"
            icon="Box"
            description="尚未录入袋码，扫码或手输后回车加入出库清单"
          />

          <template v-else>
            <ul class="stock__list">
              <li v-for="item in stockOutList" :key="item.bagCode" class="stock__list-item">
                <span class="sems-mono">{{ item.bagCode }}</span>
                <el-button link type="danger" @click="removeBag(stockOutList, item.bagCode)">
                  移除
                </el-button>
              </li>
            </ul>
          </template>

          <div class="stock__field">
            <p class="stock__label">出库备注（必填）</p>
            <el-input
              v-model="stockOutRemark"
              type="textarea"
              :rows="2"
              maxlength="200"
              show-word-limit
              placeholder="填写盘点差异单号或报废说明，将写入出入库流水备注"
            />
          </div>

          <div class="stock__actions">
            <el-button :disabled="stockOutSubmitting" @click="stockOutList = []">清空</el-button>
            <el-button type="primary" :loading="stockOutSubmitting" @click="handleStockOut">
              提交出库（{{ stockOutList.length }} 只）
            </el-button>
          </div>
        </el-tab-pane>
      </el-tabs>
    </PageCard>

    <BatchResultDialog
      v-model="resultVisible"
      :results="resultItems"
      :total="resultTotal"
      :failed="resultFailed"
    />
  </div>
</template>

<style scoped>
.stock {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-4);
}

.stock__note {
  margin: 0 0 var(--sems-space-4);
  line-height: 1.6;
}

.stock__alert {
  margin-bottom: var(--sems-space-4);
}

.stock__scan {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
}
.stock__scan-input {
  flex: 1;
  min-width: 0;
}

.stock__count {
  margin: var(--sems-space-2) 0 var(--sems-space-3);
}

.stock__list {
  display: flex;
  flex-direction: column;
  max-height: 320px;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-base);
  list-style: none;
}
.stock__list-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sems-space-2);
  min-height: var(--sems-row-height);
  padding: 0 var(--sems-space-3);
  border-bottom: 1px solid var(--sems-border);
}
.stock__list-item:last-child {
  border-bottom: none;
}

.stock__field {
  margin-top: var(--sems-space-4);
}
.stock__label {
  margin: 0 0 var(--sems-space-2);
  color: var(--sems-text-primary);
  font-weight: var(--sems-font-weight-medium);
}

.stock__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--sems-space-2);
  margin-top: var(--sems-space-4);
}

@media (max-width: 767px) {
  .stock__scan {
    flex-wrap: wrap;
  }
  .stock__actions {
    flex-direction: column-reverse;
  }
  .stock__actions .el-button {
    width: 100%;
    margin-left: 0;
    min-height: var(--sems-btn-height-touch);
  }
}
</style>
