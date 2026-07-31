import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getPtMediaServerListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/pt-media-servers', { params })
}

export function addPtMediaServerApi(data: any) {
  return request.post('/openliststrm/pt-media-servers', data)
}

export function updatePtMediaServerApi(data: any) {
  return request.put('/openliststrm/pt-media-servers', data)
}

export function deletePtMediaServerApi(id: number) {
  return request.delete(`/openliststrm/pt-media-servers/${id}`)
}

/** 连通性测试 */
export function testPtMediaServerApi(data: any) {
  return request.post('/openliststrm/pt-media-servers/test', data)
}
