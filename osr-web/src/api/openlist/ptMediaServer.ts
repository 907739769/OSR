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

/** 连通性测试。成功时返回「已连通：Emby 4.8.0.80 · 客厅」这类描述，失败由拦截器弹出具体原因 */
export function testPtMediaServerApi(data: any) {
  return request.post<any, string>('/openliststrm/pt-media-servers/test', data)
}

/** 拉取该媒体服务器上的用户列表，供填「用户ID」时选取 */
export function listPtMediaServerUsersApi(data: any) {
  return request.post<any, { id: string; name?: string }[]>('/openliststrm/pt-media-servers/users', data)
}
