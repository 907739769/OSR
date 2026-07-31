import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'
import type { SysDictType, SysDictData } from '@/types/system'

export function getDictTypeListApi(params: SearchParams) {
  return request.get<any, PageResult<SysDictType[]>>('/system/dict/type/list', { params })
}

export function getDictTypeDetailApi(dictId: number) {
  return request.get<any, SysDictType>(`/system/dict/type/${dictId}`)
}

export function addDictTypeApi(data: SysDictType) {
  return request.post('/system/dict/type', data)
}

export function updateDictTypeApi(data: SysDictType) {
  return request.put('/system/dict/type', data)
}

export function deleteDictTypeApi(dictId: number) {
  return request.delete(`/system/dict/type/${dictId}`)
}

export function getDictDataListApi(params: { dictType?: string; dictLabel?: string; status?: string; pageNum?: number; pageSize?: number }) {
  return request.get<any, PageResult<SysDictData[]>>('/system/dict/data/list', { params })
}

export function addDictDataApi(data: SysDictData) {
  return request.post('/system/dict/data', data)
}

export function updateDictDataApi(data: SysDictData) {
  return request.put('/system/dict/data', data)
}

export function deleteDictDataApi(dictCode: number) {
  return request.delete(`/system/dict/data/${dictCode}`)
}
