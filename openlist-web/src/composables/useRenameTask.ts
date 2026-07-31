import { ref, reactive, computed } from 'vue'
import { message } from '@/composables/useMessage'
import { useTaskList } from './useTaskList'
import {
  getRenameTaskListApi,
  addRenameTaskApi,
  updateRenameTaskApi,
  deleteRenameTaskApi,
  batchDeleteRenameTaskApi,
  executeRenameTaskApi,
  testParseRenameApi
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

  // 重命名测试：拿一个文件名试跑模板，看解析结果
  const testOpen = ref(false)
  const testTitle = ref('文件名重命名测试')
  const testLoading = ref(false)
  const testResult = ref<any>(null)
  const testForm = reactive({ filename: '', template: '' })

  /** 不针对具体任务的通用测试入口（PC 端工具栏） */
  const handleTest = () => {
    testTitle.value = '文件名重命名测试'
    testForm.filename = ''
    testForm.template = ''
    testResult.value = null
    testOpen.value = true
  }

  /** 针对某个任务的测试，带上它的源目录以示区分 */
  const handleTestOne = (row: any) => {
    handleTest()
    testTitle.value = `文件名重命名测试 - ${row.sourceFolder}`
  }

  const doTest = async () => {
    if (!testForm.filename.trim()) {
      message.warning('请输入文件名')
      return
    }
    testLoading.value = true
    try {
      testResult.value = await testParseRenameApi(testForm.filename, testForm.template || undefined) as any
      message.success('分析成功')
    } catch (e) {
      console.error('[重命名任务] 测试解析失败:', e)
      message.error('请求失败')
    } finally {
      testLoading.value = false
    }
  }

  base.getList()

  return {
    ...base,
    handleAdd, handleUpdate, handleDelete, handleExecuteOne, handleBatchExecute,
    // 移动端卡片选择
    toggleSelect, handleCardClick, clearSelection,
    // 移动端分页
    totalPages, prevPage, nextPage, handleSizeChange,
    // 搜索面板
    searchCollapsed,
    // 重命名测试
    testOpen, testTitle, testLoading, testResult, testForm, handleTest, handleTestOne, doTest
  }
}
