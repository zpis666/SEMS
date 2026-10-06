<script setup>
/**
 * 用户端 · 填写寄件信息（《系统设计说明书》4.2 / 5.2）。
 *
 * - 表单：快递公司（常见公司下拉 + 允许输入其他）、运单号（8–20 位字母数字）、收件人手机号；
 * - 收件人手机号仅保存掩码与不可逆哈希，用于留存证据（设计书 8.2 / 9.5）；
 * - 订单非「待寄件」时后端报 4105：提示后返回订单详情查看最新状态；
 * - 3001 的字段级明细在 error.data，回填到对应表单项的 error 提示上。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
import PageCard from '@/components/PageCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import { getOrder, submitShipping } from '@/api/order'
import { ORDER_STATUS, ORDER_STATUS_META } from '@/constants'
import { recipientPhoneRules, toUpperCase, trackingNoRules } from '@/utils/validators'

const route = useRoute()
const router = useRouter()

const orderNo = computed(() => String(route.params.orderNo || ''))
const order = ref(null)
const loading = ref(true)
const loadError = ref('')
const forbidden = ref(false)

/** 常见快递公司（allow-create 允许输入列表外的其他公司） */
const EXPRESS_COMPANIES = [
  '顺丰速运',
  '中通快递',
  '圆通速递',
  '韵达速递',
  '申通快递',
  '极兔速递',
  '京东物流',
  '邮政EMS',
]

async function load() {
  loading.value = true
  loadError.value = ''
  forbidden.value = false
  try {
    order.value = await getOrder(orderNo.value)
  } catch (error) {
    order.value = null
    if (error?.code === 2002) {
      forbidden.value = true
    } else {
      loadError.value = error?.message || '订单加载失败，请重试'
    }
  } finally {
    loading.value = false
  }
}

const statusLabel = computed(() => ORDER_STATUS_META[order.value?.status]?.label || '—')
const isPendingSend = computed(() => order.value?.status === ORDER_STATUS.PENDING_SEND)

/* ------------------------------ 表单 ------------------------------ */

const formRef = ref(null)
const form = reactive({ expressCompany: '', trackingNo: '', recipientPhone: '' })
const rules = {
  expressCompany: [{ required: true, message: '请选择或输入快递公司', trigger: 'change' }],
  trackingNo: trackingNoRules,
  recipientPhone: recipientPhoneRules,
}
const submitting = ref(false)
/** 3001 返回的字段级错误（字段名 → 文案），回填到表单项 error 上 */
const serverErrors = ref({})

function pickFieldError(field) {
  const value = serverErrors.value?.[field]
  if (!value) return ''
  return Array.isArray(value) ? value.join('；') : String(value)
}

/**
 * 3001 字段级错误（error.data.fieldErrors）回填；兼容 data 直接就是「字段 → 消息」映射的写法。
 */
function applyServerFieldErrors(data) {
  const map = data?.fieldErrors || data
  if (!map || typeof map !== 'object') return
  const next = {}
  for (const [key, value] of Object.entries(map)) {
    if (!value) continue
    next[key] = Array.isArray(value) ? value.join('；') : String(value)
  }
  serverErrors.value = next
}

/** 运单号统一大写：扫码枪与手输都可能带小写，避免后端联合唯一键因大小写不一致误判 */
function onTrackingInput(value) {
  form.trackingNo = toUpperCase(value)
}

// 用户一旦改动表单，就清掉上一次的字段级服务端错误
watch(form, () => {
  if (Object.keys(serverErrors.value).length) serverErrors.value = {}
})

async function submit() {
  if (!formRef.value || submitting.value) return
  try {
    await formRef.value.validate()
  } catch {
    return // 本地校验未通过，提示由表单自身展示
  }
  submitting.value = true
  serverErrors.value = {}
  try {
    await submitShipping(orderNo.value, {
      expressCompany: form.expressCompany,
      trackingNo: form.trackingNo,
      recipientPhone: form.recipientPhone,
    })
    ElMessage.success('寄件信息已提交，请按期将袋投回站点回收箱')
    router.replace({ name: 'OrderDetail', params: { orderNo: orderNo.value } })
  } catch (error) {
    if (error?.code === 4105) {
      // 订单已被撤回 / 已回收等：提示后回订单详情看最新状态
      ElMessage.warning('该订单已不是待寄件状态，无法提交寄件信息，已返回订单详情')
      router.replace({ name: 'OrderDetail', params: { orderNo: orderNo.value } })
    } else if (error?.code === 3001) {
      applyServerFieldErrors(error.data)
    }
    // 其余错误码的提示已由 request.js 统一弹出
  } finally {
    submitting.value = false
  }
}

