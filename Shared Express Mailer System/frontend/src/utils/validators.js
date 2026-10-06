/**
 * 表单校验规则 —— 与后端注解**同源对齐**（见《系统设计说明书》10.4）。
 *
 * 定位：前端校验是体验性拦截（即时反馈、减少无效请求）；**后端校验才是权威**（不可信任前端）。
 * 因此这里的每条规则都对应后端的一处 JSR-380 注解或 Service 校验，改一处必须改两处。
 * 正则与后端 com.sems.common.Constants 中的常量逐字一致，不要在这里「顺手优化」。
 */

/** 袋码：SJB-规格1位-年月4位-流水5位-校验1位（设计书 7.4 / 9.4） */
export const BAG_CODE_REGEX = /^SJB-[A-Z]-\d{4}-\d{5}-[0-9A-Z]$/

/** 手机号：11 位大陆手机号 */
export const PHONE_REGEX = /^1[3-9]\d{9}$/

/** 运单号：8–20 位字母数字 */
export const TRACKING_NO_REGEX = /^[A-Za-z0-9]{8,20}$/

/** UUID v4：幂等头格式（由 utils/request.js 自动生成，此处仅用于校验） */
export const UUID_REGEX =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

/** 密码强度：≥8 位且至少含两类字符（数字/字母/符号） */
export function isStrongPassword(value) {
  if (!value || value.length < 8) return false
  const hasLetter = /[A-Za-z]/.test(value)
  const hasOther = /[0-9\W_]/.test(value)
  return hasLetter && hasOther
}

/** 密码强度档位，用于强度条提示：0 无、1 弱、2 中、3 强 */
export function passwordStrength(value) {
  if (!value || value.length < 8) return 0
  let kinds = 0
  if (/[a-z]/.test(value)) kinds += 1
  if (/[A-Z]/.test(value)) kinds += 1
  if (/[0-9]/.test(value)) kinds += 1
  if (/[^A-Za-z0-9]/.test(value)) kinds += 1
  if (kinds >= 3 && value.length >= 12) return 3
  if (kinds >= 2) return 2
  return 1
}

/** 输入自动大写：袋码与运单号在扫码枪/H5 手输时统一大写，减少大小写导致的校验失败 */
export function toUpperCase(value) {
  return (value || '').toUpperCase().trim()
}

/** Element Plus 表单校验器工厂：正则匹配 */
export function patternValidator(regex, message) {
  return (_rule, value, callback) => {
    if (!value) return callback()
    return regex.test(value) ? callback() : callback(new Error(message))
  }
}

/** 手机号规则 */
export const phoneRules = [
  { required: true, message: '请输入手机号', trigger: 'blur' },
  { pattern: PHONE_REGEX, message: '请输入正确的 11 位手机号', trigger: 'blur' },
]

/** 密码规则（登录只用必填，注册/改密用强度规则） */
export const passwordRules = [
  { required: true, message: '请输入密码', trigger: 'blur' },
  { min: 8, message: '密码至少 8 位', trigger: 'blur' },
  {
    validator: (_rule, value, callback) => {
      if (!value || isStrongPassword(value)) return callback()
      return callback(new Error('密码需包含字母与数字/符号两类字符'))
    },
    trigger: 'blur',
  },
]

/** 袋码规则 */
export const bagCodeRules = [
  { required: true, message: '请输入或扫描袋码', trigger: 'blur' },
  {
    validator: patternValidator(BAG_CODE_REGEX, '袋码格式形如 SJB-L-2609-00001-4'),
    trigger: 'blur',
  },
]

/** 运单号规则 */
export const trackingNoRules = [
  { required: true, message: '请输入运单号', trigger: 'blur' },
  {
    validator: patternValidator(TRACKING_NO_REGEX, '运单号应为 8–20 位字母或数字'),
    trigger: 'blur',
  },
]

/** 收件人手机号规则（落库转掩码 + HMAC 哈希，见设计书 8.2） */
export const recipientPhoneRules = [
  { required: true, message: '请输入收件人手机号', trigger: 'blur' },
  { pattern: PHONE_REGEX, message: '请输入正确的 11 位手机号', trigger: 'blur' },
]

/** 昵称规则：与后端 sys_user.nickname varchar(32) 对齐 */
export const nicknameRules = [
  { required: true, message: '请输入昵称', trigger: 'blur' },
  { min: 2, max: 16, message: '昵称长度 2–16 个字符', trigger: 'blur' },
]
