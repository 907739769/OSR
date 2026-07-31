import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export interface PtTorrentBlacklistQuery extends SearchParams {
  type?: string
  displayValue?: string
}

export function getPtTorrentBlacklistListApi(params: PtTorrentBlacklistQuery) {
  return request.get<any, PageResult<any>>('/openliststrm/pt-torrent-blacklists', { params })
}

/** 新增：管理页只支持发布组类型，服务层会拒绝 type=GUID 的请求 */
export function addPtTorrentBlacklistApi(data: any) {
  return request.post('/openliststrm/pt-torrent-blacklists', data)
}

/** 修改：同样仅支持发布组类型；管理页当前不提供编辑入口，保留此接口与后端能力对齐 */
export function updatePtTorrentBlacklistApi(data: any) {
  return request.put('/openliststrm/pt-torrent-blacklists', data)
}

export function deletePtTorrentBlacklistApi(id: number) {
  return request.delete(`/openliststrm/pt-torrent-blacklists/${id}`)
}
