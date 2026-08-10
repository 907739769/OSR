import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

/** 一条自动删种规则 */
export interface PtCleanRule {
  id?: number
  downloaderId?: number
  name?: string
  /** 体积区间下界（GB，含） */
  minSizeGb?: number
  /** 体积区间上界（GB，不含），空表示不限 */
  maxSizeGb?: number | null
  /** 最短做种时长（小时） */
  minSeedHours?: number
  deleteFiles?: string
  enabled?: string
  sortOrder?: number
  remark?: string
}

/** 预览里的一个辅种组 */
export interface PtCleanPreviewRow {
  name: string
  torrentCount: number
  sizeBytes: number
  deletable: boolean
  deleteFiles: boolean
  skipReason: string
  blockedBy: string
}

/** 一次清理的执行结果 */
export interface PtCleanSummary {
  downloaderName: string
  deletedGroups: number
  deletedTorrents: number
  freedBytes: number
  scannedGroups: number
  failedGroups: number
  noRules: boolean
}

export function getPtCleanRuleListApi(params: SearchParams) {
  return request.get<any, PageResult<PtCleanRule>>('/openliststrm/pt-clean-rules', { params })
}

export function addPtCleanRuleApi(data: PtCleanRule) {
  return request.post('/openliststrm/pt-clean-rules', data)
}

export function updatePtCleanRuleApi(data: PtCleanRule) {
  return request.put('/openliststrm/pt-clean-rules', data)
}

export function deletePtCleanRuleApi(id: number) {
  return request.delete(`/openliststrm/pt-clean-rules/${id}`)
}

/** 预览：按当前规则判定但不删除任何东西 */
export function previewPtCleanApi(downloaderId: number) {
  return request.post<any, PtCleanPreviewRow[]>(`/openliststrm/pt-clean-rules/preview/${downloaderId}`)
}

/** 立即执行一次清理 */
export function runPtCleanApi(downloaderId: number) {
  return request.post<any, PtCleanSummary>(`/openliststrm/pt-clean-rules/run/${downloaderId}`)
}
