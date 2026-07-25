import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTaskList } from './useTaskList'
import {
  getPtSubscriptionListApi,
  addPtSubscriptionApi,
  updatePtSubscriptionApi,
  deletePtSubscriptionApi,
  tmdbSearchApi,
  subscribeApi,
  getSubscriptionProgressApi,
  refreshSubscriptionApi,
  pauseSubscriptionApi,
  resumeSubscriptionApi,
  searchSupplementApi,
  getSubscriptionSearchLogsApi,
  batchPauseSubscriptionApi,
  batchResumeSubscriptionApi,
  batchDeletePtSubscriptionApi
} from '@/api/openlist/ptSubscription'
import type { SearchParams } from '@/types'

interface PtSubscriptionQuery extends SearchParams {
  title?: string
  mediaType?: string
  status?: string
  sortBy?: string
}

/**
 * PT 订阅 composable
 */
export function usePtSubscription() {
  const base = useTaskList<PtSubscriptionQuery>({
    listApi: getPtSubscriptionListApi,
    addApi: addPtSubscriptionApi,
    updateApi: updatePtSubscriptionApi,
    deleteApi: deletePtSubscriptionApi,
    batchDeleteApi: batchDeletePtSubscriptionApi,
    idField: 'id',
    initForm: () => ({ id: undefined }),
    rules: {},
    defaultQuery: { title: undefined, mediaType: undefined, status: undefined, sortBy: undefined }
  })

  // ---------- 建订阅向导 ----------

  const subscribeOpen = ref(false)
  const searchLoading = ref(false)
  const subscribeLoading = ref(false)
  const searchResults = ref<any[]>([])

  const searchForm = reactive({
    mediaType: 'TV',
    keyword: ''
  })

  /** 当前选中的候选作品 */
  const picked = ref<any>(null)
  /** 剧集才需要选季 */
  const pickedSeason = ref<number>(1)

  const openSubscribeDialog = () => {
    searchForm.mediaType = 'TV'
    searchForm.keyword = ''
    searchResults.value = []
    picked.value = null
    pickedSeason.value = 1
    subscribeOpen.value = true
  }

  const doSearch = async () => {
    if (!searchForm.keyword?.trim()) {
      ElMessage.warning('请输入片名')
      return
    }
    // 换关键词重搜时必须清掉上一次的选择，否则用户没重新点选就点「订阅」会提交旧作品
    picked.value = null
    searchLoading.value = true
    try {
      searchResults.value = (await tmdbSearchApi(searchForm.mediaType, searchForm.keyword)) || []
      if (!searchResults.value.length) {
        ElMessage.info('没有搜到结果，换个关键词试试')
      }
    } catch (e) {
      // 拦截器已弹过错误提示，这里只记录
      console.error(e)
    } finally {
      searchLoading.value = false
    }
  }

  const pick = (item: any) => {
    picked.value = item
    pickedSeason.value = 1
  }

  const confirmSubscribe = async () => {
    if (!picked.value) {
      ElMessage.warning('请先选择一部作品')
      return
    }
    subscribeLoading.value = true
    try {
      await subscribeApi({
        tmdbId: picked.value.tmdbId,
        mediaType: searchForm.mediaType,
        season: searchForm.mediaType === 'MOVIE' ? undefined : pickedSeason.value
      })
      ElMessage.success('订阅成功')
      subscribeOpen.value = false
      base.getList()
    } catch (e) {
      console.error(e)
    } finally {
      subscribeLoading.value = false
    }
  }

  // ---------- 进度 ----------

  const progressOpen = ref(false)
  const progressLoading = ref(false)
  const progress = ref<any>(null)

  const currentSubscription = ref<any>(null)

  const showProgress = async (row: any) => {
    currentSubscription.value = row
    progressOpen.value = true
    progressLoading.value = true
    progress.value = null
    try {
      progress.value = await getSubscriptionProgressApi(row.id)
    } catch (e) {
      console.error(e)
    } finally {
      progressLoading.value = false
    }
  }

  // ---------- 匹配日志 ----------

  const searchLogOpen = ref(false)
  const searchLogLoading = ref(false)
  const searchLogs = ref<any[]>([])

  const showSearchLogs = async (row: any) => {
    searchLogOpen.value = true
    searchLogLoading.value = true
    searchLogs.value = []
    try {
      searchLogs.value = (await getSubscriptionSearchLogsApi(row.id)) || []
    } catch (e) {
      console.error(e)
    } finally {
      searchLogLoading.value = false
    }
  }

  // ---------- 过滤规则覆盖 ----------

  const filterOverrideOpen = ref(false)
  const filterOverrideSaving = ref(false)
  const filterOverrideSubId = ref<number | null>(null)

  /** 每项覆盖字段的开关+值。开关关闭的字段不写入 JSON，沿用全局配置 */
  const filterOverrideForm = reactive({
    minSeeders: { enabled: false, value: 1 as number },
    minSize: { enabled: false, value: 0 as number },
    maxSize: { enabled: false, value: 0 as number },
    freeOnly: { enabled: false, value: '0' as string },
    includeKeywords: { enabled: false, value: '' as string },
    excludeKeywords: { enabled: false, value: '' as string },
    resolutionWhitelist: { enabled: false, value: '' as string },
    resolutionPriority: { enabled: false, value: '' as string },
    preferredSize: { enabled: false, value: 0 as number }
  })

  const FILTER_OVERRIDE_KEYS = Object.keys(filterOverrideForm) as Array<keyof typeof filterOverrideForm>

  const openFilterOverride = (row: any) => {
    filterOverrideSubId.value = row.id
    FILTER_OVERRIDE_KEYS.forEach((key) => {
      filterOverrideForm[key].enabled = false
    })
    let parsed: Record<string, any> = {}
    if (row.filterOverride) {
      try {
        parsed = JSON.parse(row.filterOverride) || {}
      } catch (e) {
        console.error('解析订阅过滤覆盖失败，按未设置覆盖处理', e)
      }
    }
    FILTER_OVERRIDE_KEYS.forEach((key) => {
      if (Object.prototype.hasOwnProperty.call(parsed, key)) {
        filterOverrideForm[key].enabled = true
        filterOverrideForm[key].value = parsed[key]
      }
    })
    filterOverrideOpen.value = true
  }

  const saveFilterOverride = async () => {
    if (!filterOverrideSubId.value) return
    filterOverrideSaving.value = true
    try {
      const override: Record<string, any> = {}
      FILTER_OVERRIDE_KEYS.forEach((key) => {
        if (filterOverrideForm[key].enabled) {
          override[key] = filterOverrideForm[key].value
        }
      })
      // 空字符串而非 null：updateById 默认按"非空字段才更新"，传 null 无法清空已有覆盖，
      // 空字符串能正常写入且后端 FilterCriteriaFactory 把空白 JSON 当作"全部沿用全局配置"
      await updatePtSubscriptionApi({
        id: filterOverrideSubId.value,
        filterOverride: Object.keys(override).length ? JSON.stringify(override) : ''
      })
      ElMessage.success('已保存过滤规则覆盖')
      filterOverrideOpen.value = false
      base.getList()
    } catch (e) {
      console.error(e)
    } finally {
      filterOverrideSaving.value = false
    }
  }

  // ---------- 搜索补集 ----------

  const searchDialogOpen = ref(false)
  const searchDialogLoading = ref(false)
  const searchDialogKeyword = ref('')
  const searchDialogTarget = ref<{ subId: number; episode: number } | null>(null)

  const pad2 = (n: number) => (n < 10 ? '0' + n : String(n))

  /** 打开整季/整部搜索确认框（订阅详情顶部按钮、列表操作列按钮共用） */
  const openSeasonSearch = (row: any) => {
    const isMovie = row.mediaType === 'MOVIE'
    searchDialogTarget.value = { subId: row.id, episode: isMovie ? 0 : -1 }
    searchDialogKeyword.value = isMovie ? row.title : `${row.title} S${pad2(row.season)}`
    searchDialogOpen.value = true
  }

  /** 打开单集搜索确认框，episode 为具体集号（剧集缺集列表专用） */
  const openEpisodeSearch = (row: any, episode: number) => {
    searchDialogTarget.value = { subId: row.id, episode }
    searchDialogKeyword.value = `${row.title} S${pad2(row.season)}E${pad2(episode)}`
    searchDialogOpen.value = true
  }

  const confirmSearch = async () => {
    if (!searchDialogTarget.value) return
    if (!searchDialogKeyword.value?.trim()) {
      ElMessage.warning('请输入搜索关键词')
      return
    }
    searchDialogLoading.value = true
    try {
      const result = await searchSupplementApi(searchDialogTarget.value.subId, {
        episode: searchDialogTarget.value.episode,
        keyword: searchDialogKeyword.value.trim()
      })
      ElMessage[result.pushed ? 'success' : 'info'](result.pushed ? '已找到并推送下载' : '未搜索到匹配资源')
      searchDialogOpen.value = false
      base.getList()
      if (currentSubscription.value && currentSubscription.value.id === searchDialogTarget.value.subId) {
        progress.value = await getSubscriptionProgressApi(searchDialogTarget.value.subId)
      }
    } catch (e) {
      console.error(e)
    } finally {
      searchDialogLoading.value = false
    }
  }

  const toggleAutoSearch = async (row: any) => {
    try {
      await updatePtSubscriptionApi({ id: row.id, autoSearch: row.autoSearch })
      ElMessage.success(row.autoSearch === '1' ? '已开启自动补搜' : '已关闭自动补搜')
    } catch (e) {
      // 请求失败时把开关状态还原（v-model 已经乐观更新过了）
      row.autoSearch = row.autoSearch === '1' ? '0' : '1'
      console.error(e)
    }
  }

  // ---------- 行操作 ----------

  const handleRefresh = async (row: any) => {
    try {
      await refreshSubscriptionApi(row.id)
      ElMessage.success('已与媒体库对账')
      base.getList()
    } catch (e) {
      console.error(e)
    }
  }

  const handlePause = async (row: any) => {
    try {
      await pauseSubscriptionApi(row.id)
      ElMessage.success('已暂停')
      base.getList()
    } catch (e) {
      console.error(e)
    }
  }

  const handleResume = async (row: any) => {
    try {
      await resumeSubscriptionApi(row.id)
      ElMessage.success('已恢复')
      base.getList()
    } catch (e) {
      console.error(e)
    }
  }

  const handleRemove = async (row: any) => {
    try {
      await ElMessageBox.confirm(
        `确认删除订阅「${row.title}」？其集数追踪记录会一并删除。`,
        '警告',
        { type: 'warning' }
      )
      await deletePtSubscriptionApi(row.id)
      ElMessage.success('删除成功')
      base.getList()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
    }
  }

  // ---------- 批量操作 ----------

  const selectionMode = ref(false)

  const toggleSubSelect = (row: any) => {
    const idx = base.selectedIds.value.indexOf(row.id)
    if (idx === -1) {
      base.selectedIds.value.push(row.id)
    } else {
      base.selectedIds.value.splice(idx, 1)
    }
  }

  const isSubSelected = (id: number) => base.selectedIds.value.includes(id)

  /** 批量暂停/恢复共用的结果提示文案："成功 N 项" +（有跳过时）"，M 项已跳过（可能已被删除）" */
  const formatBatchResultMessage = (result: { successCount: number; failedIds: number[] }) => {
    const skipTip = result.failedIds.length ? `，${result.failedIds.length} 项已跳过（可能已被删除）` : ''
    return `成功 ${result.successCount} 项${skipTip}`
  }

  const handleBatchPause = async () => {
    if (!base.selectedIds.value.length) return
    try {
      await ElMessageBox.confirm(`确认批量暂停选中的 ${base.selectedIds.value.length} 个订阅？`, '提示', { type: 'warning' })
      const result = await batchPauseSubscriptionApi(base.selectedIds.value)
      ElMessage.success(formatBatchResultMessage(result))
      base.selectedIds.value = []
      base.getList()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
    }
  }

  const handleBatchResume = async () => {
    if (!base.selectedIds.value.length) return
    try {
      await ElMessageBox.confirm(`确认批量恢复选中的 ${base.selectedIds.value.length} 个订阅？`, '提示', { type: 'warning' })
      const result = await batchResumeSubscriptionApi(base.selectedIds.value)
      ElMessage.success(formatBatchResultMessage(result))
      base.selectedIds.value = []
      base.getList()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
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

  base.getList()

  return {
    ...base,
    // 建订阅向导
    subscribeOpen, searchLoading, subscribeLoading, searchResults, searchForm,
    picked, pickedSeason, openSubscribeDialog, doSearch, pick, confirmSubscribe,
    // 进度
    progressOpen, progressLoading, progress, currentSubscription, showProgress,
    // 匹配日志
    searchLogOpen, searchLogLoading, searchLogs, showSearchLogs,
    // 过滤规则覆盖
    filterOverrideOpen, filterOverrideSaving, filterOverrideForm,
    openFilterOverride, saveFilterOverride,
    // 搜索补集
    searchDialogOpen, searchDialogLoading, searchDialogKeyword,
    openSeasonSearch, openEpisodeSearch, confirmSearch, toggleAutoSearch,
    // 行操作
    handleRefresh, handlePause, handleResume, handleRemove,
    // 批量操作
    selectionMode, toggleSubSelect, isSubSelected, handleBatchPause, handleBatchResume,
    // 移动端分页 & 搜索面板
    totalPages, prevPage, nextPage, handleSizeChange, searchCollapsed
  }
}
