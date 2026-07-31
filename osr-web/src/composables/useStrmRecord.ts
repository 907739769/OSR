import { useRecordList } from './useRecordList'
import {
  getStrmRecordListApi,
  retryStrmRecordApi,
  batchDeleteStrmRecordApi,
  batchRetryStrmRecordApi,
  batchRemoveStrmNetDiskApi
} from '@/api/openlist/strmRecord'
import type { SearchParams } from '@/types'

export interface StrmRecordQuery extends SearchParams {
  strmFileName?: string
  strmPath?: string
  strmStatus?: string
}

/**
 * STRM 记录共享 composable
 * PC 端和移动端共享列表、搜索、选择与重试 / 删除逻辑
 */
export function useStrmRecord() {
  return useRecordList<StrmRecordQuery>({
    listApi: getStrmRecordListApi,
    batchDeleteApi: batchDeleteStrmRecordApi,
    retryApi: retryStrmRecordApi,
    batchRetryApi: batchRetryStrmRecordApi,
    batchRemoveNetDiskApi: batchRemoveStrmNetDiskApi,
    idField: 'strmId',
    labelField: 'strmFileName',
    recordLabel: 'STRM记录',
    defaultQuery: {
      strmStatus: undefined
    }
  })
}
