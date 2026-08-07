import request from '@/api/request'

export interface PtStatsOverview {
  totalSubscriptions: number
  activeSubscriptions: number
  totalDownloadRecords: number
  completedCount: number
  failedCount: number
  successRate: number
  avgDurationMinutes: number
}

export interface PtStatsTrendPoint {
  date: string
  pushedCount: number
  completedCount: number
  failedCount: number
  avgDurationMinutes: number | null
}

export interface PtStatsIndexerHitRate {
  indexerId: number
  indexerName: string
  acceptedCount: number
  rejectedCount: number
  hitRate: number
  hasData: boolean
}

export interface PtStatsFailReason {
  reason: string
  count: number
}

/** 搜索淘汰原因分布。与失败原因对称：那个是"推送后下载失败"，这个是"候选在推送前被过滤规则挡掉" */
export interface PtStatsRejectReason {
  /** 原始码，见后端 RejectCode 枚举 */
  code: string
  /** 中文短标签，如「非免费种」 */
  reason: string
  count: number
}

export interface PtStatsActiveSubscription {
  subId: number
  title: string
  season: number | null
  mediaType: string | null
  downloadCount: number
  completedCount: number
  failedCount: number
  lastMatchTime: string | null
}

export function getPtStatsOverviewApi() {
  return request.get<any, PtStatsOverview>('/openliststrm/pt-stats/overview')
}

export function getPtStatsTrendApi(days: number) {
  return request.get<any, PtStatsTrendPoint[]>('/openliststrm/pt-stats/trend', { params: { days } })
}

export function getPtStatsIndexerHitRateApi() {
  return request.get<any, PtStatsIndexerHitRate[]>('/openliststrm/pt-stats/indexer-hit-rate')
}

export function getPtStatsFailReasonsApi(days: number) {
  return request.get<any, PtStatsFailReason[]>('/openliststrm/pt-stats/fail-reasons', { params: { days } })
}

// 不带 days：pt_search_log 本身按订阅保留 ≤200 条，叠加时间筛选口径会不一致（同索引器命中率）
export function getPtStatsRejectReasonsApi() {
  return request.get<any, PtStatsRejectReason[]>('/openliststrm/pt-stats/reject-reasons')
}

export function getPtStatsTopSubscriptionsApi(days: number, limit: number) {
  return request.get<any, PtStatsActiveSubscription[]>('/openliststrm/pt-stats/top-subscriptions', { params: { days, limit } })
}
