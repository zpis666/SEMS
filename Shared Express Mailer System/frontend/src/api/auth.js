import request from '@/utils/request'

/**
 * 认证接口（M1，见《系统设计说明书》5.1）。
 * 注意：登录/注册/验证码是**匿名写接口**，不带 clientRequestId（见 9.6 的适用与豁免）。
 */

/** 获取图形验证码（一次性，TTL 3 分钟）：返回 { captchaId, imageBase64 } */
export function getCaptcha() {
  return request.get('/auth/captcha')
}

/** 注册：{ phone, password, nickname, captchaId, captchaCode } */
export function register(data) {
  return request.post('/auth/register', data, { skipIdempotent: true })
}

/** 登录：{ phone, password, captchaId?, captchaCode? }；返回 { token, expiresAt, user } */
export function login(data) {
  return request.post('/auth/login', data, { skipIdempotent: true })
}

/** 静默续签：旧 jti 入黑名单，换发新 token */
export function refresh() {
  return request.post('/auth/refresh')
}

/** 退出登录：当前端 jti 入黑名单 */
export function logout() {
  return request.post('/auth/logout')
}
