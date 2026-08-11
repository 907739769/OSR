import { ref, reactive, computed } from 'vue'
import { useRoute } from 'vue-router'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import {
  getPtDownloadRecordListApi, retryPtDownloadRecordApi, batchRetryPtDownloadRecordApi,
  blacklistGuidApi, blacklistReleaseGroupApi,
  batchBlacklistGuidApi, batchBlacklistReleaseGroupApi
} from '@/api/openlist/ptDownloadRecord'
import type { BatchBlacklistResult, PtDownloadRecordQuery } from '@/api/openlist/ptDownloadRecord'
import { useRecordList } from './useRecordList'
import { usePtStatusSocket } from './usePtStatusSocket'
import type { ListLoadOptions } from './useGridPageSize'

/**
 * PT 下载记录 composable：只读列表 + 失败重试 + 拉黑，没有增删改。
 *
 * 列表/分页/搜索/选择底座复用 useRecordList（含 keep-alive 返回时的静默刷新）。
 * 重试与拉黑的交互跟 useRecordList 内置的通用流程不同——单条重试不弹确认、
 * 带逐行 loading 与「已推送/未搜到」的结果提示、批量重试带 pushedCount 统计——
 * 因此这几块保留自定义实现，不为复用而改变用户交互。
 */
export function usePtDownloadRecord(options: ListLoadOptions = {}) {
  const { autoLoad = true } = options
  const route = useRoute()

  // 支持从订阅页"下载记录"按钮带 subId 跳转过来，直接筛出该订阅的记录
  const initialSubId = route.query.subId ? Number(route.query.subId) : undefined

  const {
    recordList: taskList, loading, total, queryParams,
    totalPages, prevPage, nextPage, handleSizeChange,
    queryRef, handleQuery, resetQuery,
    selectedIds, toggleSelect, handleCardClick, clearSelection,
    isAllPageSelected, toggleSelectAllPage,
    getList
  } = useRecordList<PtDownloadRecordQuery>({
    listApi: getPtDownloadRecordListApi,
    // 下载记录不支持删除；useRecordList 的 batchDeleteApi 为必填项，页面不会解构
    // handleDeleteOne/handleBatchDelete，这里给显式报错的占位，误用即暴露
    batchDeleteApi: async () => { throw new Error('下载记录不支持删除操作') },
    idField: 'id',
    recordLabel: '下载记录',
    // 每页 12 条是移动端（单列）的值，也是 PC 量出列数之前的兜底；
    // PC 端挂载后由 useGridPageSize 按实际列数改成整行的条数。
    // 用 useRecordList 的默认 10 会多出半行空位，用户会误以为「没填满 = 没有下一页」
    defaultQuery: { subId: initialSubId, state: undefined, title: undefined, pageSize: 12 }
  })

  // ---------- 实时状态推送：状态/进度原地更新，不用整页刷新 ----------
  usePtStatusSocket({
    onDownload: (event) => {
      const row = taskList.value.find((item: any) => item.id === event.downloadId)
      if (row) {
        Object.assign(row, { state: event.state, progress: event.progress, failReason: event.failReason })
      }
    }
  })

  // ---------- 重试 ----------
  const retryingIds = reactive(new Set<number>())

  const handleRetry = async (row: any) => {
    retryingIds.add(row.id)
    try {
      const result = await retryPtDownloadRecordApi(row.id)
      message[result.pushed ? 'success' : 'info'](
        result.pushed ? '已重新找到并推送下载' : '重试未搜索到匹配资源'
      )
      getList()
    } catch (e) {
      console.error(e)
    } finally {
      retryingIds.delete(row.id)
    }
  }

  // ---------- 批量操作 ----------
  const selectionMode = ref(false)

  /** 退出批量模式时清掉选择集，免得下次进来还挂着上次的选中项 */
  const toggleSelectionMode = () => {
    selectionMode.value = !selectionMode.value
    if (!selectionMode.value) clearSelection()
  }

  /** 卡片选中（兼容原接口签名：入参是行对象，内部取 id） */
  const toggleRecordSelect = (row: any) => toggleSelect(row.id)

  /**
   * 选中项里真正能重试的那部分。
   * 拉黑对任何状态的记录都成立，重试只对 FAILED 成立——所以勾选放开到全部记录，
   * 由这里把范围收回来，按钮上直接标出生效条数，不让用户点完才发现大半被跳过。
   */
  const retryableSelectedIds = computed(() =>
    taskList.value
      .filter((item: any) => item.state === 'FAILED' && selectedIds.value.includes(item.id))
      .map((item: any) => item.id)
  )

  const handleBatchRetry = async () => {
    const ids = retryableSelectedIds.value
    if (!ids.length) {
      message.warning('选中的记录里没有失败记录，只有失败记录才能重试')
      return
    }
    try {
      await confirm({ message: `确认批量重试选中的 ${ids.length} 条失败记录？`, title: '提示', type: 'warning' })
      const result = await batchRetryPtDownloadRecordApi(ids)
      message.success(`已重新推送 ${result.pushedCount} 条，${result.skippedCount} 条未搜到或已跳过`)
      clearSelection()
      getList()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
    }
  }

  /** 批量拉黑结果提示：新增 / 已存在 / 失败三段，后两段为 0 时不啰嗦 */
  const formatBlacklistResult = (result: BatchBlacklistResult) => {
    const parts = [`已拉黑 ${result.addedCount} 项`]
    if (result.duplicateCount) parts.push(`${result.duplicateCount} 项已在黑名单中`)
    if (result.failedCount) parts.push(`${result.failedCount} 项未能拉黑`)
    return parts.join('，')
  }

  const runBatchBlacklist = async (
    confirmMessage: string,
    api: (ids: number[]) => Promise<BatchBlacklistResult>
  ) => {
    if (!selectedIds.value.length) return
    try {
      await confirm({ message: confirmMessage, title: '警告', type: 'warning' })
      const result = await api(selectedIds.value)
      message.success(formatBlacklistResult(result))
      clearSelection()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
    }
  }

  const handleBatchBlacklistGuid = () => runBatchBlacklist(
    `确认拉黑选中的 ${selectedIds.value.length} 个种子？拉黑后订阅不会再推送这些种子。`,
    batchBlacklistGuidApi
  )

  const handleBatchBlacklistReleaseGroup = () => runBatchBlacklist(
    `确认拉黑选中记录对应的发布组？该发布组之后的所有种子都不会再被推送（选中 ${selectedIds.value.length} 条，同组只会拉黑一次）。`,
    batchBlacklistReleaseGroupApi
  )

  // ---------- 拉黑 ----------
  const blacklistingIds = reactive(new Set<number>())

  const handleBlacklistGuid = async (row: any) => {
    blacklistingIds.add(row.id)
    try {
      const created = await blacklistGuidApi(row.id)
      message.success(created ? '已拉黑该种子' : '该种子已在黑名单中')
    } catch (e) {
      console.error(e)
    } finally {
      blacklistingIds.delete(row.id)
    }
  }

  const handleBlacklistReleaseGroup = async (row: any) => {
    blacklistingIds.add(row.id)
    try {
      const created = await blacklistReleaseGroupApi(row.id)
      message.success(created ? '已拉黑该发布组' : '该发布组已在黑名单中')
    } catch (e) {
      console.error(e)
    } finally {
      blacklistingIds.delete(row.id)
    }
  }

  // ---------- 移动端 - 搜索面板折叠 ----------
  const searchCollapsed = ref(true)

  if (autoLoad) getList()

  return {
    taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
    retryingIds, handleRetry,
    selectionMode, toggleSelectionMode, selectedIds, toggleRecordSelect, handleCardClick, clearSelection,
    isAllPageSelected, toggleSelectAllPage,
    retryableSelectedIds, handleBatchRetry,
    handleBatchBlacklistGuid, handleBatchBlacklistReleaseGroup,
    blacklistingIds, handleBlacklistGuid, handleBlacklistReleaseGroup,
    totalPages, prevPage, nextPage, handleSizeChange, searchCollapsed
  }
}
