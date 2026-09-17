import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getStrmRecordListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/strm-records', { params })
}

/** 当前筛选条件下按状态分组计数（忽略状态这一项），返回 { 状态值: 条数, total } */
export function getStrmRecordStatsApi(params: SearchParams) {
  return request.get<any, Record<string, number>>('/openliststrm/strm-records/stats', { params })
}

/** 重试全部失败的记录（一次最多最新 200 条） */
export function retryAllFailedStrmRecordApi() {
  return request.post<any, { retried: number, remaining: number }>('/openliststrm/strm-records/retry-failed')
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
