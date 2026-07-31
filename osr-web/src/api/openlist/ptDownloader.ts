import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getPtDownloaderListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/pt-downloaders', { params })
}

export function addPtDownloaderApi(data: any) {
  return request.post('/openliststrm/pt-downloaders', data)
}

export function updatePtDownloaderApi(data: any) {
  return request.put('/openliststrm/pt-downloaders', data)
}

export function deletePtDownloaderApi(id: number) {
  return request.delete(`/openliststrm/pt-downloaders/${id}`)
}

/** 连通性测试 */
export function testPtDownloaderApi(data: any) {
  return request.post('/openliststrm/pt-downloaders/test', data)
}

/** 校验保存路径。返回空串表示通过，非空为警告文案 */
export function validateSavePathApi(data: any) {
  return request.post<any, string>('/openliststrm/pt-downloaders/validate-save-path', data)
}
