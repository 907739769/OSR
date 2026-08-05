import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export interface WecomUserQuery extends SearchParams {
  wecomUserid?: string
  sysUserName?: string
  status?: string
}

/** 可绑定的 OSR 用户下拉项 */
export interface SelectableUser {
  userId: number
  loginName: string
  userName: string
}

export function getWecomUserListApi(params: WecomUserQuery) {
  return request.get<any, PageResult<any>>('/openliststrm/wecom-users', { params })
}

export function addWecomUserApi(data: any) {
  return request.post('/openliststrm/wecom-users', data)
}

export function updateWecomUserApi(data: any) {
  return request.put('/openliststrm/wecom-users', data)
}

export function deleteWecomUserApi(id: number) {
  return request.delete(`/openliststrm/wecom-users/${id}`)
}

/** 建绑定时选人用。只回 id 与名字，不含密码等字段 */
export function getSelectableUsersApi() {
  return request.get<any, SelectableUser[]>('/openliststrm/wecom-users/selectable-users')
}
