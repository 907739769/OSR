import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export interface PtDownloadRecordQuery extends SearchParams {
  subId?: number
  state?: string
  title?: string
  failReasonCode?: string
  indexerId?: number
  downloaderId?: number
  hrState?: string
  /** 为 true 时隐藏已被后续推送接替的失败记录 */
  hideSuperseded?: boolean
  /** 为 true 时隐藏用户已忽略的失败记录 */
  hideIgnored?: boolean
  /** 日期区间落在哪一列，默认推送时间 */
  dateField?: DownloadRecordDateField
}

/** 日期区间列：推送时间 / 完成时间 / 失败时间。与统计仪表盘趋势图三条线的分组口径一一对应 */
export type DownloadRecordDateField = 'PUSHED' | 'COMPLETED' | 'FAILED'

/** 与后端 DownloadRecordView 一一对应 */
export interface PtDownloadRecordView {
  id: number
  subId: number
  /** 关联订阅已被删除时为 null */
  subTitle?: string | null
  episodeLabel?: string | null
  indexerId?: number | null
  indexerName?: string | null
  downloaderId?: number | null
  downloaderName?: string | null
  title: string
  torrentHash?: string | null
  size?: number | null
  /** 推送那一刻的做种数快照 */
  seeders?: number | null
  state: string
  progress?: number | null
  failReason?: string | null
  failReasonCode?: string | null
  /** 失败已被用户忽略：不再计入首页待办，统计照算 */
  failIgnored?: boolean
  pushedTime?: string | null
  completedTime?: string | null
  hrState?: string | null
  hrSeedSeconds?: number | null
  hrRatio?: number | null
  hrSeedHoursRequired?: number | null
  hrRatioRequired?: number | null
  /** 标题解析出的发布组，解析不出时为 null */
  releaseGroup?: string | null
  guidBlacklisted?: boolean
  releaseGroupBlacklisted?: boolean
  /** 失败记录之后接替它的那条下载记录 id */
  supersededById?: number | null
}

export function getPtDownloadRecordListApi(params: PtDownloadRecordQuery) {
  return request.get<any, PageResult<PtDownloadRecordView>>('/openliststrm/pt-download-records', { params })
}

/** 顶部统计条：当前筛选条件下按状态计数（忽略状态这一项），返回 `{ 状态: 条数, total }` */
export function getPtDownloadRecordStatsApi(params: PtDownloadRecordQuery) {
  // 统计接口不认分页参数，带着也无害；与其余记录页的写法保持一致
  return request.get<any, Record<string, number>>('/openliststrm/pt-download-records/stats', { params })
}

/** 立即重试一条失败的下载记录：按订阅标题+季/集号重新发起搜索补集 */
export function retryPtDownloadRecordApi(id: number) {
  // 季包记录的重试走整季补搜，耗时上限与 searchSupplementApi 相同
  return request.post<any, { pushed: boolean; candidateCount: number; reason?: string; pushedCount?: number }>(
    `/openliststrm/pt-download-records/${id}/retry`,
    null,
    { timeout: 240000 }
  )
}

/**
 * 批量重试选中的失败下载记录。后端转后台执行、立即返回本批的 batchId 与实际受理条数
 * （无权操作的记录已被过滤掉），跑完后经 PT 状态 WebSocket 推一条同 batchId 的 batchRetry 事件
 */
export function batchRetryPtDownloadRecordApi(ids: number[]) {
  return request.post<any, { batchId: string; accepted: number }>(
    '/openliststrm/pt-download-records/batchRetry', null, { params: { ids: ids.join(',') } }
  )
}

/**
 * 忽略 / 取消忽略选中的失败记录（非失败状态的不计），返回实际改动条数。
 * 忽略只把它从首页待办里拿掉：订阅那一集照旧缺失、自动补搜照常，统计仪表盘的失败数也照算
 */
export function batchIgnorePtDownloadRecordApi(ids: number[], ignored = true) {
  return request.post<any, number>(
    '/openliststrm/pt-download-records/batchIgnore', null, { params: { ids: ids.join(','), ignored } }
  )
}

/** 清理旧记录允许的保留天数，与后端 DownloadRecordAdminService.CLEANUP_ALLOWED_DAYS 一致 */
export const CLEANUP_DAY_OPTIONS = [30, 90, 180, 365]

/** 预览按规则清理会删掉多少条（仅管理员） */
export function previewCleanupPtDownloadRecordApi(days: number) {
  return request.get<any, number>('/openliststrm/pt-download-records/cleanup/preview', { params: { days } })
}

/** 清理 N 天前已落定、不再被引用的下载记录（仅管理员），返回实际删除条数 */
export function cleanupPtDownloadRecordApi(days: number) {
  return request.delete<any, number>('/openliststrm/pt-download-records/cleanup', { params: { days } })
}

/** 拉黑该记录对应的种子（GUID 维度），reason 可选；返回 true=新增成功，false=已在黑名单中 */
export function blacklistGuidApi(id: number, reason?: string) {
  return request.post<any, boolean>(
    `/openliststrm/pt-download-records/${id}/blacklist-guid`, reason ? { reason } : {}
  )
}

/** 拉黑该记录标题解析出的发布组，reason 可选；返回 true=新增成功，false=已在黑名单中 */
export function blacklistReleaseGroupApi(id: number, reason?: string) {
  return request.post<any, boolean>(
    `/openliststrm/pt-download-records/${id}/blacklist-release-group`, reason ? { reason } : {}
  )
}

/** 批量拉黑的执行结果：已在黑名单中计 duplicateCount，解析不出发布组等计 failedCount */
export interface BatchBlacklistResult {
  total: number
  addedCount: number
  duplicateCount: number
  failedCount: number
}

/** 批量拉黑选中记录对应的种子（GUID 维度） */
export function batchBlacklistGuidApi(ids: number[], reason?: string) {
  return request.post<any, BatchBlacklistResult>(
    '/openliststrm/pt-download-records/batchBlacklistGuid', reason ? { reason } : {},
    { params: { ids: ids.join(',') } }
  )
}

/** 批量拉黑选中记录标题解析出的发布组（多条同组时只会真正落库一条） */
export function batchBlacklistReleaseGroupApi(ids: number[], reason?: string) {
  return request.post<any, BatchBlacklistResult>(
    '/openliststrm/pt-download-records/batchBlacklistReleaseGroup', reason ? { reason } : {},
    { params: { ids: ids.join(',') } }
  )
}
