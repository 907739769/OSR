import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getCopyRecordListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/copy-records', { params })
}

export function retryCopyRecordApi(recordId: number) {
  return request.post(`/openliststrm/copy-records/retry/${recordId}`)
}

export function batchRetryCopyRecordApi(recordIds: number[]) {
  return request.post('/openliststrm/copy-records/retry', null, { params: { ids: recordIds.join(',') } })
}

export function batchDeleteCopyRecordApi(recordIds: number[]) {
  return request.post('/openliststrm/copy-records/batchDelete', null, { params: { ids: recordIds.join(',') } })
}

export function batchRemoveCopyNetDiskApi(recordIds: number[]) {
  return request.post('/openliststrm/copy-records/batchRemoveNetDisk', null, { params: { ids: recordIds.join(',') } })
}
