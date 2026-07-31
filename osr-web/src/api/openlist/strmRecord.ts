import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getStrmRecordListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/strm-records', { params })
}

export function retryStrmRecordApi(recordId: number) {
  return request.post(`/openliststrm/strm-records/retry/${recordId}`)
}

export function batchRetryStrmRecordApi(recordIds: number[]) {
  return request.post('/openliststrm/strm-records/retry', null, { params: { ids: recordIds.join(',') } })
}

export function batchDeleteStrmRecordApi(recordIds: number[]) {
  return request.post('/openliststrm/strm-records/batchDelete', null, { params: { ids: recordIds.join(',') } })
}

export function batchRemoveStrmNetDiskApi(recordIds: number[]) {
  return request.post('/openliststrm/strm-records/batchRemoveNetDisk', null, { params: { ids: recordIds.join(',') } })
}
