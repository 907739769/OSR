import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getCopyRecordListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/copy-records', { params })
}

/** 返回实际提交重试的条数；记录处于处理中或已成功时后端直接报错 */
export function retryCopyRecordApi(recordId: number) {
  return request.post<any, number>(`/openliststrm/copy-records/retry/${recordId}`)
}

/** 返回实际提交重试的条数，选中的记录里处理中/已成功的会被跳过 */
export function batchRetryCopyRecordApi(recordIds: number[]) {
  return request.post<any, number>('/openliststrm/copy-records/retry', null, { params: { ids: recordIds.join(',') } })
}

export function batchDeleteCopyRecordApi(recordIds: number[]) {
  return request.post('/openliststrm/copy-records/batchDelete', null, { params: { ids: recordIds.join(',') } })
}

export function batchRemoveCopyNetDiskApi(recordIds: number[]) {
  return request.post('/openliststrm/copy-records/batchRemoveNetDisk', null, { params: { ids: recordIds.join(',') } })
}
