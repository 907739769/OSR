import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getPtTransferRuleListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/pt-transfer-rules', { params })
}

export function addPtTransferRuleApi(data: any) {
  return request.post('/openliststrm/pt-transfer-rules', data)
}

export function updatePtTransferRuleApi(data: any) {
  return request.put('/openliststrm/pt-transfer-rules', data)
}

export function deletePtTransferRuleApi(id: number) {
  return request.delete(`/openliststrm/pt-transfer-rules/${id}`)
}

/**
 * 预览：只判定不搬动，返回源下载器上每个种子会不会被转移、不转移的原因，
 * 以及映射后的目标路径（路径映射配错时这一列是唯一的诊断依据）
 */
export function previewPtTransferRuleApi(id: number) {
  return request.post<any, any[]>(`/openliststrm/pt-transfer-rules/preview/${id}`)
}

/** 立即执行一次转移，不等定时任务 */
export function runPtTransferRuleApi(id: number) {
  return request.post<any, any>(`/openliststrm/pt-transfer-rules/run/${id}`)
}

/** 转移记录（只读） */
export function getPtTransferRecordListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/pt-transfer-records', { params })
}

/**
 * 清除失败记录，返回删除条数。
 * 同一个种子失败太多次后后端不再自动重试，这是唯一的解除入口——配置改对之后
 * 得先把历史失败清掉，那些种子才会重新参与转移
 */
export function clearPtTransferFailedRecordsApi(ruleId?: number) {
  return request.delete<any, number>('/openliststrm/pt-transfer-records/failed', { params: { ruleId } })
}
