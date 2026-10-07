<script setup>
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageCard from '@/components/PageCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import BagCodeInput from '@/components/BagCodeInput.vue'
import { repairBag, repairComplete, scrapBag } from '@/api/bag'
import { REPAIR_RESULT_META } from '@/constants'
import { BAG_CODE_REGEX } from '@/utils/validators'

/**
 * 维修 / 报废（站点端，见《系统设计说明书》4.3 / 5.4）。
 *
 * 三个功能区用 Tab 承载：
 *  ①维修登记：repairBag（破损描述必填）——袋转「维修中」；
 *  ②维修完成：repairComplete（结果=修复转「待消毒」；无法修复转「报废」）；
 *  ③直接报废：scrapBag（原因必填 + 二次确认，明确报废是审计重点核查项）。
 * 三区下方「最近操作」列表本地维护本次会话的处理结果（刷新后清空）。
 */

const activeTab = ref('repair')

/* ---------------------------------------------------------------- 公共：袋码校验与记录 */

const BAG_CODE_HINT = '袋码格式形如 SJB-L-2609-00001-4，请核对后重试'

function normalizeCode(value) {
  return (value || '').trim().toUpperCase()
}

function checkBagCode(code) {
  if (!code) {
    ElMessage.warning('请先扫码或输入袋码')
    return false
  }
  if (!BAG_CODE_REGEX.test(code)) {
    ElMessage.warning(BAG_CODE_HINT)
    return false
  }
  return true
}

/** 3001 字段级错误（request.js 不在拦截器里弹，留给表单侧展示） */
function fieldErrorText(error) {
  // 后端 3001 的 data 直接就是「字段 → 消息」映射（GlobalExceptionHandler 把 fieldErrors 作为 data），
  // 兼容带 fieldErrors 包装的形态；否则字段级明细永远取空，只剩通用文案（6.6）
  const detail = error?.data?.fieldErrors || error?.data
  if (!detail) return ''
  if (Array.isArray(detail)) {
    return detail
      .map((item) => (typeof item === 'string' ? item : item?.message))
      .filter(Boolean)
      .join('；')
  }
  if (typeof detail === 'object') return Object.values(detail).filter(Boolean).join('；')
  return typeof detail === 'string' ? detail : ''
}

/** 操作失败提示：非 3001 的错误已由 request.js 统一弹出，这里只补字段级明细 */
function notifyError(error) {
  if (error?.code === 3001) {
    ElMessage.error(fieldErrorText(error) || error.message || '请检查填写内容后重试')
  }
}

const records = ref([])

function pushRecord(record) {
  records.value.unshift({
    time: new Date().toLocaleString('zh-CN', { hour12: false }),
    ...record,
  })
  if (records.value.length > 20) records.value = records.value.slice(0, 20)
}

function goRepair() {
  activeTab.value = 'repair'
}

function clearRecords() {
  records.value = []
}

/* ---------------------------------------------------------------- ① 维修登记 */

const repairFormRef = ref()
const repairSubmitting = ref(false)
const repairForm = reactive({ bagCode: '', description: '' })
const repairRules = {
  description: [
    { required: true, message: '请填写破损描述', trigger: 'blur' },
    { max: 255, message: '破损描述不超过 255 字', trigger: 'blur' },
  ],
}

async function submitRepair() {
  const code = normalizeCode(repairForm.bagCode)
  if (!checkBagCode(code)) return
  try {
    await repairFormRef.value.validate()
  } catch {
    return
  }
  repairSubmitting.value = true
  try {
    const description = repairForm.description.trim()
    await repairBag(code, { description })
    pushRecord({ type: '维修登记', bagCode: code, success: true, message: `已登记破损描述：${description}` })
    ElMessage.success(`${code} 已转入「维修中」`)
    repairForm.bagCode = ''
    repairForm.description = ''
    repairFormRef.value?.clearValidate()
  } catch (error) {
    pushRecord({
      type: '维修登记',
      bagCode: code,
      success: false,
      message: error?.message || '登记失败',
    })
    notifyError(error)
  } finally {
    repairSubmitting.value = false
  }
}

/* ---------------------------------------------------------------- ② 维修完成 */

const completeFormRef = ref()
const completeSubmitting = ref(false)
const completeForm = reactive({ bagCode: '', result: '', remark: '' })
const completeRules = {
  result: [{ required: true, message: '请选择维修结果', trigger: 'change' }],
}

