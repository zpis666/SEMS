import request from '@/utils/request'

/** 用户管理接口（M1，见《系统设计说明书》5.1）。用户端 3 个 + 平台管理端 6 个。 */

/** 个人信息（手机号脱敏） */
export function getMe() {
  return request.get('/users/me')
}

/** 修改昵称等非敏感字段 */
export function updateMe(data) {
  return request.patch('/users/me', data)
}

/** 修改密码：成功后 token_version 自增，所有旧 token 失效，前端须回登录页 */
export function changePassword(data) {
  return request.post('/users/me/password', data)
}

/* ------------------------- 以下为平台管理员接口 ------------------------- */

/** 分页查询用户：{ pageNum, pageSize, phone, role, status } */
export function listUsers(params) {
  return request.get('/users', params)
}

/** 创建账号并授予角色（站点管理员须同时给 stationId） */
export function createUser(data) {
  return request.post('/users', data)
}

/** 调整角色（触发 token_version+1，旧 token 立即失效） */
export function updateRoles(id, data) {
  return request.put(`/users/${id}/roles`, data)
}

/** 调整站点绑定（触发 token_version+1） */
export function updateStation(id, data) {
  return request.put(`/users/${id}/station`, data)
}

/** 启用/禁用账号（禁用触发 token_version+1） */
export function updateStatus(id, data) {
  return request.patch(`/users/${id}/status`, data)
}

/** 重置密码：置 must_change_password=1，响应携带 mustChangePassword=true */
export function resetPassword(id) {
  return request.post(`/users/${id}/password-reset`)
}
