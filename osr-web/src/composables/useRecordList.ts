import { ref, reactive, computed, onActivated } from 'vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { resetQueryParams } from '@/composables/queryParams'
import type { SearchParams, PageResult } from '@/types'

export interface RecordListConfig<TQuery extends SearchParams = SearchParams> {
  /** 获取列表 */
  listApi: (params: SearchParams) => Promise<any>
  /** 批量删除记录（单条删除复用它，传单元素数组） */
  batchDeleteApi: (ids: number[]) => Promise<any>
  /** 重试单条。不传则不提供重试能力（如重命名明细走自己的 execute） */
  retryApi?: (id: number) => Promise<any>
  /** 批量重试 */
  batchRetryApi?: (ids: number[]) => Promise<any>
  /** 从网盘删除文件（危险操作） */
  batchRemoveNetDiskApi?: (ids: number[]) => Promise<any>
  /** 主键字段名 */
  idField: string
  /** 确认弹窗里指代单条记录的字段（一般是文件名），缺省用 idField */
  labelField?: string
  /** 确认弹窗里的记录类型名，如「同步记录」 */
  recordLabel: string
  /** 除 pageNum/pageSize 外的默认查询字段 */
  defaultQuery?: Partial<TQuery>
}

/**
 * 记录类页面（同步记录 / STRM 记录 / 重命名明细）的通用列表逻辑，PC 与移动端共用。
 *
 * 与 useTaskList 的分工：task 页是 CRUD + 执行，record 页是查询 + 重试 / 删除，
 * 两者的表单与对话框差异过大，因此分开而不强行合并。
 */
