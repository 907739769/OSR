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

export function getPtStatsTopSubscriptionsApi(days: number, limit: number) {
  return request.get<any, PtStatsActiveSubscription[]>('/openliststrm/pt-stats/top-subscriptions', { params: { days, limit } })
}
