import { ref, computed } from 'vue'
import { useTaskList } from './useTaskList'
import {
  getRenameTaskListApi,
  addRenameTaskApi,
  updateRenameTaskApi,
  deleteRenameTaskApi,
  batchDeleteRenameTaskApi,
  executeRenameTaskApi
} from '@/api/openlist/renameTask'
import type { SearchParams } from '@/types'

export interface RenameTaskQuery extends SearchParams {
  sourceFolder?: string
  targetRoot?: string
  status?: string
}

/**
 * 重命名任务共享 composable
 * PC 端和移动端共享列表、CRUD、搜索逻辑
 */
export function useRenameTask() {
  const base = useTaskList<RenameTaskQuery>({
    listApi: getRenameTaskListApi,
    addApi: addRenameTaskApi,
    updateApi: updateRenameTaskApi,
    deleteApi: deleteRenameTaskApi,
    batchDeleteApi: batchDeleteRenameTaskApi,
    executeApi: executeRenameTaskApi,
    idField: 'id',
    initForm: () => ({
      id: undefined,
      sourceFolder: undefined,
      targetRoot: undefined,
      status: '1',
      scrapeEnabled: '0',
      scrapeNfo: '0',
      scrapeImages: '0',
      scrapeForceOverwrite: '0'
    }),
    rules: {
      sourceFolder: [{ required: true, message: '源目录不能为空', trigger: 'blur' }],
      targetRoot: [{ required: true, message: '目标目录不能为空', trigger: 'blur' }]
    },
    defaultQuery: {
      sourceFolder: undefined,
      targetRoot: undefined,
      status: undefined
    }
  })

  const handleAdd = () => base.handleAdd('新增重命名任务')
  const handleUpdate = (row?: any) => base.handleUpdate(row, '修改重命名任务')
  // 不传 row 时删除选中项（PC 端工具栏），此时沿用 useTaskList 的默认文案
  const handleDelete = (row?: any) =>
    base.handleDelete(row, row?.sourceFolder ? `是否确认删除重命名任务"${row.sourceFolder}"？` : undefined)
  const handleExecuteOne = (row: any) =>
    base.handleExecuteOne(row, `是否确认执行重命名任务"${row?.sourceFolder}"？`)
  const handleBatchExecute = () =>
    base.handleExecute(`是否确认批量执行选中的 ${base.selectedIds.value.length} 个重命名任务？`)

  // 移动端 - 卡片选择
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

  base.getList()

  return {
    ...base,
    handleAdd, handleUpdate, handleDelete, handleExecuteOne, handleBatchExecute,
    // 移动端卡片选择
    toggleSelect, handleCardClick, clearSelection,
    // 移动端分页
    totalPages, prevPage, nextPage, handleSizeChange,
    // 搜索面板
    searchCollapsed
  }
}
