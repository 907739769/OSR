import { ref, computed } from 'vue'
import { message } from '@/composables/useMessage'
import { useTaskList } from './useTaskList'
import {
  getPtTransferRuleListApi,
  addPtTransferRuleApi,
  updatePtTransferRuleApi,
  deletePtTransferRuleApi,
  previewPtTransferRuleApi,
  runPtTransferRuleApi,
  getPtTransferRecordListApi
} from '@/api/openlist/ptTransferRule'
import { getPtDownloaderListApi } from '@/api/openlist/ptDownloader'
import type { SearchParams } from '@/types'
import type { ListLoadOptions } from './useGridPageSize'

interface PtTransferRuleQuery extends SearchParams {
  sourceDownloaderId?: number
  targetDownloaderId?: number
}

/**
 * PT 转移做种规则 composable。
 *
 * 除标准 CRUD 外还带三样东西：
 * - `downloaderOptions`：源/目标下拉。Transmission 拿不出 .torrent 本体，不能当来源，
 *   所以源下拉只列 qBittorrent（`sourceOptions`）——在选项里就挡住，比让用户配完、
 *   跑一轮、再从失败通知里知道"这个下载器不行"要好。
 * - `handlePreview`：只判定不搬动。删种类操作有预览，搬种同理：配完先看一眼
 *   "这一轮会搬走什么、目标路径长什么样"。
 * - `handleRun` / `loadRecords`：立即执行与查看历史记录。
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

  const downloaderName = (id?: number) => {
    const hit = downloaders.value.find((d: any) => d.id === id)
    return hit ? hit.name : '-'
  }

  // ---------- 预览 ----------
  const previewOpen = ref(false)
  const previewLoading = ref(false)
  const previewRows = ref<any[]>([])
  const previewRuleName = ref('')

  const handlePreview = async (row: any) => {
    previewRuleName.value = row?.name || ''
    previewOpen.value = true
    previewLoading.value = true
    previewRows.value = []
    try {
      const res: any = await previewPtTransferRuleApi(row.id)
      previewRows.value = res || []
    } catch (e) {
      console.error('[PT转移做种] 预览失败:', e)
    } finally {
      previewLoading.value = false
    }
  }

  // ---------- 立即执行 ----------
  const runLoading = ref(false)

  const handleRun = async (row: any) => {
    if (row.enabled !== '1') {
      message.warning('该规则未启用，请先启用后再执行')
      return
    }
    runLoading.value = true
    try {
      const res: any = await runPtTransferRuleApi(row.id)
      if (res?.exportUnsupported) {
        message.warning('源下载器不支持导出种子文件，Transmission 只能作为转移目标')
      } else {
        message.success(
          `本轮发起 ${res?.started ?? 0} 个转移，完成 ${res?.completed ?? 0} 个，失败 ${res?.failed ?? 0} 个`
        )
      }
      base.getList()
    } catch (e) {
      console.error('[PT转移做种] 执行失败:', e)
    } finally {
      runLoading.value = false
    }
  }

  // ---------- 转移记录 ----------
  const recordOpen = ref(false)
  const recordLoading = ref(false)
  const records = ref<any[]>([])

  const loadRecords = async (ruleId?: number) => {
    recordOpen.value = true
    recordLoading.value = true
    records.value = []
    try {
      const res: any = await getPtTransferRecordListApi({ pageNum: 1, pageSize: 50, ruleId } as any)
      records.value = res?.records || []
    } catch (e) {
      console.error('[PT转移做种] 加载转移记录失败:', e)
    } finally {
      recordLoading.value = false
    }
  }

  // ---------- 移动端 - 分页辅助 ----------
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

  // ---------- 移动端 - 搜索面板折叠 ----------
  const searchCollapsed = ref(true)

  loadDownloaders()

  // PC 端卡片网格页把首次加载交给 useGridPageSize（要先量出列数）
  if (options.autoLoad !== false) base.getList()

  return {
    ...base,
    downloaderOptions, sourceOptions, downloaderName,
    previewOpen, previewLoading, previewRows, previewRuleName, handlePreview,
    runLoading, handleRun,
    recordOpen, recordLoading, records, loadRecords,
    totalPages, prevPage, nextPage, handleSizeChange,
    searchCollapsed
  }
}
