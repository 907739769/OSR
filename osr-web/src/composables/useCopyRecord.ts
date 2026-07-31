import { useRecordList } from './useRecordList'
import {
  getCopyRecordListApi,
  retryCopyRecordApi,
  batchDeleteCopyRecordApi,
  batchRetryCopyRecordApi,
  batchRemoveCopyNetDiskApi
} from '@/api/openlist/copyRecord'
import type { SearchParams } from '@/types'

export interface CopyRecordQuery extends SearchParams {
  copySrcPath?: string
  copyDstPath?: string
  copySrcFileName?: string
  copyDstFileName?: string
  copyStatus?: string
}

const STATUS_TEXT: Record<string, string> = {
  '1': '处理中',
  '2': '失败',
  '3': '成功',
  '4': '未知'
}

const STATUS_TYPE: Record<string, 'warning' | 'error' | 'success' | 'info'> = {
  '1': 'warning',
  '2': 'error',
  '3': 'success'
}

/**
 * 同步记录共享 composable
 * PC 端和移动端共享列表、搜索、选择与重试 / 删除逻辑
 */
export function useCopyRecord() {
  const base = useRecordList<CopyRecordQuery>({
    listApi: getCopyRecordListApi,
    batchDeleteApi: batchDeleteCopyRecordApi,
    retryApi: retryCopyRecordApi,
    batchRetryApi: batchRetryCopyRecordApi,
    batchRemoveNetDiskApi: batchRemoveCopyNetDiskApi,
    idField: 'copyId',
    labelField: 'copySrcFileName',
    recordLabel: '同步记录',
    defaultQuery: {
      copyStatus: undefined
    }
  })

  const getCopyStatusText = (status: string) => STATUS_TEXT[status] || '未知'
  const getCopyStatusType = (status: string) => STATUS_TYPE[status] || 'info'

  return { ...base, getCopyStatusText, getCopyStatusType }
}