export function useRecordList<TQuery extends SearchParams = SearchParams>(config: RecordListConfig<TQuery>) {
  const {
    listApi, batchDeleteApi, retryApi, batchRetryApi, batchRemoveNetDiskApi,
    idField, labelField, recordLabel, defaultQuery
  } = config

  // --- 列表 & 分页 ---
  const recordList = ref<any[]>([])
  const loading = ref(true)
  const total = ref(0)

  // 默认查询条件快照，重置时按它还原（见 resetQuery）
  const defaultQueryParams: SearchParams = {
    pageNum: 1,
    pageSize: 10,
    ...(defaultQuery || {})
  }

  const queryParams = reactive<SearchParams>({ ...defaultQueryParams }) as TQuery

  const totalPages = computed(() => Math.ceil(total.value / queryParams.pageSize) || 1)

  async function fetchList() {
    const res = await listApi(queryParams) as PageResult
    recordList.value = res.records || []
    total.value = res.total || 0
  }

  /** 是否已成功拉过一次列表，用于区分「首次进入」与「返回已缓存的页面」 */
  let loadedOnce = false

  const getList = async () => {
    loading.value = true
    try {
      await fetchList()
      loadedOnce = true
    } catch (e) {
      console.error(`[${recordLabel}] 列表加载失败:`, e)
      recordList.value = []
      total.value = 0
    } finally {
      loading.value = false
    }
  }

  /** 不打开 loading 遮罩地刷新，用于返回页面时避免闪烁 */
  const silentRefresh = async () => {
    try {
      await fetchList()
    } catch (e) {
      console.error(`[${recordLabel}] 刷新失败:`, e)
    }
  }

  // 页面被 keep-alive 缓存后，返回时组件不会重新挂载，数据会停在离开时的那一刻。
  // 这里静默拉一次最新数据：筛选条件与页码保持不变，只换列表内容。
  //
  // 判据用「是否已加载过数据」而不是「是否首次 activated」：路由组件经由
  // createDeviceView 的 defineAsyncComponent 异步加载，等它 setup 完时，KeepAlive
  // 早已在其直接子组件挂载时调度过 activated 钩子，因此首次挂载压根收不到 activated
  // （实测如此）。若按「跳过首次 activated」来写，反而会把返回时的第一次刷新吃掉。
  // 首次进入的数据由页面自己的 getList() 负责，此时 loadedOnce 尚为 false，不会重复请求。
  onActivated(() => {
    if (!loadedOnce) return
    silentRefresh()
  })

  const prevPage = () => {
    if (queryParams.pageNum > 1) {
      queryParams.pageNum--
      getList()
    }
  }

  const nextPage = () => {
    if (queryParams.pageNum < totalPages.value) {
      queryParams.pageNum++
      getList()
    }
  }

  const handleSizeChange = () => {
    queryParams.pageNum = 1
    getList()
  }

  // --- 搜索 ---
  const queryRef = ref<any>()
  const dateRange = ref<string[] | null>(null)

  /**
   * 开始 / 结束日期是两个独立的输入框，任一侧都可以单独填。
   * 写回时保持 [开始, 结束] 的数组形状，未填的一侧留空串，两侧都空则整体置 null。
   */
  function setRangeSide(index: 0 | 1, val: string) {
    const next: [string, string] = [dateRange.value?.[0] ?? '', dateRange.value?.[1] ?? '']
    next[index] = val || ''
    dateRange.value = (next[0] || next[1]) ? next : null
  }

  const dateStart = computed({
    get: () => dateRange.value?.[0] ?? '',
    set: (val: string) => setRangeSide(0, val)
  })
  const dateEnd = computed({
    get: () => dateRange.value?.[1] ?? '',
    set: (val: string) => setRangeSide(1, val)
  })

  const handleQuery = () => {
    queryParams.pageNum = 1
    // params 只存在于 SearchParams 的索引签名里，TS 不会把索引签名应用到泛型形参上，
    // 这里退回基类型来读写
    const qp = queryParams as SearchParams
    // 两侧各自判空后再拼时间：只填一边时按半开区间查询。
    // 不能因为「数组长度是 2」就两个都拼——空的那一侧会拼出 " 00:00:00" / " 23:59:59"，
    // 后端拿它去比 DATETIME 列，MySQL 直接报 Incorrect DATETIME value 变成 500。
    const begin = dateRange.value?.[0]
    const end = dateRange.value?.[1]
    if (begin || end) {
      const params: Record<string, string> = {}
      if (begin) params.beginTime = begin + ' 00:00:00'
      if (end) params.endTime = end + ' 23:59:59'
      qp.params = params
    } else {
      delete qp.params
    }
    getList()
  }

  const resetQuery = () => {
    dateRange.value = null
    resetQueryParams(queryParams, defaultQueryParams)
    // 值已由上面还原，这里只清校验态（v-form.reset() 会顺带把值清成 null，不能用）
    queryRef.value?.resetValidation?.()
    handleQuery()
  }

  // --- 选择 ---
  const selectedIds = ref<number[]>([])
  const multiple = computed(() => !selectedIds.value.length)

  const toggleSelect = (id: number) => {
    const idx = selectedIds.value.indexOf(id)
    if (idx > -1) {
      selectedIds.value.splice(idx, 1)
    } else {
      selectedIds.value.push(id)
    }
  }

  /** 移动端整卡点击切换选中；点在 checkbox 上时交给它自己的 change 处理，避免切换两次 */
  const handleCardClick = (event: Event, id: number) => {
    const target = event.target as HTMLElement
    if (target.closest('.card-checkbox')) return
    toggleSelect(id)
  }

  const clearSelection = () => {
    selectedIds.value = []
  }

  /** PC 端 v-data-table-server 的选择变化 */
  const handleSelectionChange = (selection: any[]) => {
    selectedIds.value = selection.map((item: any) => item[idField])
  }

  // --- 操作 ---
  function labelOf(row: any): string {
    return labelField ? row[labelField] : row[idField]
  }

  /** 统一的「确认 -> 调接口 -> 提示 -> 刷新」流程；用户取消时静默返回 */
  async function confirmThen(msg: string, title: string, type: 'warning' | 'error', action: () => Promise<any>, successMsg: string) {
    try {
      await confirm({ message: msg, title, type })
    } catch {
      return
    }
    try {
      await action()
      message.success(successMsg)
      getList()
    } catch (e) {
      console.error(`[${recordLabel}] ${successMsg}失败:`, e)
    }
  }

  const handleRetryOne = (row: any) =>
    confirmThen(
      `是否确认重试${recordLabel}"${labelOf(row)}"？`, '提示', 'warning',
      () => retryApi!(row[idField]), '重试成功'
    )

  const handleBatchRetry = () =>
    confirmThen(
      `是否确认批量重试选中的 ${selectedIds.value.length} 条记录？`, '提示', 'warning',
      () => batchRetryApi!(selectedIds.value), '批量重试成功'
    )

  const handleDeleteOne = (row: any) =>
    confirmThen(
      `是否确认删除${recordLabel}"${labelOf(row)}"？`, '警告', 'warning',
      () => batchDeleteApi([row[idField]]), '删除成功'
    )

  const handleBatchDelete = () =>
    confirmThen(
      `是否确认删除选中的 ${selectedIds.value.length} 条记录？`, '警告', 'warning',
      () => batchDeleteApi(selectedIds.value), '删除成功'
    )

  const handleRemoveNetDiskOne = (row: any) =>
    confirmThen(
      `危险操作：确认要从网盘中彻底删除该文件吗？`, '警告', 'error',
      () => batchRemoveNetDiskApi!([row[idField]]), '删除网盘文件成功'
    )

  const handleBatchRemoveNetDisk = () =>
    confirmThen(
      `危险操作：确认要从网盘中彻底删除选中的 ${selectedIds.value.length} 个文件吗？`, '警告', 'error',
      () => batchRemoveNetDiskApi!(selectedIds.value), '删除网盘文件成功'
    )

  return {
    // 列表 & 分页
    recordList, loading, total, queryParams, totalPages,
    getList, silentRefresh, prevPage, nextPage, handleSizeChange,
    // 搜索
    queryRef, dateRange, dateStart, dateEnd, handleQuery, resetQuery,
    // 选择
    selectedIds, multiple, toggleSelect, handleCardClick, clearSelection, handleSelectionChange,
    // 操作
    handleRetryOne, handleBatchRetry, handleDeleteOne, handleBatchDelete,
    handleRemoveNetDiskOne, handleBatchRemoveNetDisk
  }
}
