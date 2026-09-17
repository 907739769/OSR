import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getRenameDetailListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/rename-details', { params })
}

/** 当前筛选条件下按状态分组计数（忽略状态这一项），返回 { 状态值: 条数, total } */
export function getRenameDetailStatsApi(params: SearchParams) {
  return request.get<any, Record<string, number>>('/openliststrm/rename-details/stats', { params })
}

/** 重试全部失败的重命名明细（一次最多最新 200 条） */
export function retryAllFailedRenameDetailApi() {
  return request.post<any, { retried: number, remaining: number }>('/openliststrm/rename-details/retry-failed')
}

export function batchDeleteRenameDetailApi(recordIds: number[]) {
  return request.post('/openliststrm/rename-details/batchDelete', null, { params: { ids: recordIds.join(',') } })
}

export function executeRenameDetailApi(detailIds: number[], title?: string, year?: string, season?: string, episode?: string) {
  const params: Record<string, any> = { ids: detailIds.join(',') }
  if (title) params.title = title
  if (year) params.year = year
  if (season) params.season = season
  if (episode) params.episode = episode
  return request.post('/openliststrm/rename-details/execute', null, { params })
}

export function scrapeRenameDetailApi(detailId: number) {
  return request.post(`/openliststrm/rename-details/scrape/${detailId}`)
}

export function batchScrapeRenameDetailApi(detailIds: number[]) {
  return request.post('/openliststrm/rename-details/scrape', null, { params: { ids: detailIds.join(',') } })
}

export function deleteScrapeFilesApi(detailId: number) {
  return request.post(`/openliststrm/rename-details/scrape/delete/${detailId}`)
}

export function batchDeleteScrapeFilesApi(detailIds: number[]) {
  return request.post('/openliststrm/rename-details/scrape/batch', null, { params: { ids: detailIds.join(',') } })
}

/** 预览清理：返回这批记录名下磁盘上真实存在、将被删除的文件绝对路径。只读 */
export function previewPurgeApi(detailIds: number[]) {
  return request.post<any, string[]>('/openliststrm/rename-details/purge/preview', null, { params: { ids: detailIds.join(',') } })
}

/**
 * 清理重命名产物：删目标库里的主文件 + 刮削文件 + 回收空目录。
 * 只动目标库副本，源文件（网盘挂载 / 下载器保种目录）不碰。
 */
export function purgeRenameDetailApi(detailIds: number[], deleteRecord: boolean) {
  return request.post<any, string>('/openliststrm/rename-details/purge', null, {
    params: { ids: detailIds.join(','), deleteRecord }
  })
}
