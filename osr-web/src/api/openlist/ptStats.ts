import request from '@/api/request'

/**
 * 下载相关各项按 rangeDays 统计（null 为全部历史）；订阅数与 H&R 数是当前状态，不受区间影响。
 * 成功率 = 完成 / (完成 + 未被接替的失败)，失败数也只算还没着落的
 */
export interface PtStatsOverview {
  rangeDays: number | null
  totalSubscriptions: number
  activeSubscriptions: number
  /** 区间内推送数 */
  totalDownloadRecords: number
  completedCount: number
  failedCount: number
  successRate: number
  avgDurationMinutes: number
  /** 当前处于「可能已 H&R」的记录数 */
  hrViolatedCount: number
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

/** 失败原因分布。按分类码聚合——fail_reason 原文里嵌着集号与超时小时数，按原文分组会碎成一堆计数为 1 的扇形 */
export interface PtStatsFailReason {
  /** 原始码，见后端 FailReasonCode 枚举；历史未分类记录归为 OTHER */
  code: string
  /** 中文短标签，如「下载超时」 */
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

/** 不传 days 为全部历史（首页 PT 概览卡），仪表盘传当前挡位 */
export function getPtStatsOverviewApi(days?: number) {
  return request.get<any, PtStatsOverview>('/openliststrm/pt-stats/overview', { params: days ? { days } : undefined })
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
