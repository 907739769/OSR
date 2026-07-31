import { ref, computed } from 'vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { useTaskList } from './useTaskList'
import {
  getCopyTaskListApi,
  addCopyTaskApi,
  updateCopyTaskApi,
  deleteCopyTaskApi,
  batchDeleteCopyTaskApi,
  executeCopyTaskApi
} from '@/api/openlist/copyTask'
import type { SearchParams } from '@/types'

interface CopyTaskQuery extends SearchParams {
  copyTaskSrc?: string
  copyTaskDst?: string
  monitorDir?: string
  copyTaskStatus?: string
}

/**
 * 文件同步任务共享 composable
 * PC 端和移动端共享列表、CRUD、搜索逻辑
 */
export function useCopyTask() {
  const base = useTaskList<CopyTaskQuery>({
    listApi: getCopyTaskListApi,
    addApi: addCopyTaskApi,
    updateApi: updateCopyTaskApi,
    deleteApi: deleteCopyTaskApi,
    batchDeleteApi: batchDeleteCopyTaskApi,
    executeApi: executeCopyTaskApi,
    idField: 'copyTaskId',
    initForm: () => ({
      copyTaskId: undefined,
      copyTaskSrc: undefined,
      copyTaskDst: undefined,
      monitorDir: undefined,
      copyTaskStatus: '1'
    }),
    rules: {
      copyTaskSrc: [{ required: true, message: '源目录不能为空', trigger: 'blur' }],
      copyTaskDst: [{ required: true, message: '目标目录不能为空', trigger: 'blur' }]
    },
    defaultQuery: {
      copyTaskSrc: undefined,
      copyTaskDst: undefined,
      monitorDir: undefined,
      copyTaskStatus: undefined
    }
  })

  // 移动端 - 全选卡片逻辑
  const toggleSelect = (id: number) => {
    const idx = base.selectedIds.value.indexOf(id)
    if (idx > -1) {
      base.selectedIds.value.splice(idx, 1)
    } else {
      base.selectedIds.value.push(id)
    }
  }

  const handleCardClick = (event: Event, id: number) => {
    const target = event.target as HTMLElement
    if (target.closest('.card-checkbox')) return
    toggleSelect(id)
  }

  const clearSelection = () => {
    base.selectedIds.value = []
  }

  // 移动端 - 分页辅助
  const totalPages = computed(() => Math.ceil(base.total.value / base.queryParams.pageSize) || 1)

  const prevPage = () => {
    if (base.queryParams.pageNum > 1) {
      base.queryParams.pageNum--
      base.getList()
    }
  }

  const nextPage = () => {
    if (base.queryParams.pageNum < totalPages.value) {
      base.queryParams.pageNum++
      base.getList()
    }
  }

  const handleSizeChange = () => {
    base.queryParams.pageNum = 1
    base.getList()
  }

  // 移动端 - 搜索面板折叠
  const searchCollapsed = ref(true)

  // 批量执行
  const handleBatchExecute = async () => {
    try {
      await confirm({
        message: `是否确认批量执行选中的 ${base.selectedIds.value.length} 个同步任务？`,
        title: '提示', type: 'warning'
      })
      await executeCopyTaskApi(base.selectedIds.value)
      message.success('批量执行成功')
      base.getList()
    } catch (e) { if (e !== 'cancel') console.error(e) }
  }

  // 初始化加载
  base.getList()

  return {
    ...base,
    // 移动端卡片选择
    toggleSelect, handleCardClick, clearSelection,
    // 移动端分页
    totalPages, prevPage, nextPage, handleSizeChange,
    // 搜索面板
    searchCollapsed,
    // 批量执行
    handleBatchExecute
  }
}
