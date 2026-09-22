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
  /** 拉取榜单失败时的原因；成功时为空 */
  fetchError?: string | null
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

/**
 * 立即执行一次该规则，不受执行间隔限制。
 * 同步等结果，超时放宽到 3 分钟：豆瓣源每条都要按标题搜 TMDb 再建订阅，
 * 默认 15 秒必然超时——页面报错而后端还在建订阅，用户很可能再点一次
 */
export function runPtAutoAddRuleApi(id: number) {
  return request.post<any, AutoAddRunResult>(`/openliststrm/pt-auto-add-rules/${id}/run`, undefined, { timeout: 180000 })
}

/** 该规则最近的执行日志，最多 100 条。跳过类结果同一条目只记第一次 */
export function getPtAutoAddRuleLogsApi(id: number) {
  return request.get<any, any[]>(`/openliststrm/pt-auto-add-rules/${id}/logs`)
}
