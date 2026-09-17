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

/**
 * 能按记录重试的状态：失败、监控超时/任务丢失。与后端 CopyServiceImpl#RETRYABLE_STATUSES 一致——
 * 处理中的由监控或兜底任务收尾，已成功的无事可做，对它们点重试后端会直接拒绝。
 */
const RETRYABLE_STATUSES = ['2', '4']

export const canRetryCopy = (status: string) => RETRYABLE_STATUSES.includes(status)

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

  return { ...base, getCopyStatusText, getCopyStatusType, canRetryCopy }
}
