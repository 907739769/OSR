import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getPtSubscriptionListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/pt-subscriptions', { params })
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

/** 搜索补集：关键词搜索所有索引器并推送最优结果 */
export function searchSupplementApi(id: number, data: { episode: number; keyword: string; manualSelect?: boolean }) {
  return request.post<any, { pushed: boolean; candidateCount: number; candidates?: any[] }>(
    `/openliststrm/pt-subscriptions/${id}/search`,
    data,
    { timeout: 60000 }
  )
}

/** 手动选择推送：用户在候选列表中选中一个种子后推送到下载器 */
export function pushSelectedCandidateApi(id: number, data: {
  episode: number
  title: string
  size: number
  seeders: number
  peers: number
  downloadVolumeFactor: number
  indexerId: number
  guid: string
  downloadUrl: string
  infoHash?: string
  description?: string
  pubDate?: string
}) {
  return request.post(`/openliststrm/pt-subscriptions/${id}/push-selected`, data)
}

/** 查订阅最近的匹配/过滤日志，排查"这一轮为什么没抓到" */
export function getSubscriptionSearchLogsApi(id: number) {
  return request.get<any, any[]>(`/openliststrm/pt-subscriptions/${id}/search-logs`)
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

/** 批量删除订阅 */
export function batchDeletePtSubscriptionApi(ids: number[]) {
  return request.post('/openliststrm/pt-subscriptions/batchDelete', null, { params: { ids: ids.join(',') } })
}
