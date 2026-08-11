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
 * 孤儿原因的展示元数据，与后端 OrphanReason 一一对应，PC / 移动端共用一份。
 * 前两个是正向发现（有记录、文件没了），后三个是反向发现（有文件、没记录）。
 */
export const REASON_META: Record<string, { text: string; type: 'warning' | 'error' | 'info' }> = {
  local_missing: { text: '本地文件丢失', type: 'warning' },
  source_missing: { text: '网盘源丢失', type: 'error' },
  local_extra: { text: '无主媒体文件', type: 'warning' },
  metadata_only: { text: '仅剩元数据', type: 'error' },
  empty_dir: { text: '空目录', type: 'info' }
}

export const REASON_OPTIONS = Object.entries(REASON_META).map(([value, meta]) => ({ title: meta.text, value }))

/**
 * 每种 reason 的清理会删掉什么，逐条对齐后端 `RenameOrphanScanServiceImpl#cleanOne` 的分派分支。
 * 后端改了删除范围，这里必须跟着改——两边说的不是同一件事，比不给提示更糟。
 */
export const CLEAN_EFFECTS: Record<string, string> = {
  local_missing: '目标库里残留的刮削文件、回收空目录，以及这条重命名记录（本地主文件本来就已经不在了）',
  source_missing: '目标库产物（主文件 + 刮削文件）、这条重命名记录，'
    + '以及暂存目录里那个 .strm 中间产物和它在 STRM 记录表里的生成记录',
  local_extra: '这个媒体文件本身、它的同名 NFO，以及回收空目录',
  metadata_only: '该目录里剩下的元数据文件，并回收这个目录',
  empty_dir: '回收这个空目录'
}

/** 目录级发现（metadata_only / empty_dir）没有 newName，拼路径时不能带出一个尾斜杠 */
export const fullPath = (row: any) => (row?.newName ? `${row.newPath}/${row.newName}` : row?.newPath || '')

/**
 * 重命名一致性检查页（PC + 移动端）共用逻辑。
 * 列表/分页/搜索/选择/清理是标准记录页逻辑，复用 useRecordList（清理接到其 batchDeleteApi 插槽——
 * 清理本质上就是删除残留文件+记录，语义相符）；忽略、立即扫描是本页特有能力，在此扩展叠加。
 */
export function useRenameOrphanList() {
  const {
    recordList, loading, total, queryParams, totalPages,
    getList, silentRefresh, prevPage, nextPage, handleSizeChange,
    queryRef, dateRange, dateStart, dateEnd, handleQuery, resetQuery,
    selectedIds, multiple, toggleSelect, handleCardClick, clearSelection, handleSelectionChange,
    isAllPageSelected, toggleSelectAllPage
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

  // --- 清理 ---
  // 不复用 useRecordList 的通用删除文案（"是否确认删除孤儿记录xxx？"）：清理动作按 reason
  // 分派到完全不同的删除范围，而确认框是用户唯一的解释来源。尤其 source_missing 现在还会
  // 删掉暂存目录里的 .strm 中间产物，不写出来等于让用户在不知情的情况下同意了一次删除。
  const handleCleanOne = async (row: any) => {
    try {
      await confirm({
        message: `确认清理"${fullPath(row)}"？将删除：${CLEAN_EFFECTS[row?.reason] || '相关的残留文件与记录'}。`,
        title: '警告', type: 'warning'
      })
      await batchCleanRenameOrphanApi([row.id])
      message.success('清理成功')
      getList()
    } catch (e) { if (e !== 'cancel') console.error(e) }
  }

  const handleBatchClean = async () => {
    try {
      await confirm({
        message: `确认清理选中的 ${selectedIds.value.length} 条？将按各自的原因删除对应的残留文件与记录。`
          + '其中「网盘源丢失」会连暂存目录里的 .strm 中间产物一起删掉——不删它的话，'
          + '下一次重命名任务会把这条死链原样重新生成出来。',
        title: '警告', type: 'warning'
      })
      await batchCleanRenameOrphanApi(selectedIds.value)
      message.success('清理成功')
      getList()
    } catch (e) { if (e !== 'cancel') console.error(e) }
  }

  // --- 忽略 ---
  const handleIgnoreOne = async (row: any) => {
    try {
      await confirm({ message: `是否确认忽略"${fullPath(row)}"？忽略后不会自动清理，也不会再次提醒。`, title: '提示', type: 'warning' })
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
    queryRef, dateRange, dateStart, dateEnd, handleQuery, resetQuery,
    selectedIds, multiple, toggleSelect, handleCardClick, clearSelection, handleSelectionChange,
    isAllPageSelected, toggleSelectAllPage,
    handleCleanOne, handleBatchClean,
    scanning, handleScanNow,
    handleIgnoreOne, handleBatchIgnore
  }
}
