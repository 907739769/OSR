import { ref, reactive, computed, watch } from 'vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { resetQueryParams } from '@/composables/queryParams'
import { usePageSelection } from '@/composables/usePageSelection'
import { useDebounce } from '@/composables/useDebounce'
import type { SearchParams, PageResult } from '@/types'

export interface TaskListApiConfig<TQuery extends SearchParams = SearchParams> {
  /** 获取列表 API */
  listApi: (params: SearchParams) => Promise<any>
  /** 新增 API */
  addApi: (data: any) => Promise<any>
  /** 修改 API */
  updateApi: (data: any) => Promise<any>
  /** 删除 API（单条） */
  deleteApi: (id: number) => Promise<any>
  /** 批量删除 API。不传则退化为逐条调用 deleteApi */
  batchDeleteApi?: (ids: number[]) => Promise<any>
  /** 执行 API。配置类页面（如 PT 索引器/下载器/媒体服务器）没有执行动作，可不传 */
  executeApi?: (ids: number[]) => Promise<any>
  /** ID 字段名 */
  idField: string
  /** 创建初始表单数据的函数 */
  initForm: () => any
  /** 表单校验规则 */
  rules: Record<string, any>
  /** 默认查询参数（除 pageNum/pageSize 外的搜索字段） */
  defaultQuery?: Partial<TQuery>
}

/**
 * 通用任务列表 CRUD composable
 * 适用于 strmTask、copyTask 等具有相同模式的页面
 */
