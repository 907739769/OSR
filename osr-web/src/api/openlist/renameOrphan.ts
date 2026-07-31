import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getRenameOrphanListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/rename-orphans', { params })
}

export function scanRenameOrphanApi() {
  return request.post('/openliststrm/rename-orphans/scan')
}

export function batchCleanRenameOrphanApi(orphanIds: number[]) {
  return request.post('/openliststrm/rename-orphans/clean', null, { params: { ids: orphanIds.join(',') } })
}

export function batchIgnoreRenameOrphanApi(orphanIds: number[]) {
  return request.post('/openliststrm/rename-orphans/ignore', null, { params: { ids: orphanIds.join(',') } })
}
