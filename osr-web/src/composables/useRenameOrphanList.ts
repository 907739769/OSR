import { ref } from 'vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { useRecordList } from './useRecordList'
import type { SearchParams } from '@/types'
import {
  getRenameOrphanListApi,
  scanRenameOrphanApi,
  batchCleanRenameOrphanApi,
  batchIgnoreRenameOrphanApi
} from '@/api/openlist/renameOrphan'

export type RenameOrphanQuery = SearchParams & {
  status?: string
  reason?: string
  title?: string
}

/**
 * 重命名一致性检查页（PC + 移动端）共用逻辑。
 * 列表/分页/搜索/选择/清理是标准记录页逻辑，复用 useRecordList（清理接到其 batchDeleteApi 插槽——
 * 清理本质上就是删除残留文件+记录，语义相符）；忽略、立即扫描是本页特有能力，在此扩展叠加。
 */
export function useRenameOrphanList() {
  const {
    recordList, loading, total, queryParams, totalPages,
    getList, silentRefresh, prevPage, nextPage, handleSizeChange,
    queryRef, dateRange, handleQuery, resetQuery,
    selectedIds, multiple, toggleSelect, handleCardClick, clearSelection, handleSelectionChange,
    handleDeleteOne: handleCleanOne, handleBatchDelete: handleBatchClean
  } = useRecordList<RenameOrphanQuery>({
    listApi: getRenameOrphanListApi,
    batchDeleteApi: batchCleanRenameOrphanApi,
    idField: 'id',
    labelField: 'newName',
    recordLabel: '孤儿记录',
    defaultQuery: { status: '0' }
  })

  // --- 立即扫描 ---
  const scanning = ref(false)
  const handleScanNow = async () => {
    scanning.value = true
    try {
      await scanRenameOrphanApi()
      message.success('扫描已在后台启动，请稍后刷新查看结果')
    } catch (error: any) {
      message.error(error.message || '触发扫描失败')
    } finally {
      scanning.value = false
    }
  }

  // --- 忽略 ---
  const handleIgnoreOne = async (row: any) => {
    try {
      await confirm({ message: `是否确认忽略"${row.newName}"？忽略后不会自动清理，也不会再次提醒。`, title: '提示', type: 'warning' })
      await batchIgnoreRenameOrphanApi([row.id])
      message.success('已忽略')
      getList()
    } catch (e) { if (e !== 'cancel') console.error(e) }
  }

  const handleBatchIgnore = async () => {
    try {
      await confirm({ message: `是否确认忽略选中的 ${selectedIds.value.length} 条记录？`, title: '提示', type: 'warning' })
      await batchIgnoreRenameOrphanApi(selectedIds.value)
      message.success('已忽略')
      getList()
    } catch (e) { if (e !== 'cancel') console.error(e) }
  }

  return {
    recordList, loading, total, queryParams, totalPages,
    getList, silentRefresh, prevPage, nextPage, handleSizeChange,
    queryRef, dateRange, handleQuery, resetQuery,
    selectedIds, multiple, toggleSelect, handleCardClick, clearSelection, handleSelectionChange,
    handleCleanOne, handleBatchClean,
    scanning, handleScanNow,
    handleIgnoreOne, handleBatchIgnore
  }
}
