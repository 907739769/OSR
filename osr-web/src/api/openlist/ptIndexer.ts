import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export function getPtIndexerListApi(params: SearchParams) {
  return request.get<any, PageResult<any>>('/openliststrm/pt-indexers', { params })
}

export function addPtIndexerApi(data: any) {
  return request.post('/openliststrm/pt-indexers', data)
}

export function updatePtIndexerApi(data: any) {
  return request.put('/openliststrm/pt-indexers', data)
}

export function deletePtIndexerApi(id: number) {
  return request.delete(`/openliststrm/pt-indexers/${id}`)
}

/** 连通性测试，传入表单当前值，无需先保存 */
export function testPtIndexerApi(data: any) {
  return request.post('/openliststrm/pt-indexers/test', data)
}

/** 获取索引器支持的分类树，传入表单当前值，无需先保存 */
export function getPtIndexerCategoriesApi(data: any) {
  return request.post('/openliststrm/pt-indexers/categories', data)
}
