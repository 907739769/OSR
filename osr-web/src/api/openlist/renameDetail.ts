import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getRenameDetailListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/rename-details', { params })
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
