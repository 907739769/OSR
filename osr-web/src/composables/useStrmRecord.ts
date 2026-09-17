import { useRecordList } from './useRecordList'
import {
  getStrmRecordListApi,
  getStrmRecordStatsApi,
  retryStrmRecordApi,
  retryAllFailedStrmRecordApi,
  batchDeleteStrmRecordApi,
  batchRetryStrmRecordApi,
  batchRemoveStrmNetDiskApi
} from '@/api/openlist/strmRecord'
import type { SearchParams } from '@/types'
import type { RecordStatusOption } from '@/components/RecordStatusBar.vue'

export interface StrmRecordQuery extends SearchParams {
  strmFileName?: string
  strmPath?: string
  strmStatus?: string
  /** video / subtitle：后端按当前的视频/字幕扩展名配置筛选 */
  fileType?: string
}

export const STRM_STATUS_OPTIONS: RecordStatusOption[] = [
  { value: '0', title: '失败', type: 'error' },
  { value: '1', title: '成功', type: 'success' }
]

export const STRM_FILE_TYPE_OPTIONS = [
  { title: '视频', value: 'video' },
  { title: '字幕', value: 'subtitle' }
]

/**
 * 这张表里既有视频（生成 .strm）也有字幕（下载到本地），两者的图标与「重试」含义都不同。
 * 前端判类型只用来挑图标，按常见字幕扩展名判即可；筛选走后端，用的是真实的扩展名配置。
 */
const SUBTITLE_EXTENSIONS = ['srt', 'ass', 'ssa', 'vtt', 'sub', 'sup', 'idx', 'smi']

export const isSubtitleFile = (name: string | null | undefined) => {
  const ext = (name || '').split('.').pop()?.toLowerCase()
  return !!ext && SUBTITLE_EXTENSIONS.includes(ext)
}

/**
 * STRM 记录共享 composable
 * PC 端和移动端共享列表、搜索、选择与重试 / 删除逻辑
 */
export function useStrmRecord() {
  return useRecordList<StrmRecordQuery>({
    listApi: getStrmRecordListApi,
    statsApi: getStrmRecordStatsApi,
    batchDeleteApi: batchDeleteStrmRecordApi,
    retryApi: retryStrmRecordApi,
    retryFailedApi: retryAllFailedStrmRecordApi,
    batchRetryApi: batchRetryStrmRecordApi,
    batchRemoveNetDiskApi: batchRemoveStrmNetDiskApi,
    idField: 'strmId',
    labelField: 'strmFileName',
    recordLabel: 'STRM记录',
    defaultQuery: {
      strmStatus: undefined,
      fileType: undefined
    }
  })
}
