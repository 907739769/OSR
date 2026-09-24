import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getPtSubscriptionListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/pt-subscriptions', { params })
}

/** 订阅「归属」筛选的一个选项；value 是 mine / public / 用户 id，count 是可见范围内的订阅数 */
export interface SubscriptionOwnerOption {
  value: string
  label: string
  count: number
}

export function getSubscriptionOwnersApi() {
  return request.get<any, SubscriptionOwnerOption[]>('/openliststrm/pt-subscriptions/owners')
}

export function addPtSubscriptionApi(data: any) {
  return request.post('/openliststrm/pt-subscriptions', data)
}

export function updatePtSubscriptionApi(data: any) {
  return request.put('/openliststrm/pt-subscriptions', data)
}

export function deletePtSubscriptionApi(id: number) {
  return request.delete(`/openliststrm/pt-subscriptions/${id}`)
}

/** 按 id 查单条订阅详情，用于下载记录页跳转定位 */
export function getPtSubscriptionByIdApi(id: number) {
  return request.get<any, any>(`/openliststrm/pt-subscriptions/${id}`)
}

/** TMDb 搜索，供建订阅时选片 */
export function tmdbSearchApi(mediaType: string, keyword: string) {
  return request.get<any, any[]>('/openliststrm/pt-subscriptions/tmdb-search', {
    params: { mediaType, keyword }
  })
}

/** 查某剧指定季在 TMDb 上的总集数 */
export function tmdbSeasonEpisodeCountApi(tmdbId: string, season: number) {
  return request.get<any, number>(`/openliststrm/pt-subscriptions/tmdb-seasons/${tmdbId}`, {
    params: { season }
  })
}

/** 建订阅 */
export function subscribeApi(data: any) {
  return request.post('/openliststrm/pt-subscriptions/subscribe', data)
}

/** 查订阅进度 */
export function getSubscriptionProgressApi(id: number) {
  return request.get<any, any>(`/openliststrm/pt-subscriptions/${id}/progress`)
}

/** 查订阅的每集明细 */
export function getSubscriptionEpisodesApi(id: number) {
  return request.get<any, any[]>(`/openliststrm/pt-subscriptions/${id}/episodes`)
}

/** 手动把某一集重置为缺失，用于用户从媒体库误删或想重新洗版某集 */
export function resetEpisodeApi(id: number, episode: number) {
  return request.post(`/openliststrm/pt-subscriptions/${id}/episodes/${episode}/reset`)
}

/** 立即与媒体库对账刷新 */
export function refreshSubscriptionApi(id: number) {
  return request.post(`/openliststrm/pt-subscriptions/${id}/refresh`)
}

/** 暂停订阅 */
export function pauseSubscriptionApi(id: number) {
  return request.post(`/openliststrm/pt-subscriptions/${id}/pause`)
}

/** 恢复订阅 */
export function resumeSubscriptionApi(id: number) {
  return request.post(`/openliststrm/pt-subscriptions/${id}/resume`)
}

/**
 * 搜索补集：关键词搜索索引器并推送最优结果。indexerIds 省略或为空时搜全部启用中的索引器。
 * <p>
 * 超时按整季自动推送的上限配：那条路径与缺集体检「立即补搜」是同一套（季搜索 30 秒 + 单集补发
 * 最多 180 秒），60 秒的旧超时会让它必然被前端判超时——而后端照样在跑，用户再点一次就是并发两遍。
 * </p>
 */
export function searchSupplementApi(id: number, data: {
  episode: number
  keyword: string
  manualSelect?: boolean
  indexerIds?: number[]
}) {
  return request.post<any, {
    pushed: boolean
    candidateCount: number
    candidates?: any[]
    /** 没推成的原因，与匹配日志里的是同一句话 */
    reason?: string
    /** 推送成功的资源个数，整季搜索可能不止一个 */
    pushedCount?: number
  }>(
    `/openliststrm/pt-subscriptions/${id}/search`,
    data,
    { timeout: 240000 }
  )
}

/** 手动选择推送：用户在候选列表中选中一个种子后推送到下载器 */
export function pushSelectedCandidateApi(id: number, data: {
  episode: number
  title: string
  size: number
  seeders: number
  peers: number
  /** 下载量系数原值：0=免费，0.5=半价，1=正常计量。省略时后端按 1.0 兜底，绝不会当成免费 */
  downloadVolumeFactor?: number
  indexerId: number
  guid: string
  downloadUrl: string
  infoHash?: string
  description?: string
  pubDate?: string
  /** 种子内文件数，原样回传：季包的集数估算要用它，丢了会让体积规则的判定与列表不一致 */
  files?: number
}) {
  return request.post(`/openliststrm/pt-subscriptions/${id}/push-selected`, data)
}

/** 查订阅最近的匹配/过滤日志，排查"这一轮为什么没抓到" */
export function getSubscriptionSearchLogsApi(id: number) {
  return request.get<any, any[]>(`/openliststrm/pt-subscriptions/${id}/search-logs`)
}

/** 一集的诊断：最近一轮搜索的时间、候选数与按原因计数的淘汰情况 */
export interface EpisodeDiagnosis {
  episode: number
  label: string
  state: string
  lastSearchTime: string | null
  source: string | null
  candidates: number
  accepted: number
  reasons: { label: string; count: number }[]
  summary: string
}

export interface SubscriptionDiagnosis {
  subId: number
  title: string
  /** 订阅级的前提问题（没开自动补搜、没有启用的索引器……），先解决这些再看逐集 */
  notes: string[]
  episodes: EpisodeDiagnosis[]
  /** 已播出仍未入库的集总数；大于 episodes.length 时说明被截断了 */
  pendingTotal: number
}

/** 一键诊断：只读，不发起任何搜索 */
export function getSubscriptionDiagnosisApi(id: number) {
  return request.get<any, SubscriptionDiagnosis>(`/openliststrm/pt-subscriptions/${id}/diagnosis`)
}

/** 批量暂停订阅 */
export function batchPauseSubscriptionApi(ids: number[]) {
  return request.post<any, { successCount: number; failedIds: number[] }>(
    '/openliststrm/pt-subscriptions/batchPause', null, { params: { ids: ids.join(',') } }
  )
}

/** 批量恢复订阅 */
export function batchResumeSubscriptionApi(ids: number[]) {
  return request.post<any, { successCount: number; failedIds: number[] }>(
    '/openliststrm/pt-subscriptions/batchResume', null, { params: { ids: ids.join(',') } }
  )
}

/** 批量开启/关闭自动补搜，返回实际生效的条数（无权操作的会被后端过滤掉） */
export function batchAutoSearchSubscriptionApi(ids: number[], enabled: boolean) {
  return request.post<any, number>(
    '/openliststrm/pt-subscriptions/batchAutoSearch', null, { params: { ids: ids.join(','), enabled } }
  )
}

/** 批量删除订阅 */
export function batchDeletePtSubscriptionApi(ids: number[]) {
  return request.post('/openliststrm/pt-subscriptions/batchDelete', null, { params: { ids: ids.join(',') } })
}
