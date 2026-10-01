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

export interface LibraryRoot {
  name?: string
  path: string
  key?: string
}

export interface MappingCheckRow {
  /** 目录是谁的：「STRM 全局输出目录」「重命名任务#2 目标目录」 */
  source: string
  localPath: string
  mappedPath: string
  /** 命中的映射规则；null 表示按原路径比对 */
  rule?: string | null
  /** 落在哪个媒体库；null 表示不在任何库下 */
  libraryName?: string | null
  libraryPath?: string | null
  /** 目录本身不在库下、但它下面有库目录时的那些库名 */
  nestedLibraries: string[]
}

export interface MappingCheckResult {
  libraries: LibraryRoot[]
  rows: MappingCheckRow[]
}

/** 用表单上（未保存）的路径映射，把 OSR 会写新文件的目录逐条映射，看各自落在哪个媒体库 */
export function checkPtMediaServerMappingApi(data: any) {
  return request.post<any, MappingCheckResult>('/openliststrm/pt-media-servers/check-mapping', data)
}