export function useTaskList<TQuery extends SearchParams = SearchParams>(config: TaskListApiConfig<TQuery>) {
  const { listApi, addApi, updateApi, deleteApi, batchDeleteApi, executeApi, idField, initForm, rules, defaultQuery } = config

  // --- 列表 & 分页 ---
  const taskList = ref<any[]>([])
  const loading = ref(true)
  const total = ref(0)

  // 默认查询条件快照，重置时按它还原（见 resetQuery）
  const defaultQueryParams: SearchParams = {
    pageNum: 1,
    pageSize: 10,
    ...(defaultQuery || {})
  }

  const queryParams = reactive<SearchParams>({ ...defaultQueryParams }) as TQuery

  const getList = async () => {
    lastQuerySignature = filterSignature()
    loading.value = true
    try {
      const res = await listApi(queryParams) as PageResult
      taskList.value = res.records || []
      total.value = res.total || 0
    } finally {
      loading.value = false
    }
  }

  // --- 选择 ---
  const {
    selectedIds, toggleSelect, handleCardClick, clearSelection,
    isAllPageSelected, toggleSelectAllPage
  } = usePageSelection(taskList, idField)
  // 「未选中恰好一条 / 一条都没选」的按钮禁用态。派生自 selectedIds 而不是各处手动同步——
  // 早先卡片勾选是在每个业务 composable 里自己改这两个 ref 的，漏改一处就会出现
  // 「明明选中了，修改按钮还是灰的」
  const notOneSelected = computed(() => selectedIds.value.length !== 1)
  // 名字原本叫 multiple（RuoYi 遗留）：`:disabled="multiple"` 字面读作「多选时禁用」，
  // 实际是「一条都没选时禁用」，方向正好相反
  const noneSelected = computed(() => !selectedIds.value.length)

  const handleSelectionChange = (selection: any[]) => {
    selectedIds.value = selection.map((item: any) => item[idField])
  }

  // --- 搜索 ---
  const queryRef = ref<any>()

  const handleQuery = () => {
    queryParams.pageNum = 1
    getList()
  }

  // --- 输入即搜索 ---
  //
  // 改造前只有 3 个页面（同步记录/同步任务/STRM 任务）在页面里自己写了一份防抖 watch，
  // 另外 14 个必须点「搜索」——同一套界面两种反馈方式，用户会以为某些页面卡住了。
  // 放在这里而不是各页：所有列表 composable 都建立在 useTaskList / useRecordList 之上。
  //
  // 去重靠「上次真正发出去的条件」：handleQuery / resetQuery 会立即查一次，
  // 不比较的话 300ms 后 watcher 还会照着同样的条件再打一次，等于每次搜索发两个请求。
  // pageNum / pageSize 不参与——翻页不是筛选条件的变化，算进去会让翻页额外触发一次查询。
  const filterSignature = () => {
    const filters: Record<string, any> = {}
    for (const [key, value] of Object.entries(queryParams as Record<string, any>)) {
      if (key === 'pageNum' || key === 'pageSize') continue
      filters[key] = value
    }
    return JSON.stringify(filters)
  }

  let lastQuerySignature = ''
  const liveSearch = useDebounce(() => {
    if (filterSignature() === lastQuerySignature) return
    handleQuery()
  }, 300)
  watch(filterSignature, () => liveSearch())

  const resetQuery = () => {
    resetQueryParams(queryParams, defaultQueryParams)
    // 值已由上面还原，这里只清校验态（v-form.reset() 会顺带把值清成 null，不能用）
    queryRef.value?.resetValidation?.()
    handleQuery()
  }

  // --- 对话框 ---
  const open = ref(false)
  const dialogTitle = ref('')
  const submitLoading = ref(false)
  const formRef = ref<any>()
  const form = ref<any>(initForm())

  const handleAdd = (title: string) => {
    dialogTitle.value = title
    form.value = initForm()
    open.value = true
  }

  const handleUpdate = (row?: any, title?: string) => {
    const id = row?.[idField] || selectedIds.value[0]
    if (!id) {
      message.warning('请选择数据项')
      return
    }
    if (title) dialogTitle.value = title
    listApi({ ...queryParams, pageNum: 1, pageSize: 100 }).then((res: PageResult) => {
      const task = res.records.find((t: any) => t[idField] === id)
      if (task) {
        form.value = { ...task }
        open.value = true
      } else {
        message.error('任务不存在')
      }
    })
  }

  const submitForm = async () => {
    if (!formRef.value) return
    const result = await formRef.value.validate()
    if (result && typeof result === 'object' && 'valid' in result && !result.valid) return
    submitLoading.value = true
    try {
      if (form.value[idField]) {
        await updateApi(form.value)
        message.success('修改成功')
      } else {
        await addApi(form.value)
        message.success('新增成功')
      }
      open.value = false
      getList()
    } finally {
      submitLoading.value = false
    }
  }

  // --- 删除 ---
  const handleDelete = async (row?: any, confirmMsg?: string) => {
    const id = row?.[idField]
    if (!id) {
      // PC 端批量删除
      const ids = selectedIds.value
      if (!ids.length) return
      try {
        await confirm({ message: confirmMsg || `是否确认删除编号为"${ids}"的数据项？`, title: '警告', type: 'warning' })
        // 曾经这里是 deleteApi(ids[0])：选中多条时只会删掉第一条，却照样提示删除成功
        if (batchDeleteApi) {
          await batchDeleteApi(ids)
        } else {
          await Promise.all(ids.map(id => deleteApi(id)))
        }
        message.success('删除成功')
        getList()
      } catch (e) { if (e !== 'cancel') console.error(e) }
      return
    }
    try {
      await confirm({ message: confirmMsg || `是否确认删除该数据项？`, title: '警告', type: 'warning' })
      await deleteApi(id)
      message.success('删除成功')
      getList()
    } catch (e) { if (e !== 'cancel') console.error(e) }
  }

  // --- 执行 ---
  const handleExecuteOne = async (row: any, confirmMsg: string) => {
    if (!executeApi) {
      message.warning('该列表不支持执行操作')
      return
    }
    try {
      await confirm({ message: confirmMsg, title: '提示', type: 'warning' })
      await executeApi([row[idField]])
      message.success('执行成功')
      getList()
    } catch (e) { if (e !== 'cancel') console.error(e) }
  }

  const handleExecute = async (confirmMsg: string) => {
    if (!executeApi) {
      message.warning('该列表不支持执行操作')
      return
    }
    try {
      await confirm({ message: confirmMsg, title: '警告', type: 'warning' })
      await executeApi(selectedIds.value)
      message.success('执行成功')
      getList()
    } catch (e) { if (e !== 'cancel') console.error(e) }
  }

  return {
    // 列表 & 分页
    taskList, loading, total, queryParams,
    getList, handleQuery, resetQuery, queryRef,
    // 选择
    selectedIds, notOneSelected, noneSelected, handleSelectionChange,
    toggleSelect, handleCardClick, clearSelection,
    isAllPageSelected, toggleSelectAllPage,
    // 对话框
    open, dialogTitle, submitLoading, formRef, form, rules,
    handleAdd, handleUpdate, submitForm,
    // 删除 & 执行
    handleDelete, handleExecuteOne, handleExecute
  }
}
