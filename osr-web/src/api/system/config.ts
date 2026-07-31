import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'
import type { SysConfig } from '@/types/system'

export function getConfigListApi(params: SearchParams) {
  return request.get<any, PageResult<SysConfig[]>>('/system/config/list', { params })
}

export function getConfigDetailApi(configId: number) {
  return request.get<any, SysConfig>(`/system/config/${configId}`)
}

export function addConfigApi(data: SysConfig) {
  return request.post('/system/config', data)
}

export function updateConfigApi(data: SysConfig) {
  return request.put('/system/config', data)
}

export function deleteConfigApi(configId: number) {
  return request.delete(`/system/config/${configId}`)
}

export function refreshCacheApi() {
  return request.post('/system/config/refreshCache')
}
