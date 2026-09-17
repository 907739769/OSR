import { computed, watch } from 'vue'
import { useRecordList, formatFileSize } from './useRecordList'
import {
  getCopyRecordListApi,
  getCopyRecordStatsApi,
  retryCopyRecordApi,
  retryAllFailedCopyRecordApi,
  batchDeleteCopyRecordApi,
  batchRetryCopyRecordApi,
  batchRemoveCopyNetDiskApi
} from '@/api/openlist/copyRecord'
import type { SearchParams } from '@/types'
import type { RecordStatusOption } from '@/components/RecordStatusBar.vue'

export interface CopyRecordQuery extends SearchParams {
  copySrcPath?: string
  copyDstPath?: string
  copySrcFileName?: string
  copyDstFileName?: string
  copyTaskId?: string
  copyStatus?: string
}

/**
 * 状态 4 原先叫「未知」。它实际覆盖两种情况——监控超时、任务从 OpenList 消失且目标文件不存在——
 * 具体是哪种已经写在失败原因里，状态名只需要说清「这条出了问题、需要看一眼」。
 * 「未知」读起来像是系统自己也不清楚，用户会以为是显示出错而不是记录出错。
 */
export const COPY_STATUS_OPTIONS: RecordStatusOption[] = [
  { value: '1', title: '处理中', type: 'warning' },
  { value: '2', title: '失败', type: 'error' },
  { value: '4', title: '异常', type: 'error' },
  { value: '3', title: '成功', type: 'success' }
]

const STATUS_BY_VALUE = Object.fromEntries(COPY_STATUS_OPTIONS.map(o => [o.value, o]))

/**
 * 能按记录重试的状态：失败、监控超时/任务丢失。与后端 CopyServiceImpl#RETRYABLE_STATUSES 一致——
 * 处理中的由监控或兜底任务收尾，已成功的无事可做，对它们点重试后端会直接拒绝。
 */
const RETRYABLE_STATUSES = ['2', '4']

export const canRetryCopy = (status: string) => RETRYABLE_STATUSES.includes(status)

/**
 * 同步记录共享 composable
 * PC 端和移动端共享列表、搜索、选择与重试 / 删除逻辑
 */
export function useCopyRecord() {
  const base = useRecordList<CopyRecordQuery>({
    listApi: getCopyRecordListApi,
    statsApi: getCopyRecordStatsApi,
    batchDeleteApi: batchDeleteCopyRecordApi,
    retryApi: retryCopyRecordApi,
    retryFailedApi: retryAllFailedCopyRecordApi,
    batchRetryApi: batchRetryCopyRecordApi,
    batchRemoveNetDiskApi: batchRemoveCopyNetDiskApi,
    isInProgress: row => row.copyStatus === '1',
    idField: 'copyId',
    labelField: 'copySrcFileName',
    recordLabel: '同步记录',
    defaultQuery: {
      copyStatus: undefined
    }
  })

  const getCopyStatusText = (status: string) => STATUS_BY_VALUE[status]?.title || '未知'
  const getCopyStatusType = (status: string) => STATUS_BY_VALUE[status]?.type || 'info'

  // 选中集跨页累加，而大小只在看过的那几页数据里有：按 id 记下见过的大小
  const sizeById = new Map<number, number | null>()
  watch(base.recordList, rows => {
    for (const row of rows) sizeById.set(row.copyId, row.fileSize ?? null)
  }, { immediate: true })

  /**
   * 选中文件的合计大小。存量记录没有大小，合计就不完整——此时明说「其中 N 个大小未知」，
   * 不然 86 GB 会被读成全部选中项的总量。
   */
  const selectedSizeText = computed(() => {
    let sum = 0
    let known = 0
    for (const id of base.selectedIds.value) {
      const size = sizeById.get(id)
      if (size !== null && size !== undefined) {
        sum += size
        known++
      }
    }
    if (!known) return ''
    const unknown = base.selectedIds.value.length - known
    return unknown > 0
      ? `共 ${formatFileSize(sum)}（其中 ${unknown} 个大小未知）`
      : `共 ${formatFileSize(sum)}`
  })

  return { ...base, getCopyStatusText, getCopyStatusType, canRetryCopy, selectedSizeText }
}
