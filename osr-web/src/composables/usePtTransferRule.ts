import { ref, reactive, computed, watch } from 'vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { useTaskList } from './useTaskList'
import { usePtStatusSocket } from './usePtStatusSocket'
import {
  getPtTransferRuleListApi,
  addPtTransferRuleApi,
  updatePtTransferRuleApi,
  deletePtTransferRuleApi,
  previewPtTransferRuleApi,
  runPtTransferRuleApi,
  getPtTransferRecordListApi,
  getPtTransferRecordSummaryApi,
  clearPtTransferFailedRecordsApi
} from '@/api/openlist/ptTransferRule'
import type { PtTransferRuleSummary } from '@/api/openlist/ptTransferRule'
import { getPtDownloaderListApi } from '@/api/openlist/ptDownloader'
import type { SearchParams } from '@/types'
import type { ListLoadOptions } from './useGridPageSize'

interface PtTransferRuleQuery extends SearchParams {
  sourceDownloaderId?: number
  targetDownloaderId?: number
}

/** 转移记录的状态，记录弹窗的筛选与 StatusChip 共用这一份 */
export const TRANSFER_STATE_OPTIONS = [
  { value: 'VERIFYING', title: '校验中', type: 'warning' },
  { value: 'COMPLETED', title: '已完成', type: 'success' },
  { value: 'FAILED', title: '失败', type: 'error' },
  { value: 'SKIPPED', title: '已跳过', type: 'info' }
] as const

export const transferStateText = (state: string) =>
  TRANSFER_STATE_OPTIONS.find(o => o.value === state)?.title ?? state

export const transferStateType = (state: string) =>
  TRANSFER_STATE_OPTIONS.find(o => o.value === state)?.type ?? 'info'

export const formatTransferSize = (bytes?: number) => {
  if (!bytes) return '-'
  const gb = bytes / 1024 ** 3
  return gb >= 1 ? `${gb.toFixed(2)} GB` : `${(bytes / 1024 ** 2).toFixed(0)} MB`
}

/** 把规则的几个筛选条件压成一行人话，卡片上只有一行的位置 */
export const transferConditionText = (item: any) => {
  const parts: string[] = []
  if (item.minSeedHours > 0) parts.push(`做满 ${item.minSeedHours} 小时`)
  const min = Number(item.minSizeGb) || 0
  if (min > 0 || item.maxSizeGb) {
    parts.push(`${min}~${item.maxSizeGb ?? '∞'} GB`)
  }
  if (item.includeTags) parts.push(`含标签 ${item.includeTags}`)
  if (item.excludeTags) parts.push(`排除 ${item.excludeTags}`)
  return parts.length ? parts.join('，') : '全部已完成的种子'
}

/**
 * 保存路径映射的前端校验，与后端 PathMapping.validate 同一套判据。
 * 后端运行期对坏配置是宽容的（退化成不映射），写错的 JSON 不在保存时拦下，
 * 要等转移校验失败、回滚之后用户才会回头怀疑这一栏
 */
export function validatePathMapping(value?: string | null): true | string {
  if (!value || !value.trim()) return true
  let parsed: unknown
  try {
    parsed = JSON.parse(value)
  } catch {
    return '不是合法的 JSON，格式如 [{"from":"/downloads","to":"/data/downloads"}]'
  }
  if (!Array.isArray(parsed)) return '应为 JSON 数组，格式如 [{"from":"/downloads","to":"/data/downloads"}]'
  for (let i = 0; i < parsed.length; i++) {
    const item = parsed[i] as any
    if (!item || typeof item !== 'object' || Array.isArray(item)) return `第 ${i + 1} 项应为 {"from":"…","to":"…"}`
    if (!String(item.from ?? '').trim() || !String(item.to ?? '').trim()) return `第 ${i + 1} 项的 from / to 不能为空`
  }
  return true
}

/**
 * PT 转移做种规则 composable。
 *
 * 除标准 CRUD 外还带这些东西：
 * - `downloaderOptions`：源/目标下拉。Transmission 拿不出 .torrent 本体，不能当来源，
 *   所以源下拉只列 qBittorrent（`sourceOptions`）——在选项里就挡住，比让用户配完、
 *   跑一轮、再从失败通知里知道"这个下载器不行"要好。目标下拉排除已选的源（`targetOptions`）。
 * - `summaries`：规则卡片上的运行情况（各状态条数、最近一次转移时间）。只有配置的话，
 *   看不出这条规则是在正常干活还是一直在失败。
 * - `handlePreview`：只判定不搬动。删种类操作有预览，搬种同理：配完先看一眼
 *   "这一轮会搬走什么、目标路径长什么样"。
 * - `handleRun`：立即执行。后端转后台跑，结果经 WebSocket 按 runId 回报；
 *   loading 按规则分开记，点一张卡片不能让所有卡片一起转圈。
 * - `loadRecords` / `handleClearFailed`：转移记录（分页 + 状态筛选）与失败闸门的解除入口。
 */