function goDetail() {
  router.replace({ name: 'OrderDetail', params: { orderNo: orderNo.value } })
}

onMounted(load)
</script>

<template>
  <div class="shipping-form">
    <PageSkeleton v-if="loading" :rows="4" type="card" />

    <EmptyState v-else-if="forbidden" icon="Lock" description="无权查看该订单">
      <el-button type="primary" @click="router.replace({ name: 'MyOrders' })">返回我的订单</el-button>
    </EmptyState>

    <EmptyState v-else-if="loadError" icon="Warning" :description="loadError">
      <el-button type="primary" @click="load()">重试</el-button>
    </EmptyState>

    <template v-else-if="order">
      <PageCard title="订单信息">
        <div class="info-row">
          <span class="info-label">订单号</span>
          <span class="info-value sems-mono">{{ order.orderNo }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">袋码</span>
          <span class="info-value sems-mono">{{ order.bagCode }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">当前状态</span>
          <span class="info-value">
            <StatusTag kind="order" :value="order.status" size="small" />
          </span>
        </div>
      </PageCard>

      <PageCard
        v-if="isPendingSend"
        title="填写寄件信息"
        subtitle="提交后订单进入「使用中」，请按期将袋投回任一站点回收箱"
      >
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent
        >
          <el-form-item
            label="快递公司"
            prop="expressCompany"
            :error="pickFieldError('expressCompany')"
          >
            <el-select
              v-model="form.expressCompany"
              class="form-control"
              placeholder="请选择或输入快递公司"
              filterable
              allow-create
              default-first-option
            >
              <el-option
                v-for="company in EXPRESS_COMPANIES"
                :key="company"
                :label="company"
                :value="company"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="运单号" prop="trackingNo" :error="pickFieldError('trackingNo')">
            <el-input
              v-model="form.trackingNo"
              placeholder="8–20 位字母或数字"
              maxlength="20"
              clearable
              @input="onTrackingInput"
            />
          </el-form-item>

          <el-form-item
            label="收件人手机号"
            prop="recipientPhone"
            :error="pickFieldError('recipientPhone')"
          >
            <el-input
              v-model="form.recipientPhone"
              placeholder="请输入 11 位手机号"
              maxlength="11"
              clearable
            />
          </el-form-item>

          <p class="form-tip sems-text-muted">
            收件人手机号仅保存掩码与不可逆哈希，用于留存证据（系统不存储明文）。
          </p>

          <el-button type="primary" class="submit-btn" :loading="submitting" @click="submit">
            提交寄件信息
          </el-button>
        </el-form>
      </PageCard>

      <PageCard v-else>
        <EmptyState
          icon="InfoFilled"
          :description="`该订单当前状态为「${statusLabel}」，仅待寄件订单可提交寄件信息`"
        >
          <el-button type="primary" @click="goDetail">返回订单详情</el-button>
        </EmptyState>
      </PageCard>
    </template>
  </div>
</template>

<style scoped>
.shipping-form {
  display: flex;
  flex-direction: column;
  gap: var(--sems-space-4);
}

.info-row {
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  padding: var(--sems-space-2) 0;
  border-bottom: 1px solid var(--sems-border);
}

.info-row:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.info-label {
  flex: none;
  width: 84px;
  color: var(--sems-text-secondary);
  font-size: var(--sems-font-size-sm);
}

.info-value {
  flex: 1;
  display: flex;
  align-items: center;
  gap: var(--sems-space-2);
  color: var(--sems-text-primary);
  word-break: break-all;
}

.form-control {
  width: 100%;
}

.form-tip {
  margin: 0 0 var(--sems-space-4);
  line-height: 1.5;
}

/* 移动端主操作按钮：48px 高（设计书 10.5） */
.submit-btn {
  width: 100%;
  height: var(--sems-btn-height-touch);
}
</style>