async function submitComplete() {
  const code = normalizeCode(completeForm.bagCode)
  if (!checkBagCode(code)) return
  try {
    await completeFormRef.value.validate()
  } catch {
    return
  }
  completeSubmitting.value = true
  try {
    const remark = completeForm.remark.trim()
    await repairComplete(code, { result: completeForm.result, remark: remark || undefined })
    const message = completeForm.result === '修复' ? '已修复，转「待消毒」' : '无法修复，已转「报废」'
    pushRecord({ type: '维修完成', bagCode: code, success: true, message: `${message}${remark ? `：${remark}` : ''}` })
    ElMessage.success(`${code} ${message}`)
    completeForm.bagCode = ''
    completeForm.result = ''
    completeForm.remark = ''
    completeFormRef.value?.clearValidate()
  } catch (error) {
    pushRecord({
      type: '维修完成',
      bagCode: code,
      success: false,
      message: error?.message || '提交失败',
    })
    notifyError(error)
  } finally {
    completeSubmitting.value = false
  }
}

/* ---------------------------------------------------------------- ③ 直接报废 */

const scrapFormRef = ref()
const scrapSubmitting = ref(false)
const scrapForm = reactive({ bagCode: '', reason: '' })
const scrapRules = {
  reason: [
    { required: true, message: '请填写报废原因', trigger: 'blur' },
    { max: 255, message: '报废原因不超过 255 字', trigger: 'blur' },
  ],
}

