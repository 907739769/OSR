import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getCopyTaskListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/copy-tasks', { params })
}

export function addCopyTaskApi(data: any) {
  return request.post('/openliststrm/copy-tasks', data)
}

export function updateCopyTaskApi(data: any) {
  return request.put('/openliststrm/copy-tasks', data)
}

export function deleteCopyTaskApi(taskId: number) {
  return request.delete(`/openliststrm/copy-tasks/${taskId}`)
}

export function batchDeleteCopyTaskApi(recordIds: number[]) {
  return request.post('/openliststrm/copy-tasks/batchDelete', null, { params: { ids: recordIds.join(',') } })
}

export function executeCopyTaskApi(taskIds: number[]) {
  return request.post('/openliststrm/copy-tasks/execute', taskIds)
}
