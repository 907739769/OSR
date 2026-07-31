import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getRenameTaskListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/rename-tasks', { params })
}

export function addRenameTaskApi(data: any) {
  return request.post('/openliststrm/rename-tasks', data)
}

export function updateRenameTaskApi(data: any) {
  return request.put('/openliststrm/rename-tasks', data)
}

export function deleteRenameTaskApi(taskId: number) {
  return request.delete(`/openliststrm/rename-tasks/${taskId}`)
}

export function batchDeleteRenameTaskApi(recordIds: number[]) {
  return request.post('/openliststrm/rename-tasks/batchDelete', null, { params: { ids: recordIds.join(',') } })
}

export function executeRenameTaskApi(taskIds: number[]) {
  return request.post('/openliststrm/rename-tasks/execute', taskIds)
}

export function testRenameTaskApi(taskId: number) {
  return request.get(`/openliststrm/rename-tasks/test/${taskId}`)
}

export function testParseRenameApi(filename: string, template?: string) {
  return request.post('/openliststrm/rename-tasks/test/parse', null, { params: { filename, template } })
}