async function submitScrap() {
  const code = normalizeCode(scrapForm.bagCode)
  if (!checkBagCode(code)) return
  try {
    await scrapFormRef.value.validate()
  } catch {
    return
  }
  try {
    await ElMessageBox.confirm('报废是不可逆终态，且为审计重点核查项，确认继续？', '直接报废确认', {
      confirmButtonText: '确认报废',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  scrapSubmitting.value = true
  try {
    const reason = scrapForm.reason.trim()
    await scrapBag(code, { reason })
    pushRecord({ type: '直接报废', bagCode: code, success: true, message: `已报废：${reason}` })
    ElMessage.success(`${code} 已登记报废（终态，不可逆）`)
    scrapForm.bagCode = ''
    scrapForm.reason = ''
    scrapFormRef.value?.clearValidate()
  } catch (error) {
    pushRecord({
      type: '直接报废',
      bagCode: code,
      success: false,
      message: error?.message || '报废登记失败',
    })
    notifyError(error)
  } finally {
    scrapSubmitting.value = false
  }
}
</script>

<template>
  <div class="repair-page">
    <PageCard title="维修 / 报废作业" subtitle="单袋扫码处理，结果即时反馈">
      <el-tabs v-model="activeTab">
        <!-- ① 维修登记 -->
        <el-tab-pane label="维修登记" name="repair">
          <p class="sems-text-muted repair-page__tip">
            适用场景：待消毒或空闲的袋出现破损，登记后转「维修中」。破损描述必填，作为维修与审计依据。
          </p>
          <el-form
            ref="repairFormRef"
            :model="repairForm"
            :rules="repairRules"
            label-position="top"
            class="repair-page__form"
          >
            <el-form-item label="快递袋编码" required>
              <BagCodeInput v-model="repairForm.bagCode" />
              <div class="sems-text-muted">支持扫码枪回车、手机扫码或手输；袋码统一大写。</div>
            </el-form-item>
            <el-form-item label="破损描述" prop="description">
              <el-input
                v-model="repairForm.description"
                type="textarea"
                :rows="3"
                maxlength="255"
                show-word-limit
                placeholder="例如：外袋拉链破损、封口条断裂"
              />
            </el-form-item>
            <el-button
              type="primary"
              class="repair-page__action"
              :loading="repairSubmitting"
              @click="submitRepair"
            >
              登记维修
            </el-button>
          </el-form>
        </el-tab-pane>

        <!-- ② 维修完成 -->
        <el-tab-pane label="维修完成" name="complete">
          <p class="sems-text-muted repair-page__tip">
            适用场景：维修中的袋处置完成。结果「修复」转「待消毒」；「无法修复」转「报废」（终态）。
          </p>
          <el-form
            ref="completeFormRef"
            :model="completeForm"
            :rules="completeRules"
            label-position="top"
            class="repair-page__form"
          >
            <el-form-item label="快递袋编码" required>
              <BagCodeInput v-model="completeForm.bagCode" />
            </el-form-item>
            <el-form-item label="维修结果" prop="result">
              <el-radio-group v-model="completeForm.result">
                <el-radio-button v-for="(meta, key) in REPAIR_RESULT_META" :key="key" :value="key">
                  {{ meta.label }}
                </el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="备注" prop="remark">
              <el-input
                v-model="completeForm.remark"
                type="textarea"
                :rows="2"
                maxlength="255"
                show-word-limit
                placeholder="选填：维修过程、更换配件等"
              />
            </el-form-item>
            <el-button
              type="primary"
              class="repair-page__action"
              :loading="completeSubmitting"
              @click="submitComplete"
            >
              提交维修结果
            </el-button>
          </el-form>
        </el-tab-pane>

        <!-- ③ 直接报废 -->
        <el-tab-pane label="直接报废" name="scrap">
          <p class="sems-text-muted repair-page__tip">
            适用场景：不走维修流程的直接报废（严重破损、超限等）。报废是不可逆终态，且为审计重点核查项。
          </p>
          <el-form
            ref="scrapFormRef"
            :model="scrapForm"
            :rules="scrapRules"
            label-position="top"
            class="repair-page__form"
          >
            <el-form-item label="快递袋编码" required>
              <BagCodeInput v-model="scrapForm.bagCode" />
            </el-form-item>
            <el-form-item label="报废原因" prop="reason">
              <el-input
                v-model="scrapForm.reason"
                type="textarea"
                :rows="3"
                maxlength="255"
                show-word-limit
                placeholder="例如：袋体严重破损已无法修复"
              />
            </el-form-item>
            <el-button
              type="danger"
              class="repair-page__action"
              :loading="scrapSubmitting"
              @click="submitScrap"
            >
              直接报废
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </PageCard>

    <!-- 最近操作（本地维护） -->
    <PageCard title="最近操作" subtitle="本次会话内已处理的作业结果（本地记录，刷新后清空）">
      <template v-slot:extra>
        <el-button v-if="records.length" link type="danger" @click="clearRecords">清空记录</el-button>
      </template>

      <EmptyState v-if="!records.length" icon="Clock" description="尚未处理任何维修 / 报废作业">
        <el-button type="primary" @click="goRepair">登记第一单</el-button>
      </EmptyState>

      <ul v-else class="repair-records">
        <li v-for="(item, index) in records" :key="index" class="repair-records__item">
          <div class="repair-records__head">
            <!-- 绿色系标签必须带图标（10.5 状态双通道），色觉障碍下仍可辨红绿 -->
            <el-tag :type="item.success ? 'success' : 'danger'" size="small">
              <el-icon v-if="item.success" class="repair-records__tag-icon">
                <CircleCheckFilled />
              </el-icon>
              {{ item.success ? '成功' : '失败' }}
            </el-tag>
            <span class="repair-records__type">{{ item.type }}</span>
            <span class="sems-mono">{{ item.bagCode }}</span>
            <span class="sems-text-muted repair-records__time">{{ item.time }}</span>
          </div>
          <div class="sems-text-muted repair-records__message">{{ item.message }}</div>
        </li>
      </ul>
    </PageCard>
  </div>
</template>

<style scoped>
.repair-page {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-5);
}

.repair-page__tip {
  margin: 0 0 var(--sems-space-4);
  line-height: 1.6;
}

.repair-page__form {
  max-width: 560px;
}

.repair-page__action {
  min-width: 160px;
}

@media (max-width: 767px) {
  .repair-page__tip {
    font-size: var(--sems-font-size-sm);
  }
  .repair-page__form {
    max-width: none;
  }
  .repair-page__action {
    width: 100%;
    min-height: var(--sems-btn-height-touch);
    font-size: var(--sems-font-size-lg);
  }
}

.repair-records {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.repair-records__item {
  padding: var(--sems-space-3);
  border: 1px solid var(--sems-border);
  border-radius: var(--sems-radius-base);
}

.repair-records__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sems-space-2);
}

/* 成功标签内的对勾图标：与文字之间留 4px 间距（10.5 状态双通道） */
.repair-records__tag-icon {
  margin-right: var(--sems-space-1);
}

.repair-records__type {
  color: var(--sems-text-regular);
  font-size: var(--sems-font-size-sm);
  font-weight: var(--sems-font-weight-medium);
}

.repair-records__time {
  margin-left: auto;
}

.repair-records__message {
  margin-top: var(--sems-space-1);
  font-size: var(--sems-font-size-sm);
}
</style>
