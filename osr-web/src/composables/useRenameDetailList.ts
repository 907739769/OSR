import { reactive, ref } from 'vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import type { VForm } from 'vuetify/components'
import { useRecordList } from './useRecordList'
import type { SearchParams } from '@/types'
import {
  getRenameDetailListApi,
  executeRenameDetailApi,
  batchDeleteRenameDetailApi,
  scrapeRenameDetailApi,
  batchScrapeRenameDetailApi,
  deleteScrapeFilesApi,
  batchDeleteScrapeFilesApi,
  previewPurgeApi,
  purgeRenameDetailApi
} from '@/api/openlist/renameDetail'

export type RenameDetailQuery = SearchParams & {
  originalName?: string
  newName?: string
  originalPath?: string
  newPath?: string
  title?: string
  status?: string
}

/**
 * 重命名明细页（PC + 移动端）共用逻辑。
 * 列表/分页/搜索/选择/删除是标准记录页逻辑，复用 useRecordList；
 * 重试改名（弹窗可编辑 title/year/season/episode 后重新执行）与刮削相关操作是本页特有能力，
 * useRecordList 未提供，在此扩展叠加。
 */
export function useRenameDetailList() {
  const {
    recordList, loading, total, queryParams, totalPages,
    getList, silentRefresh, prevPage, nextPage, handleSizeChange,
    queryRef, dateRange, dateStart, dateEnd, handleQuery, resetQuery,
    selectedIds, multiple, toggleSelect, handleCardClick, clearSelection, handleSelectionChange
    // 删除记录不用 useRecordList 的默认实现：它的确认文案只说"是否确认删除"，
    // 而这里"只删记录不删文件"的后果必须讲清楚，见下方 handleDeleteOne
  } = useRecordList<RenameDetailQuery>({
    listApi: getRenameDetailListApi,
    batchDeleteApi: batchDeleteRenameDetailApi,
    idField: 'id',
    labelField: 'newName',
    recordLabel: '重命名记录',
    defaultQuery: { status: undefined }
  })

  // --- 重试改名弹窗 ---
  const retryDialogVisible = ref(false)
  const retryLoading = ref(false)
  const retryFormRef = ref<InstanceType<typeof VForm>>()
  const retryForm = reactive({ id: 0, title: '', year: '', season: '', episode: '', mediaType: '' })

  const handleRetryOne = (row: any) => {
    retryForm.id = row.id
    retryForm.title = row.title || ''
    retryForm.year = row.year || ''
    retryForm.season = row.season || ''
    retryForm.episode = row.episode || ''
    retryForm.mediaType = row.mediaType || ''
    retryDialogVisible.value = true
  }

  const handleRetryClose = () => {
    retryFormRef.value?.reset()
  }

  const handleRetrySubmit = async () => {
    const result = await retryFormRef.value?.validate()
    if (result && !result.valid) return
    retryLoading.value = true
    try {
      await executeRenameDetailApi([retryForm.id], retryForm.title || undefined, retryForm.year || undefined, retryForm.season || undefined, retryForm.episode || undefined)
      message.success('编辑并重命名成功')
      retryDialogVisible.value = false
      getList()
    } catch (error: any) {
      message.error(error.message || '操作失败')
    } finally {
      retryLoading.value = false
    }
  }

  // --- 批量执行（改名重试）弹窗 ---
  // 与单条重试同一套后端逻辑（executeRenameDetails 支持 title/year 覆盖），
  // 但只需填标题和年份，便于批量修正刮削识别失败的记录
  const batchDialogVisible = ref(false)
  const batchLoading = ref(false)
  const batchFormRef = ref<InstanceType<typeof VForm>>()
  const batchForm = reactive({ title: '', year: '' })

  const handleBatchExecute = () => {
    if (!selectedIds.value.length) return
    batchForm.title = ''
    batchForm.year = ''
    batchDialogVisible.value = true
  }

  const handleBatchClose = () => {
    batchFormRef.value?.reset()
  }

  const handleBatchSubmit = async () => {
    const result = await batchFormRef.value?.validate()
    if (result && !result.valid) return
    batchLoading.value = true
    try {
      await executeRenameDetailApi(selectedIds.value, batchForm.title || undefined, batchForm.year || undefined)
      message.success('批量执行成功')
      batchDialogVisible.value = false
      getList()
    } catch (error: any) {
      message.error(error.message || '操作失败')
    } finally {
      batchLoading.value = false
    }
  }

  const handleScrapeOne = async (row: any) => {
    try {
      await confirm({ message: `是否确认对"${row.newName}"执行刮削？`, title: '提示', type: 'info' })
      await scrapeRenameDetailApi(row.id)
      message.success('刮削已启动')
      getList()
    } catch (e) { if (e !== 'cancel') console.error(e) }
  }

  const handleBatchScrape = async () => {
    try {
      await confirm({ message: `是否确认批量刮削选中的 ${selectedIds.value.length} 条记录？`, title: '提示', type: 'info' })
      await batchScrapeRenameDetailApi(selectedIds.value)
      message.success('批量刮削已启动')
      getList()
    } catch (e) { if (e !== 'cancel') console.error(e) }
  }

  const handleDeleteScrapeOne = async (row: any) => {
    try {
      await confirm({ message: `是否确认删除"${row.newName}"的刮削文件（NFO + 图片）？`, title: '删除刮削文件', type: 'warning' })
      await deleteScrapeFilesApi(row.id)
      message.success('刮削文件已删除')
      getList()
    } catch (e) { if (e !== 'cancel') console.error(e) }
  }

  const handleBatchDeleteScrape = async () => {
    try {
      await confirm({ message: `是否确认删除选中记录的刮削文件？`, title: '批量删除刮削', type: 'warning' })
      await batchDeleteScrapeFilesApi(selectedIds.value)
      message.success('刮削文件已删除')
      getList()
    } catch (e) { if (e !== 'cancel') console.error(e) }
  }

  // --- 只删记录 ---
  // 这是「失忆」操作：数据库行没了、文件全留着。后果必须写进确认框，
  // 否则用户会以为点完就干净了，实际上一致性检查从此看不见这些文件、
  // 手动执行任务时源文件还会被当成没处理过而重新复制一份。
  const RECORD_ONLY_WARNING =
    '只删除数据库记录，磁盘上的 STRM/视频、NFO 与图片都会保留。\n' +
    '删除后一致性检查将不再跟踪这些文件，且下次执行重命名任务时源文件会被重新处理一遍。\n' +
    '若要连文件一起清掉，请改用「清理产物」。'

  const deleteRecords = async (ids: number[], scopeText: string) => {
    try {
      await confirm({ message: `${scopeText}\n\n${RECORD_ONLY_WARNING}`, title: '仅删除记录', type: 'warning' })
      await batchDeleteRenameDetailApi(ids)
      message.success('记录已删除')
      getList()
    } catch (e) { if (e !== 'cancel') console.error(e) }
  }

  const handleDeleteOne = (row: any) => deleteRecords([row.id], `即将删除记录"${row.newName || row.originalName}"。`)
  const handleBatchDelete = () => deleteRecords(selectedIds.value, `即将删除选中的 ${selectedIds.value.length} 条记录。`)

  // --- 清理产物 ---
  // 先 dry-run 拉一份真实存在的文件清单给用户过目，再执行。
  // 预览与执行走同一份后端判定，清单里列出的就是真正会被删的。
  const purgeDialogVisible = ref(false)
  const purgeLoading = ref(false)
  const purgePreviewLoading = ref(false)
  const purgeFiles = ref<string[]>([])
  const purgeIds = ref<number[]>([])
  const purgeDeleteRecord = ref(true)

  const openPurgeDialog = async (ids: number[]) => {
    if (!ids.length) return
    purgeIds.value = ids
    purgeFiles.value = []
    purgeDeleteRecord.value = true
    purgeDialogVisible.value = true
    purgePreviewLoading.value = true
    try {
      purgeFiles.value = (await previewPurgeApi(ids)) || []
    } catch (error: any) {
      message.error(error.message || '获取清理清单失败')
      purgeDialogVisible.value = false
    } finally {
      purgePreviewLoading.value = false
    }
  }

  const handlePurgeOne = (row: any) => openPurgeDialog([row.id])
  const handleBatchPurge = () => openPurgeDialog(selectedIds.value)

  const handlePurgeSubmit = async () => {
    purgeLoading.value = true
    try {
      const summary = await purgeRenameDetailApi(purgeIds.value, purgeDeleteRecord.value)
      message.success(summary || '清理完成')
      purgeDialogVisible.value = false
      clearSelection()
      getList()
    } catch (error: any) {
      message.error(error.message || '清理失败')
    } finally {
      purgeLoading.value = false
    }
  }

  return {
    recordList, loading, total, queryParams, totalPages,
    getList, silentRefresh, prevPage, nextPage, handleSizeChange,
    queryRef, dateRange, dateStart, dateEnd, handleQuery, resetQuery,
    selectedIds, multiple, toggleSelect, handleCardClick, clearSelection, handleSelectionChange,
    handleDeleteOne, handleBatchDelete,
    retryDialogVisible, retryLoading, retryFormRef, retryForm,
    handleRetryOne, handleRetryClose, handleRetrySubmit,
    batchDialogVisible, batchLoading, batchFormRef, batchForm,
    handleBatchExecute, handleBatchClose, handleBatchSubmit,
    handleScrapeOne, handleBatchScrape,
    handleDeleteScrapeOne, handleBatchDeleteScrape,
    purgeDialogVisible, purgeLoading, purgePreviewLoading, purgeFiles, purgeDeleteRecord,
    handlePurgeOne, handleBatchPurge, handlePurgeSubmit
  }
}
