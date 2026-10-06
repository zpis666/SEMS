import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * Axios 统一封装（见《系统设计说明书》10.1 与 6.6）。
 *
 * 职责：
 *  1) 注入 Authorization 与 clientRequestId（写操作幂等头，见 9.6）；
 *  2) 把统一响应体 { code, message, data } 剥成直接可用的 data；
 *  3) 按 6.6 的前端提示约定分流错误：401 跳登录并带回跳路由、3001 给字段级明细、
 *     5102/5103 自动指数退避重试（0.5/1/2/4s，累计 ≥5s）后仍失败才提示。
 *
 * 注意：baseURL 用相对路径 /api/v1。开发期由 vite 代理到 8090，生产期由 Nginx 反代 8080，
 * 业务代码中不出现任何后端地址（见设计书 9.4：生产同域部署、CORS 默认关闭）。
 */

const BASE_URL = '/api/v1'

/** 可重试错误码：库存计数未就绪 / 对账中（设计书 7.2 的退避口径） */
const RETRYABLE_CODES = [5102, 5103]
/** 退避间隔（毫秒），与设计书 7.2「0.5/1/2/4s 重试、累计 ≥5s」一致 */
const RETRY_DELAYS = [500, 1000, 2000, 4000]

/** token 以内存为主、sessionStorage 兜底（标签页级，关闭即清除，见设计书 10.3） */
let memoryToken = sessionStorage.getItem('sems_token') || ''

export function getToken() {
  return memoryToken
}

export function setToken(token) {
  memoryToken = token || ''
  if (memoryToken) {
    sessionStorage.setItem('sems_token', memoryToken)
  } else {
    sessionStorage.removeItem('sems_token')
  }
}