export function usePtTransferRule(options: ListLoadOptions = {}) {
  const base = useTaskList<PtTransferRuleQuery>({
    listApi: getPtTransferRuleListApi,
    addApi: addPtTransferRuleApi,
    updateApi: updatePtTransferRuleApi,
    deleteApi: deletePtTransferRuleApi,
    idField: 'id',
    initForm: () => ({
      id: undefined,
      name: undefined,
      sourceDownloaderId: undefined,
      targetDownloaderId: undefined,
      enabled: '0',
      minSeedHours: 72,
      minSizeGb: 0,
      maxSizeGb: undefined,
      includeTags: undefined,
      excludeTags: undefined,
      pathMapping: undefined,
      targetTag: 'osr-transfer',
      deleteSource: '1',
      maxPerRound: 10,
      verifyTimeoutMinutes: 120,
      remark: undefined
    }),
    rules: {
      name: [{ required: true, message: '规则名不能为空', trigger: 'blur' }],
      sourceDownloaderId: [{ required: true, message: '请选择源下载器', trigger: 'change' }],
      targetDownloaderId: [{ required: true, message: '请选择目标下载器', trigger: 'change' }]
    },
    defaultQuery: {
      sourceDownloaderId: undefined,
      targetDownloaderId: undefined,
      pageSize: 12
    }
  })

  const getList = base.getList

  // ---------- 下载器下拉 ----------
  const downloaders = ref<any[]>([])

  const loadDownloaders = async () => {
    try {
      const res: any = await getPtDownloaderListApi({ pageNum: 1, pageSize: 200 })
      downloaders.value = res?.records || []
    } catch (e) {
      console.error('[PT转移做种] 加载下载器列表失败:', e)
    }
  }

  const downloaderOptions = computed(() =>
    downloaders.value.map((d: any) => ({ title: `${d.name}（${d.type === 'TRANSMISSION' ? 'Transmission' : 'qBittorrent'}）`, value: d.id }))
  )

  /** 源下载器候选：Transmission 不能导出种子文件，不列进来 */
  const sourceOptions = computed(() =>
    downloaders.value
      .filter((d: any) => d.type !== 'TRANSMISSION')
      .map((d: any) => ({ title: `${d.name}（qBittorrent）`, value: d.id }))
  )

  /** 目标下载器候选：排除表单里已选为源的那一个（后端同样会拒，这里是不让用户选到） */
  const targetOptions = computed(() =>
    downloaderOptions.value.filter(o => o.value !== base.form.value?.sourceDownloaderId)
  )

  const targetRules = [
    (v: any) => (v !== undefined && v !== null && v !== '') || '请选择目标下载器',
    (v: any) => v !== base.form.value?.sourceDownloaderId || '源下载器与目标下载器不能是同一个'
  ]

  /** 体积上限：填了就不能小于下限 */
  const maxSizeRules = [
    (v: any) => {
      if (v === undefined || v === null || v === '') return true
      const min = Number(base.form.value?.minSizeGb) || 0
      return Number(v) >= min || '体积上限不能小于体积下限'
    }
  ]

  const downloaderName = (id?: number) => {
    const hit = downloaders.value.find((d: any) => d.id === id)
    return hit ? hit.name : '-'
  }

  // ---------- 卡片上的运行情况 ----------
  const summaries = ref<Record<string, PtTransferRuleSummary>>({})

  /** 拉不到只是卡片上少一行运行情况，不影响列表，不弹错 */
  const loadSummaries = async () => {
    try {
      summaries.value = (await getPtTransferRecordSummaryApi()) || {}
    } catch (e) {
      console.error('[PT转移做种] 加载运行情况失败:', e)
    }
  }

  // 列表每刷新一次（查询、增删改、执行完成）就顺带刷新运行情况：两者得对得上，
  // 挂在列表数据上而不是逐个调用点补一行，免得哪条路径漏掉
  watch(() => base.taskList.value, () => loadSummaries())

  const summaryOf = (id?: number): PtTransferRuleSummary | undefined =>
    id === undefined ? undefined : summaries.value[String(id)]

  // ---------- 启用开关 ----------
  const toggleLoadingIds = reactive(new Set<number>())

  /**
   * 卡片上直接启停。新建规则默认停用、确认预览后再启用，这一步原先要进编辑弹窗改一个单选框
   */
  const toggleEnabled = async (row: any, enabled: boolean) => {
    toggleLoadingIds.add(row.id)
    try {
      await updatePtTransferRuleApi({ ...row, enabled: enabled ? '1' : '0' })
      row.enabled = enabled ? '1' : '0'
      message.success(enabled ? `已启用「${row.name}」` : `已停用「${row.name}」`)
    } catch (e) {
      console.error('[PT转移做种] 切换启用状态失败:', e)
    } finally {
      toggleLoadingIds.delete(row.id)
    }
  }

  // ---------- 预览 ----------
  const previewOpen = ref(false)
  const previewLoading = ref(false)
  const previewRows = ref<any[]>([])
  const previewRuleName = ref('')
  /** 源下载器上种子多时「会转移」的那几条淹没在一屏跳过里，默认只看会转移的 */
  const previewOnlyTransferable = ref(false)

  /** 预览汇总：会转移几个、各跳过原因各几个，按条数降序 */
  const previewSummary = computed(() => {
    const transferable = previewRows.value.filter(r => r.transferable).length
    const skipCounts = new Map<string, number>()
    for (const row of previewRows.value) {
      if (row.transferable) continue
      const reason = row.skipReason || '未知原因'
      skipCounts.set(reason, (skipCounts.get(reason) || 0) + 1)
    }
    const skipped = [...skipCounts.entries()]
      .map(([reason, count]) => ({ reason, count }))
      .sort((a, b) => b.count - a.count)
    return { total: previewRows.value.length, transferable, skipped }
  })

  const visiblePreviewRows = computed(() =>
    previewOnlyTransferable.value ? previewRows.value.filter(r => r.transferable) : previewRows.value
  )

  const handlePreview = async (row: any) => {
    previewRuleName.value = row?.name || ''
    previewOpen.value = true
    previewLoading.value = true
    previewRows.value = []
    previewOnlyTransferable.value = false
    try {
      const res: any = await previewPtTransferRuleApi(row.id)
      previewRows.value = res || []
      // 有会转移的才默认收起跳过项；一个都没有时要看的恰恰是跳过原因
      previewOnlyTransferable.value = previewRows.value.some(r => r.transferable)
        && previewRows.value.length > 20
    } catch (e) {
      console.error('[PT转移做种] 预览失败:', e)
    } finally {
      previewLoading.value = false
    }
  }

  // ---------- 立即执行 ----------
  const runningIds = reactive(new Set<number>())
  /** WebSocket 是全体广播，只认自己发起的那几次 */
  const pendingRuns = new Map<string, number>()

  const handleRun = async (row: any) => {
    if (row.enabled !== '1') {
      message.warning('该规则未启用，请先启用后再执行')
      return
    }
    runningIds.add(row.id)
    try {
      const { runId } = await runPtTransferRuleApi(row.id)
      pendingRuns.set(runId, row.id)
      message.info(`「${row.name}」已在后台执行，完成后会提示结果`)
    } catch (e) {
      runningIds.delete(row.id)
      console.error('[PT转移做种] 执行失败:', e)
    }
  }

  usePtStatusSocket({
    onTransferRun: (event) => {
      const ruleId = pendingRuns.get(event.runId)
      if (ruleId === undefined) return
      pendingRuns.delete(event.runId)
      runningIds.delete(ruleId)
      if (event.error) {
        message.error(`执行失败：${event.error}`)
      } else if (event.exportUnsupported) {
        message.warning('源下载器不支持导出种子文件，Transmission 只能作为转移目标')
      } else {
        message.success(
          `本轮发起 ${event.started ?? 0} 个转移，完成 ${event.completed ?? 0} 个，失败 ${event.failed ?? 0} 个`
          + (event.skipped ? `，${event.skipped} 个目标端已存在` : '')
        )
      }
      getList()
      if (recordOpen.value) fetchRecords()
    }
  })

  // ---------- 转移记录 ----------
  const recordOpen = ref(false)
  const recordLoading = ref(false)
  const records = ref<any[]>([])
  const recordTotal = ref(0)
  const recordQuery = reactive({
    ruleId: undefined as number | undefined,
    state: undefined as string | undefined,
    pageNum: 1,
    pageSize: 20
  })
  const recordTotalPages = computed(() => Math.ceil(recordTotal.value / recordQuery.pageSize) || 1)
  /** 记录弹窗标题里的规则名；从工具栏打开（全部规则）时为空 */
  const recordRuleName = computed(() =>
    recordQuery.ruleId === undefined ? '' : (base.taskList.value.find((r: any) => r.id === recordQuery.ruleId)?.name ?? '')
  )

  const fetchRecords = async () => {
    recordLoading.value = true
    try {
      const res: any = await getPtTransferRecordListApi({ ...recordQuery })
      records.value = res?.records || []
      recordTotal.value = res?.total || 0
    } catch (e) {
      console.error('[PT转移做种] 加载转移记录失败:', e)
    } finally {
      recordLoading.value = false
    }
  }

  const loadRecords = (ruleId?: number, state?: string) => {
    recordOpen.value = true
    recordQuery.ruleId = ruleId
    recordQuery.state = state
    recordQuery.pageNum = 1
    records.value = []
    recordTotal.value = 0
    return fetchRecords()
  }

  /** 状态筛选变了回到第一页 */
  const onRecordStateChange = () => {
    recordQuery.pageNum = 1
    fetchRecords()
  }

  /**
   * 清除失败记录。同一个种子失败太多次后后端会停止自动重试（否则失败原因不变、
   * 每一轮都原样重试一次再发一条通知），这里是解除那道闸门的唯一入口。
   * 清掉之后那批种子会重新开始转移，而从工具栏打开时清的是全部规则——必须先确认
   */
  const clearLoading = ref(false)
  const handleClearFailed = async () => {
    const ruleId = recordQuery.ruleId
    let failedCount = 0
    try {
      const res: any = await getPtTransferRecordListApi({ ruleId, state: 'FAILED', pageNum: 1, pageSize: 1 })
      failedCount = res?.total || 0
    } catch (e) {
      console.error('[PT转移做种] 统计失败记录失败:', e)
      return
    }
    if (!failedCount) {
      message.info('没有失败记录需要清除')
      return
    }
    const scope = ruleId === undefined ? '全部规则' : `规则「${recordRuleName.value || ruleId}」`
    try {
      await confirm({
        title: '清除失败记录',
        type: 'warning',
        message: `将清除${scope}的 ${failedCount} 条失败记录。清除后这些种子会重新参与转移（转移成功后按规则可能删除源端种子），`
          + '请确认导致失败的配置已经改好。'
      })
    } catch {
      return
    }
    clearLoading.value = true
    try {
      const removed: any = await clearPtTransferFailedRecordsApi(ruleId)
      message.success(`已清除 ${removed ?? 0} 条失败记录，这些种子会重新参与转移`)
      await fetchRecords()
      loadSummaries()
    } catch (e) {
      console.error('[PT转移做种] 清除失败记录失败:', e)
    } finally {
      clearLoading.value = false
    }
  }

  // ---------- 移动端 - 分页辅助 ----------
  const totalPages = computed(() => Math.ceil(base.total.value / base.queryParams.pageSize) || 1)

  const prevPage = () => {
    if (base.queryParams.pageNum > 1) {
      base.queryParams.pageNum--
      getList()
    }
  }

  const nextPage = () => {
    if (base.queryParams.pageNum < totalPages.value) {
      base.queryParams.pageNum++
      getList()
    }
  }

  const handleSizeChange = () => {
    base.queryParams.pageNum = 1
    getList()
  }

  // ---------- 移动端 - 搜索面板折叠 ----------
  const searchCollapsed = ref(true)

  loadDownloaders()

  // PC 端卡片网格页把首次加载交给 useGridPageSize（要先量出列数）
  if (options.autoLoad !== false) getList()

  return {
    ...base,
    downloaderOptions, sourceOptions, targetOptions, targetRules, maxSizeRules, downloaderName,
    summaries, summaryOf, loadSummaries,
    toggleLoadingIds, toggleEnabled,
    previewOpen, previewLoading, previewRows, previewRuleName, previewOnlyTransferable,
    previewSummary, visiblePreviewRows, handlePreview,
    runningIds, handleRun,
    recordOpen, recordLoading, records, recordTotal, recordQuery, recordTotalPages, recordRuleName,
    loadRecords, fetchRecords, onRecordStateChange,
    clearLoading, handleClearFailed,
    totalPages, prevPage, nextPage, handleSizeChange,
    searchCollapsed
  }
}
