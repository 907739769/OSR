import { ref, reactive, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { useDebounce } from '@/composables/useDebounce'
import {
  getPtDownloadRecordListApi, getPtDownloadRecordStatsApi,
  retryPtDownloadRecordApi, batchRetryPtDownloadRecordApi, batchIgnorePtDownloadRecordApi,
  blacklistGuidApi, blacklistReleaseGroupApi,
  batchBlacklistGuidApi, batchBlacklistReleaseGroupApi,
  previewCleanupPtDownloadRecordApi, cleanupPtDownloadRecordApi, CLEANUP_DAY_OPTIONS
} from '@/api/openlist/ptDownloadRecord'
import type { BatchBlacklistResult, DownloadRecordDateField, PtDownloadRecordQuery, PtDownloadRecordView } from '@/api/openlist/ptDownloadRecord'
import type { SearchParams } from '@/types'
import { getPtIndexerListApi } from '@/api/openlist/ptIndexer'
import { getPtDownloaderListApi } from '@/api/openlist/ptDownloader'
import { useRecordList } from './useRecordList'
import { usePtStatusSocket } from './usePtStatusSocket'
import { canIgnore, canRetry } from './ptDownloadRecordLabels'
import type { ListLoadOptions } from './useGridPageSize'

/**
 * PT 下载记录 composable：只读列表 + 失败重试 + 拉黑 + 按规则清理旧记录，没有单条增删改。
 *
 * 列表/分页/搜索/选择/统计条底座复用 useRecordList（含 keep-alive 返回时的静默刷新）。
 * 重试与拉黑的交互跟 useRecordList 内置的通用流程不同——单条重试不弹确认、
 * 带逐行 loading 与「已推送/未搜到」的结果提示、批量重试转后台后靠 WebSocket 回报结果、
 * 拉黑要能填原因——因此这几块保留自定义实现，不为复用而改变用户交互。
 */
export function usePtDownloadRecord(options: ListLoadOptions = {}) {
  const { autoLoad = true } = options
  const route = useRoute()
  const router = useRouter()

  // 支持两处带筛选跳过来：订阅页的「下载记录」按钮带 subId（+ subTitle，筛选条上才写得出剧名——
  // 列表为空时从记录里是取不到的），统计仪表盘的下钻带状态 / 失败分类 / H&R / 日期区间
  const ownPath = route.path
  let initialSubTitle = typeof route.query.subTitle === 'string' ? route.query.subTitle : undefined
  let initialSubId = route.query.subId ? Number(route.query.subId) : undefined

  const {
    recordList: taskList, loading, total, queryParams, stats,
    totalPages, prevPage, nextPage, handleSizeChange,
    queryRef, dateStart, dateEnd, handleQuery, resetQuery: resetBaseQuery,
    selectedIds, toggleSelect, handleCardClick, clearSelection,
    isAllPageSelected, toggleSelectAllPage,
    getList, silentRefresh
  } = useRecordList<PtDownloadRecordQuery>({
    listApi: getPtDownloadRecordListApi,
    statsApi: getPtDownloadRecordStatsApi,
    // 下载记录不支持单条删除；useRecordList 的 batchDeleteApi 为必填项，页面不会解构
    // handleDeleteOne/handleBatchDelete，这里给显式报错的占位，误用即暴露
    batchDeleteApi: async () => { throw new Error('下载记录不支持删除操作') },
    idField: 'id',
    recordLabel: '下载记录',
    // 每页 12 条是移动端（单列）的值，也是 PC 量出列数之前的兜底；
    // PC 端挂载后由 useGridPageSize 按实际列数改成整行的条数。
    // 用 useRecordList 的默认 10 会多出半行空位，用户会误以为「没填满 = 没有下一页」。
    //
    // subId 刻意不放进默认值：放进去的话「重置」会把它还原回来，用户从订阅页跳过来之后
    // 就再也回不到全部记录了。它在下面单独写入，重置时按默认值清掉
    defaultQuery: {
      subId: undefined, state: undefined, title: undefined,
      failReasonCode: undefined, indexerId: undefined, downloaderId: undefined, hrState: undefined,
      hideSuperseded: undefined, hideIgnored: undefined, dateField: undefined,
      pageSize: 12
    }
  })

  // ---------- 路由带进来的筛选 ----------

  /** 地址栏里认的筛选键，与 usePtStatsNavigation#downloadRecordLocation 写出的一致 */
  const ROUTE_FILTER_KEYS = ['subId', 'subTitle', 'state', 'failReasonCode', 'hrState', 'dateField', 'beginDate', 'endDate', 'hideSuperseded', 'hideIgnored'] as const

  const hasRouteFilters = (query: Record<string, unknown>) => ROUTE_FILTER_KEYS.some(k => query[k] !== undefined)

  /**
   * 每带筛选跳进来一次加一，页面据此展开搜索区：统计页点进来的条件（日期区间、隐藏已接替）
   * 都在搜索区里，搜索区默认是收起的，不展开的话用户只会觉得「怎么才这几条」。
   * 用计数而不是布尔量：keep-alive 下第二次跳进来时布尔量已经是 true，页面 watch 不到变化
   */
  const routeFilterTick = ref(0)

  /**
   * 把地址栏的筛选写进查询条件。带筛选跳进来是一次「新的查看意图」，上一次手动留下的
   * 标题 / 索引器 / 下载器条件一并清掉，否则两组条件叠在一起，看到的条数与点进来的数字对不上
   */
  const applyRouteFilters = (query: Record<string, unknown>) => {
    const str = (k: string) => (typeof query[k] === 'string' && query[k] !== '' ? (query[k] as string) : undefined)
    const qp = queryParams as SearchParams
    const subId = str('subId') ? Number(str('subId')) : undefined
    initialSubId = subId
    initialSubTitle = str('subTitle')
    Object.assign(queryParams, {
      subId,
      state: str('state'),
      failReasonCode: str('failReasonCode'),
      hrState: str('hrState'),
      dateField: str('dateField') as DownloadRecordDateField | undefined,
      hideSuperseded: str('hideSuperseded') === '1' ? true : undefined,
      hideIgnored: str('hideIgnored') === '1' ? true : undefined,
      title: undefined,
      indexerId: undefined,
      downloaderId: undefined,
      pageNum: 1
    })
    const begin = str('beginDate') ?? ''
    const end = str('endDate') ?? ''
    dateStart.value = begin
    dateEnd.value = end
    // 首次加载由 useGridPageSize 直接调 getList（不经过 handleQuery），日期要先落成 params
    const params: Record<string, string> = {}
    if (begin) params.beginTime = begin + ' 00:00:00'
    if (end) params.endTime = end + ' 23:59:59'
    if (begin || end) qp.params = params
    else delete qp.params
    routeFilterTick.value++
  }

  if (hasRouteFilters(route.query)) applyRouteFilters(route.query)

  // 列表页开了 keep-alive：从统计页第二次点进来时组件不重新创建，setup 不会再跑，
  // 只能盯着路由变化重新应用。只认本页路径、且带了筛选键的那次——从菜单直接进来（没带参数）
  // 保留用户上次的筛选，dropRouteQuery 抹掉参数引起的那次变化也不带筛选键，自然被忽略
  watch(() => route.fullPath, () => {
    if (route.path !== ownPath || !hasRouteFilters(route.query)) return
    applyRouteFilters(route.query)
    handleQuery()
  })

  // ---------- 订阅筛选 ----------
  // 搜索区没有「订阅」这个控件，路由带进来的 subId 不做成一条看得见、关得掉的筛选条的话，
  // 用户只会觉得「下载记录怎么这么少」

  const subFilterLabel = computed(() => {
    const subId = queryParams.subId
    if (!subId) return ''
    const title = (subId === initialSubId ? initialSubTitle : undefined)
      ?? taskList.value.find((r: PtDownloadRecordView) => r.subId === subId)?.subTitle
    return title ? `《${title}》` : `订阅 #${subId}`
  })

  /** 把地址栏里的筛选键一起去掉，否则刷新页面又筛回去了 */
  const dropRouteQuery = (keys: readonly string[] = ROUTE_FILTER_KEYS) => {
    if (!keys.some(k => route.query[k] !== undefined)) return
    const rest = { ...route.query }
    keys.forEach(k => delete rest[k])
    router.replace({ query: rest })
  }

  const clearSubFilter = () => {
    queryParams.subId = undefined
    dropRouteQuery(['subId', 'subTitle'])
    handleQuery()
  }

  const resetQuery = () => {
    dropRouteQuery()
    resetBaseQuery()
  }

  // ---------- 筛选项：索引器 / 下载器 ----------
  const indexerOptions = ref<{ title: string, value: number }[]>([])
  const downloaderOptions = ref<{ title: string, value: number }[]>([])

  /** 下拉数据只拉一次；拉不到只是少了两个筛选项，不影响列表 */
  const loadFilterOptions = async () => {
    const toOptions = (res: any) => (res?.records ?? []).map((r: any) => ({ title: r.name, value: r.id }))
    const [indexers, downloaders] = await Promise.allSettled([
      getPtIndexerListApi({ pageNum: 1, pageSize: 200 }),
      getPtDownloaderListApi({ pageNum: 1, pageSize: 200 })
    ])
    if (indexers.status === 'fulfilled') indexerOptions.value = toOptions(indexers.value)
    if (downloaders.status === 'fulfilled') downloaderOptions.value = toOptions(downloaders.value)
  }

  // ---------- 实时状态推送：状态/进度原地更新，不用整页刷新 ----------

  // 状态一变，统计条的数字和「当前筛选下还该不该出现这一行」就都变了。原地改完先给即时反馈，
  // 再静默刷一次把计数与分页对齐；批量完成时几十条事件挤在一起，合并成一次
  const refreshAfterStateChange = useDebounce(() => silentRefresh(), 1500)

  /** 后台批量重试的 batchId：WebSocket 是全体广播，只认自己发起的那几批 */
  const pendingBatchIds = new Set<string>()

  usePtStatusSocket({
    onDownload: (event) => {
      const index = taskList.value.findIndex((item: PtDownloadRecordView) => item.id === event.downloadId)
      if (index < 0) return
      const row = taskList.value[index]
      const stateChanged = row.state !== event.state
      Object.assign(row, { state: event.state, progress: event.progress, failReason: event.failReason })
      if (event.failReasonCode !== undefined) row.failReasonCode = event.failReasonCode
      if (event.completedTime !== undefined) row.completedTime = event.completedTime
      if (event.hrState !== undefined) row.hrState = event.hrState
      if (!stateChanged) return
      // 按状态筛选时，变成别的状态的那条已经不属于这个列表了：留着的话「只看下载中」里会混进已完成
      if (queryParams.state && queryParams.state !== event.state) {
        taskList.value.splice(index, 1)
        total.value = Math.max(0, total.value - 1)
      }
      refreshAfterStateChange()
    },
    onBatchRetry: (event) => {
      if (!pendingBatchIds.delete(event.batchId)) return
      message.success(`批量重试完成：重新推送 ${event.pushedCount} 条，${event.skippedCount} 条未搜到或已跳过`)
      silentRefresh()
    }
  })

  // ---------- 重试 ----------
  const retryingIds = reactive(new Set<number>())

  const handleRetry = async (row: PtDownloadRecordView) => {
    retryingIds.add(row.id)
    try {
      const result = await retryPtDownloadRecordApi(row.id)
      message[result.pushed ? 'success' : 'info'](
        result.pushed ? '已重新找到并推送下载' : (result.reason || '重试未搜索到匹配资源')
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
  const toggleRecordSelect = (row: PtDownloadRecordView) => toggleSelect(row.id)

  /**
   * 选中项里真正能重试的那部分。
   * 拉黑对任何状态的记录都成立，重试只对「还没被接替的 FAILED」成立——所以勾选放开到全部记录，
   * 由这里把范围收回来，按钮上直接标出生效条数，不让用户点完才发现大半被跳过。
   */
  const retryableSelectedIds = computed(() =>
    taskList.value
      .filter((item: PtDownloadRecordView) => canRetry(item) && selectedIds.value.includes(item.id))
      .map((item: PtDownloadRecordView) => item.id)
  )

  /**
   * 批量重试转后台：每条都要真的打一轮索引器，同步等的话几条就超过请求超时，
   * 页面报错而后端还在推送。接口立即返回 batchId，跑完由 WebSocket 回报结果。
   */
  const handleBatchRetry = async () => {
    const ids = retryableSelectedIds.value
    if (!ids.length) {
      message.warning('选中的记录里没有可重试的失败记录')
      return
    }
    try {
      await confirm({ message: `确认批量重试选中的 ${ids.length} 条失败记录？`, title: '提示', type: 'warning' })
      const { batchId, accepted } = await batchRetryPtDownloadRecordApi(ids)
      pendingBatchIds.add(batchId)
      const skipped = ids.length - accepted
      message.success(`已在后台重试 ${accepted} 条失败记录，完成后会提示结果`
        + (skipped > 0 ? `（${skipped} 条无权操作，已跳过）` : ''))
      clearSelection()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
    }
  }

  // ---------- 忽略失败 ----------
  // 有些失败看过之后决定不管了（不要这一集了、已从别处补上），但它永远不会被接替，
  // 首页「下载失败待处理」就一直挂着。忽略只把它从待办里拿掉，订阅那一集的补搜照常

  const ignoringIds = reactive(new Set<number>())

  /** 改完原地更新标记；正在「隐藏已忽略」时刚忽略的那条已不属于这个列表，当场移除 */
  const applyIgnored = (ids: number[], ignored: boolean) => {
    const idSet = new Set(ids)
    taskList.value.forEach((item: PtDownloadRecordView) => {
      if (idSet.has(item.id) && item.state === 'FAILED') item.failIgnored = ignored
    })
    if (ignored && queryParams.hideIgnored) {
      const before = taskList.value.length
      taskList.value = taskList.value.filter((item: PtDownloadRecordView) => !(idSet.has(item.id) && item.failIgnored))
      total.value = Math.max(0, total.value - (before - taskList.value.length))
    }
    refreshAfterStateChange()
  }

  const handleIgnore = async (row: PtDownloadRecordView, ignored = true) => {
    ignoringIds.add(row.id)
    try {
      await batchIgnorePtDownloadRecordApi([row.id], ignored)
      message.success(ignored ? '已忽略，不再计入首页待办；这一集的自动补搜不受影响' : '已取消忽略')
      applyIgnored([row.id], ignored)
    } catch (e) {
      console.error(e)
    } finally {
      ignoringIds.delete(row.id)
    }
  }

  /** 选中项里能忽略的部分，按钮上标出生效条数，口径同批量重试 */
  const ignorableSelectedIds = computed(() =>
    taskList.value
      .filter((item: PtDownloadRecordView) => canIgnore(item) && selectedIds.value.includes(item.id))
      .map((item: PtDownloadRecordView) => item.id)
  )

  const handleBatchIgnore = async () => {
    const ids = ignorableSelectedIds.value
    if (!ids.length) {
      message.warning('选中的记录里没有可忽略的失败记录')
      return
    }
    try {
      await confirm({
        message: `确认忽略选中的 ${ids.length} 条失败记录？忽略后不再计入首页待办，可随时取消忽略；订阅的自动补搜不受影响。`,
        title: '提示',
        type: 'warning'
      })
      const changed = await batchIgnorePtDownloadRecordApi(ids, true)
      message.success(`已忽略 ${changed} 条失败记录`)
      applyIgnored(ids, true)
      clearSelection()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
    }
  }

  // ---------- 拉黑（单条与批量共用一个弹窗，都能填原因） ----------

  type BlacklistKind = 'guid' | 'group'

  const blacklistDialog = reactive({
    visible: false,
    kind: 'guid' as BlacklistKind,
    /** 单条拉黑的目标；批量时为 null，取当时的选中项 */
    row: null as PtDownloadRecordView | null,
    ids: [] as number[],
    title: '',
    message: '',
    reason: '',
    submitting: false
  })

  const openBlacklist = (kind: BlacklistKind, row: PtDownloadRecordView | null, title: string, text: string) => {
    Object.assign(blacklistDialog, {
      visible: true, kind, row, title, message: text, reason: '', submitting: false,
      ids: row ? [row.id] : [...selectedIds.value]
    })
  }

  const handleBlacklistGuid = (row: PtDownloadRecordView) => openBlacklist('guid', row, '拉黑种子',
    `拉黑种子「${row.title}」？之后任何订阅都不会再推送这个种子，可在「种子黑名单」页撤销。`)

  // 发布组的影响面比单个种子大得多（该组今后的所有种子、所有订阅），原先点一下就生效，没有任何确认
  const handleBlacklistReleaseGroup = (row: PtDownloadRecordView) => openBlacklist('group', row, '拉黑发布组',
    `拉黑发布组「${row.releaseGroup ?? '未识别'}」？该发布组今后的所有种子都不会再被推送（对所有订阅生效），可在「种子黑名单」页撤销。`)

  const handleBatchBlacklistGuid = () => {
    if (!selectedIds.value.length) return
    openBlacklist('guid', null, '批量拉黑种子',
      `确认拉黑选中的 ${selectedIds.value.length} 个种子？拉黑后订阅不会再推送这些种子。`)
  }

  const handleBatchBlacklistReleaseGroup = () => {
    if (!selectedIds.value.length) return
    openBlacklist('group', null, '批量拉黑发布组',
      `确认拉黑选中记录对应的发布组？该发布组之后的所有种子都不会再被推送（选中 ${selectedIds.value.length} 条，同组只会拉黑一次）。`)
  }

  /** 批量拉黑结果提示：新增 / 已存在 / 失败三段，后两段为 0 时不啰嗦 */
  const formatBlacklistResult = (result: BatchBlacklistResult) => {
    const parts = [`已拉黑 ${result.addedCount} 项`]
    if (result.duplicateCount) parts.push(`${result.duplicateCount} 项已在黑名单中`)
    if (result.failedCount) parts.push(`${result.failedCount} 项未能拉黑`)
    return parts.join('，')
  }

  /** 单条拉黑成功后原地改标记：发布组是按组生效的，本页同组的记录一起标上 */
  const markBlacklisted = (kind: BlacklistKind, row: PtDownloadRecordView) => {
    if (kind === 'guid') {
      row.guidBlacklisted = true
      return
    }
    const group = row.releaseGroup?.toUpperCase()
    taskList.value.forEach((item: PtDownloadRecordView) => {
      if (group && item.releaseGroup?.toUpperCase() === group) item.releaseGroupBlacklisted = true
    })
  }

  const submitBlacklist = async () => {
    const { kind, row, ids } = blacklistDialog
    const reason = blacklistDialog.reason.trim() || undefined
    blacklistDialog.submitting = true
    try {
      if (row) {
        const created = await (kind === 'guid' ? blacklistGuidApi : blacklistReleaseGroupApi)(row.id, reason)
        const what = kind === 'guid' ? '该种子' : '该发布组'
        message.success(created ? `已拉黑${what}` : `${what}已在黑名单中`)
        markBlacklisted(kind, row)
      } else {
        const result = await (kind === 'guid' ? batchBlacklistGuidApi : batchBlacklistReleaseGroupApi)(ids, reason)
        message.success(formatBlacklistResult(result))
        clearSelection()
        silentRefresh()
      }
      blacklistDialog.visible = false
    } catch (e) {
      console.error(e)
    } finally {
      blacklistDialog.submitting = false
    }
  }

  // ---------- 复制种子 hash ----------
  const copyTorrentHash = async (row: PtDownloadRecordView) => {
    if (!row.torrentHash) return
    try {
      await navigator.clipboard.writeText(row.torrentHash)
      message.success('已复制种子 hash，可到下载器里搜索这个任务')
    } catch {
      // 局域网 http:// 部署时 clipboard API 不可用（只在安全上下文里开放），明说而不是静默失败
      message.warning(`当前环境不支持自动复制，请手动复制：${row.torrentHash}`)
    }
  }

  // ---------- 清理旧记录（仅管理员，后端判） ----------
  const cleanupDialog = reactive({
    visible: false,
    days: 180,
    /** 预览条数；null 表示还没拉到或拉取失败（比如非管理员） */
    count: null as number | null,
    loading: false,
    submitting: false
  })

  const loadCleanupPreview = async () => {
    cleanupDialog.loading = true
    cleanupDialog.count = null
    try {
      cleanupDialog.count = await previewCleanupPtDownloadRecordApi(cleanupDialog.days)
    } catch (e) {
      console.error(e)
    } finally {
      cleanupDialog.loading = false
    }
  }

  const openCleanup = () => {
    cleanupDialog.visible = true
    loadCleanupPreview()
  }

  watch(() => cleanupDialog.days, () => {
    if (cleanupDialog.visible) loadCleanupPreview()
  })

  const submitCleanup = async () => {
    if (!cleanupDialog.count) return
    cleanupDialog.submitting = true
    try {
      const removed = await cleanupPtDownloadRecordApi(cleanupDialog.days)
      message.success(`已清理 ${removed} 条下载记录`)
      cleanupDialog.visible = false
      getList()
    } catch (e) {
      console.error(e)
    } finally {
      cleanupDialog.submitting = false
    }
  }

  // ---------- 移动端 - 搜索面板折叠 ----------
  const searchCollapsed = ref(true)

  loadFilterOptions()
  if (autoLoad) getList()

  return {
    taskList, loading, total, queryParams, stats, getList, handleQuery, resetQuery, queryRef,
    dateStart, dateEnd, indexerOptions, downloaderOptions,
    subFilterLabel, clearSubFilter, routeFilterTick,
    retryingIds, handleRetry,
    selectionMode, toggleSelectionMode, selectedIds, toggleRecordSelect, handleCardClick, clearSelection,
    isAllPageSelected, toggleSelectAllPage,
    retryableSelectedIds, handleBatchRetry,
    ignoringIds, handleIgnore, ignorableSelectedIds, handleBatchIgnore,
    handleBatchBlacklistGuid, handleBatchBlacklistReleaseGroup,
    handleBlacklistGuid, handleBlacklistReleaseGroup, blacklistDialog, submitBlacklist,
    copyTorrentHash,
    cleanupDialog, cleanupDayOptions: CLEANUP_DAY_OPTIONS, openCleanup, submitCleanup,
    totalPages, prevPage, nextPage, handleSizeChange, searchCollapsed
  }
}
