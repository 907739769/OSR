import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getStrmTaskListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/strm-tasks', { params })
}

export function addStrmTaskApi(data: any) {
  return request.post('/openliststrm/strm-tasks', data)
}

export function updateStrmTaskApi(data: any) {
  return request.put('/openliststrm/strm-tasks', data)
}

export function deleteStrmTaskApi(taskId: number) {
  return request.delete(`/openliststrm/strm-tasks/${taskId}`)
}

export function batchDeleteStrmTaskApi(recordIds: number[]) {
  return request.post('/openliststrm/strm-tasks/batchDelete', null, { params: { ids: recordIds.join(',') } })
}

export function executeStrmTaskApi(taskIds: number[]) {
  return request.post('/openliststrm/strm-tasks/execute', taskIds)
}
