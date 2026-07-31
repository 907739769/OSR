import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export interface PtAutoAddRuleQuery extends SearchParams {
  name?: string
  mediaType?: string
  enabled?: string
}

export interface AutoAddRunResult {
  addedCount: number
  skippedCount: number
  failedCount: number
}

export function getPtAutoAddRuleListApi(params: PtAutoAddRuleQuery) {
  return request.get<any, PageResult<any>>('/openliststrm/pt-auto-add-rules', { params })
}

export function addPtAutoAddRuleApi(data: any) {
  return request.post('/openliststrm/pt-auto-add-rules', data)
}

export function updatePtAutoAddRuleApi(data: any) {
  return request.put('/openliststrm/pt-auto-add-rules', data)
}

export function deletePtAutoAddRuleApi(id: number) {
  return request.delete(`/openliststrm/pt-auto-add-rules/${id}`)
}

/** 立即执行一次该规则，不受执行间隔限制 */
export function runPtAutoAddRuleApi(id: number) {
  return request.post<any, AutoAddRunResult>(`/openliststrm/pt-auto-add-rules/${id}/run`)
}

/** 该规则最近的执行日志，最多 100 条 */
export function getPtAutoAddRuleLogsApi(id: number) {
  return request.get<any, any[]>(`/openliststrm/pt-auto-add-rules/${id}/logs`)
}