/** 生成符合 UUID v4 格式的幂等 ID（后端按 Constants.UUID_REGEX 校验格式，格式错会返回 3001） */
export function newClientRequestId() {
  if (window.crypto?.randomUUID) {
    return window.crypto.randomUUID()
  }
  // 老浏览器兜底：crypto.getRandomValues 拼 v4
  const bytes = new Uint8Array(16)
  window.crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0'))
  return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex
    .slice(6, 8)
    .join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10, 16).join('')}`
}

const service = axios.create({
  baseURL: BASE_URL,
  timeout: 20000,
  headers: { 'Content-Type': 'application/json;charset=UTF-8' },
})

/** 401 只跳一次登录，避免并发请求各自弹一遍 */
let redirecting = false

function redirectToLogin() {
  if (redirecting) return
  redirecting = true
  setToken('')
  const current = window.location.hash.replace(/^#/, '') || '/'
  const target = current.startsWith('/login')
    ? '/login'
    : `/login?redirect=${encodeURIComponent(current)}`
  window.location.hash = target
  setTimeout(() => {
    redirecting = false
  }, 1000)
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * 发起一次请求，命中可重试错误码时按退避表重试。
 * 关键：重试必须复用同一个 clientRequestId——否则服务端会当成新请求，
 * 幂等（设计书 9.6）就形同虚设。因此幂等 ID 在进入重试循环前只生成一次，
 * 每次尝试都沿用同一个值；请求拦截器只补齐、不覆盖已有值。
 */
async function requestWithRetry(config) {
  const method = (config.method || 'get').toUpperCase()
  // 写方法且已登录且未显式豁免才需要幂等头（匿名写接口豁免，见 9.6）
  const clientRequestId =
    ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method) &&
    getToken() &&
    !config.skipIdempotent
      ? newClientRequestId()
      : ''
  if (clientRequestId) {
    config.headers = { ...(config.headers || {}), clientRequestId }
  }
  let attempt = 0
  for (;;) {
    try {
      const response = await service.request(config)
      const body = response.data
      if (body && typeof body.code === 'number' && body.code !== 0) {
        if (RETRYABLE_CODES.includes(body.code) && attempt < RETRY_DELAYS.length) {
          await sleep(RETRY_DELAYS[attempt])
          attempt += 1
          continue
        }
      }
      return response
    } catch (error) {
      // HTTP 503（5102/5103 走 503 + Retry-After）同样按退避重试
      const status = error.response?.status
      const code = error.response?.data?.code
      const retryable =
        (status === 503 && (code === undefined || RETRYABLE_CODES.includes(code))) ||
        RETRYABLE_CODES.includes(code)
      if (retryable && attempt < RETRY_DELAYS.length) {
        await sleep(RETRY_DELAYS[attempt])
        attempt += 1
        continue
      }
      throw error
    }
  }
}

/**
 * 读取请求头当前值：AxiosHeaders 会把头名归一化为小写，普通对象则原样保留，
 * 两种形态都要兼容（用于判断幂等头是否已由 requestWithRetry 生成）。
 */
function headerOf(headers, name) {
  if (!headers) return ''
  if (typeof headers.get === 'function') return headers.get(name) || ''
  return headers[name] || ''
}

service.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  const method = (config.method || 'get').toUpperCase()
  // 写操作幂等头：登录后全部写接口启用；匿名写接口（注册/登录/验证码）不启用（见设计书 9.6），
  // 由调用方传 skipIdempotent: true 显式豁免——不要用空字符串表达「不启用」，那会被下面的判断当成缺省。
  // 已有值时不得覆盖：requestWithRetry 生成的值要贯穿整条重试链（9.6 幂等键复用），
  // 若每次重试换新 UUID，服务端会把重放当成新请求（重复建单/重复扣库存）。
  if (
    ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method) &&
    token &&
    !config.skipIdempotent &&
    !headerOf(config.headers, 'clientRequestId')
  ) {
    config.headers.clientRequestId = newClientRequestId()
  }
  return config
})

service.interceptors.response.use(
  (response) => {
    // 二进制流（batch-generate 的二维码 ZIP 下载，设计书 6.2 的唯一例外）原样返回
    if (response.config?.responseType === 'blob') {
      return response
    }
    const body = response.data
    if (!body || typeof body.code !== 'number') {
      return body
    }
    if (body.code === 0) {
      return body.data
    }
    // 非 0：业务错误码在响应体里（HTTP 多为 200，见 6.6 映射表）
    const error = new Error(body.message || '请求失败')
    error.code = body.code
    error.data = body.data
    handleBizError(error)
    return Promise.reject(error)
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data
    if (body && typeof body.code === 'number' && body.code !== 0) {
      const bizError = new Error(body.message || '请求失败')
      bizError.code = body.code
      bizError.data = body.data
      handleBizError(bizError)
      return Promise.reject(bizError)
    }
    if (status === 429) {
      // Nginx 限流直接返回 429、不带业务码（设计书 11.2），与应用层 5101 不混用
      ElMessage.error('操作过于频繁，请稍后再试')
    } else if (status === 401) {
      ElMessage.error('登录已失效，请重新登录')
      redirectToLogin()
    } else if (status >= 500) {
      ElMessage.error('服务暂时不可用，请稍后重试')
    } else if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请检查网络后重试')
    } else {
      ElMessage.error('网络异常，请稍后重试')
    }
    return Promise.reject(error)
  },
)

/** 按设计书 6.6 的前端提示约定分流：401 跳登录、403 提示无权限、3001 交表单、其余顶部轻提示 */
function handleBizError(error) {
  if (error.code === 1001) {
    ElMessage.error('登录已失效，请重新登录')
    redirectToLogin()
    return
  }
  if (error.code === 2001 || error.code === 2002) {
    ElMessage.error(error.message || '无权限执行该操作')
    return
  }
  if (error.code === 3001) {
    // 字段级错误由表单侧读取 error.data.fieldErrors 展示，这里不再重复弹窗
    return
  }
  ElMessage.error(error.message || '操作失败')
}

const request = {
  get(url, params, config) {
    return requestWithRetry({ ...config, url, method: 'get', params })
  },
  post(url, data, config) {
    return requestWithRetry({ ...config, url, method: 'post', data })
  },
  put(url, data, config) {
    return requestWithRetry({ ...config, url, method: 'put', data })
  },
  patch(url, data, config) {
    return requestWithRetry({ ...config, url, method: 'patch', data })
  },
  delete(url, config) {
    return requestWithRetry({ ...config, url, method: 'delete' })
  },
  /** 二进制下载（单只袋二维码 PNG、批量生成的二维码 ZIP）：走 blob，返回完整 response 以便取文件名。 */
  download(url, params, config) {
    return requestWithRetry({
      ...config,
      url,
      // 允许调用方覆盖为 post（batch-generate 是 POST 触发下载），不能写死 get
      method: config?.method || 'get',
      params,
      responseType: 'blob',
      timeout: 60000,
    })
  },
}

export default request
