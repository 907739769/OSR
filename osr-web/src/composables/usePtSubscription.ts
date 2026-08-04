import { ref, reactive, computed } from 'vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { useTaskList } from './useTaskList'
import { usePtStatusSocket } from './usePtStatusSocket'
import {
  getPtSubscriptionListApi,
  addPtSubscriptionApi,
  updatePtSubscriptionApi,
  deletePtSubscriptionApi,
  tmdbSearchApi,
  subscribeApi,
  getSubscriptionProgressApi,
  getSubscriptionEpisodesApi,
  resetEpisodeApi,
  refreshSubscriptionApi,
  pauseSubscriptionApi,
  resumeSubscriptionApi,
  searchSupplementApi,
  pushSelectedCandidateApi,
  getSubscriptionSearchLogsApi,
  batchPauseSubscriptionApi,
  batchResumeSubscriptionApi,
  batchDeletePtSubscriptionApi,
  getPtSubscriptionByIdApi
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
    defaultQuery: { title: undefined, mediaType: undefined, status: 'ACTIVE', sortBy: undefined, pageSize: 12 }
  })

  // ---------- 实时状态推送：订阅命中时间原地更新，不用整页刷新 ----------
  usePtStatusSocket({
    onSubscription: (event) => {
      const row = base.taskList.value.find((item: any) => item.id === event.subId)
      if (row) {
        Object.assign(row, { lastMatchTime: event.lastMatchTime })
      }
    }
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
      message.warning('请输入片名')
      return
    }
    // 换关键词重搜时必须清掉上一次的选择，否则用户没重新点选就点「订阅」会提交旧作品
    picked.value = null
    searchLoading.value = true
    try {
      searchResults.value = (await tmdbSearchApi(searchForm.mediaType, searchForm.keyword)) || []
      if (!searchResults.value.length) {
        message.info('没有搜到结果，换个关键词试试')
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
      message.warning('请先选择一部作品')
      return
    }
    subscribeLoading.value = true
    try {
      await subscribeApi({
        tmdbId: picked.value.tmdbId,
        mediaType: searchForm.mediaType,
        season: searchForm.mediaType === 'MOVIE' ? undefined : pickedSeason.value
      })
      message.success('订阅成功')
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
    episodeDetailOpen.value = false
    episodeDetail.value = []
    try {
      progress.value = await getSubscriptionProgressApi(row.id)
    } catch (e) {
      console.error(e)
    } finally {
      progressLoading.value = false
    }
  }

  /** 从下载记录页跳转过来时，按 id 查该条订阅并直接弹出进度，而不是过滤列表 */
  const showProgressById = async (id: number) => {
    try {
      const row = await getPtSubscriptionByIdApi(id)
      if (row) await showProgress(row)
    } catch (e) {
      console.error(e)
    }
  }

  // ---------- 每集明细（进度弹窗内"查看全部集"展开区） ----------

  const episodeDetailOpen = ref(false)
  const episodeDetailLoading = ref(false)
  const episodeDetail = ref<any[]>([])
  const resettingEpisode = ref<number | null>(null)

  const loadEpisodeDetail = async () => {
    if (!currentSubscription.value) return
    episodeDetailOpen.value = !episodeDetailOpen.value
    if (!episodeDetailOpen.value || episodeDetail.value.length) return
    episodeDetailLoading.value = true
    try {
      episodeDetail.value = (await getSubscriptionEpisodesApi(currentSubscription.value.id)) || []
    } catch (e) {
      console.error(e)
    } finally {
      episodeDetailLoading.value = false
    }
  }

  /** 每集状态文案。PC 与移动端共用，避免两端文案漂移 */
  const EPISODE_STATE_LABELS: Record<string, string> = {
    MISSING: '缺失', IN_FLIGHT: '在途', IN_LIBRARY: '已入库',
    UPGRADING: '洗版中', BLOCKED: '已熔断'
  }
  const episodeStateLabel = (state: string) => EPISODE_STATE_LABELS[state] || state

  /** 每集状态对应的 chip 颜色，同样两端共用 */
  const episodeStateColor = (state: string) => {
    switch (state) {
      case 'IN_LIBRARY': return 'success'
      case 'IN_FLIGHT': return 'primary'
      // 洗版中：旧版本一直在库里可正常观看，不该用告警色吓人
      case 'UPGRADING': return 'primary'
      case 'BLOCKED': return 'error'
      default: return 'info'
    }
  }

  /** 重置某一集为缺失：只对 IN_LIBRARY/BLOCKED 这类"卡住"的状态开放，需二次确认 */
  const handleResetEpisode = async (ep: any) => {
    if (!currentSubscription.value) return
    try {
      await confirm({
        message: `确认将第 ${ep.episode} 集重置为缺失？重置后需要重新匹配/下载。`,
        title: '提示',
        type: 'warning'
      })
      resettingEpisode.value = ep.episode
      await resetEpisodeApi(currentSubscription.value.id, ep.episode)
      message.success('已重置')
      // 重置成功后重新拉一次明细与进度汇总，两处都要保持一致
      episodeDetail.value = (await getSubscriptionEpisodesApi(currentSubscription.value.id)) || []
      progress.value = await getSubscriptionProgressApi(currentSubscription.value.id)
      base.getList()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
    } finally {
      resettingEpisode.value = null
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
    requireChineseSubtitle: { enabled: false, value: '0' as string },
    includeKeywords: { enabled: false, value: '' as string },
    excludeKeywords: { enabled: false, value: '' as string },
    resolutionWhitelist: { enabled: false, value: '' as string },
    resolutionPriority: { enabled: false, value: '' as string },
    preferredSize: { enabled: false, value: 0 as number }
  })

  const FILTER_OVERRIDE_KEYS = Object.keys(filterOverrideForm) as Array<keyof typeof filterOverrideForm>

  /** 前端用 GB 显示，后端存字节；1 GB = 1073741824 字节 */
  const GB = 1073741824
  const sizeFields = new Set<keyof typeof filterOverrideForm>(['minSize', 'maxSize', 'preferredSize'])

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
        // 体积字段后端存字节，前端显示 GB（除以 GB），未定义或0时展示0
        filterOverrideForm[key].value = sizeFields.has(key)
          ? Math.round((parsed[key] as number) / GB)
          : parsed[key]
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
          // 体积字段前端显示 GB，后端存字节（乘以 GB）
          override[key] = sizeFields.has(key)
            ? (filterOverrideForm[key].value as number) * GB
            : filterOverrideForm[key].value
        }
      })
      // 空字符串而非 null：updateById 默认按"非空字段才更新"，传 null 无法清空已有覆盖，
      // 空字符串能正常写入且后端 FilterCriteriaFactory 把空白 JSON 当作"全部沿用全局配置"
      await updatePtSubscriptionApi({
        id: filterOverrideSubId.value,
        filterOverride: Object.keys(override).length ? JSON.stringify(override) : ''
      })
      message.success('已保存过滤规则覆盖')
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
  const searchManualSelect = ref(false)
  const searchDialogTarget = ref<{ subId: number; episode: number } | null>(null)

  /** 手动选择候选弹窗 */
  const candidateDialogOpen = ref(false)
  const candidates = ref<any[]>([])
  const pushingSelected = ref(false)

  const pad2 = (n: number) => (n < 10 ? '0' + n : String(n))

  /** 打开整季/整部搜索确认框（订阅详情顶部按钮、列表操作列按钮共用） */
  const openSeasonSearch = (row: any) => {
    const isMovie = row.mediaType === 'MOVIE'
    searchDialogTarget.value = { subId: row.id, episode: isMovie ? 0 : -1 }
    searchDialogKeyword.value = isMovie ? row.title : `${row.title} S${pad2(row.season)}`
    searchManualSelect.value = false
    searchDialogOpen.value = true
  }

  /** 打开单集搜索确认框，episode 为具体集号（剧集缺集列表专用） */
  const openEpisodeSearch = (row: any, episode: number) => {
    searchDialogTarget.value = { subId: row.id, episode }
    searchDialogKeyword.value = `${row.title} S${pad2(row.season)}E${pad2(episode)}`
    searchManualSelect.value = false
    searchDialogOpen.value = true
  }

  const confirmSearch = async () => {
    if (!searchDialogTarget.value) return
    if (!searchDialogKeyword.value?.trim()) {
      message.warning('请输入搜索关键词')
      return
    }
    const target = searchDialogTarget.value
    const manualSelect = searchManualSelect.value
    searchDialogLoading.value = true
    try {
      const result = await searchSupplementApi(target.subId, {
        episode: target.episode,
        keyword: searchDialogKeyword.value.trim(),
        manualSelect
      })

      if (manualSelect && result.candidates && result.candidates.length > 0) {
        // 手动模式：展示候选列表供用户挑选
        candidates.value = result.candidates
        searchDialogOpen.value = false
        candidateDialogOpen.value = true
      } else if (manualSelect && (!result.candidates || result.candidates.length === 0)) {
        // 手动模式但无结果
        message.info('未搜索到匹配资源')
        searchDialogOpen.value = false
      } else {
        // 自动推送模式
        message[result.pushed ? 'success' : 'info'](result.pushed ? '已找到并推送下载' : '未搜索到匹配资源')
        searchDialogOpen.value = false
        base.getList()
        if (currentSubscription.value && currentSubscription.value.id === target.subId) {
          progress.value = await getSubscriptionProgressApi(target.subId)
        }
      }
    } catch (e) {
      console.error(e)
    } finally {
      searchDialogLoading.value = false
    }
  }

  /** 推送用户选中的候选种子 */
  const pushSelectedCandidate = async (candidate: any) => {
    if (!searchDialogTarget.value) return
    pushingSelected.value = true
    try {
      // 候选自带解析出的集号时按候选走（可能是季包目标下混入的单集资源），否则回退到弹窗目标的集号
      const episode = candidate.parsedEpisode ?? searchDialogTarget.value.episode
      await pushSelectedCandidateApi(searchDialogTarget.value.subId, {
        episode,
        title: candidate.title,
        size: candidate.size,
        seeders: candidate.seeders,
        peers: candidate.peers,
        // 原样回传后端给出的下载量系数，不要用 free 布尔反推：那样会把半价促销种(0.5)
        // 压成 1.0，促销优先排序在推送路径上失真。后端对 undefined 按 1.0(正常计量)兜底。
        downloadVolumeFactor: candidate.downloadVolumeFactor,
        indexerId: candidate.indexerId,
        guid: candidate.guid,
        downloadUrl: candidate.downloadUrl,
        infoHash: candidate.infoHash,
        pubDate: candidate.pubDate
      })
      message.success('已推送下载')
      candidateDialogOpen.value = false
      base.getList()
      if (currentSubscription.value && currentSubscription.value.id === searchDialogTarget.value.subId) {
        progress.value = await getSubscriptionProgressApi(searchDialogTarget.value.subId)
      }
    } catch (e) {
      console.error(e)
      message.error('推送失败')
    } finally {
      pushingSelected.value = false
    }
  }

  /** 格式化体积为人类可读 */
  const formatSize = (bytes: number) => {
    if (bytes === 0) return '0 B'
    const units = ['B', 'KB', 'MB', 'GB', 'TB']
    const k = 1024
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + units[i]
  }

  // ---------- 一键补齐全部缺集 ----------

  const searchAllMissingLoading = ref(false)

  const handleSearchAllMissing = async () => {
    if (!currentSubscription.value || !progress.value?.missingEpisodes?.length) return
    searchAllMissingLoading.value = true
    const missing = [...progress.value.missingEpisodes] as number[]
    let pushedCount = 0
    for (const ep of missing) {
      const keyword = `${currentSubscription.value.title} S${String(currentSubscription.value.season).padStart(2, '0')}E${String(ep).padStart(2, '0')}`
      try {
        const result = await searchSupplementApi(currentSubscription.value.id, {
          episode: ep,
          keyword
        })
        if (result.pushed) pushedCount++
      } catch (e) {
        console.error(`第${ep}集补搜失败：`, e)
      }
    }
    message.success(`已完成搜索：${pushedCount}/${missing.length} 集已推送下载`)
    // 刷新进度
    if (currentSubscription.value) {
      progress.value = await getSubscriptionProgressApi(currentSubscription.value.id)
    }
    base.getList()
    searchAllMissingLoading.value = false
  }

  /**
   * 集当前的质量画像摘要，如 {@code 2160p / REMUX / HDR10 / CHDBits}。
   * quality 列存的是后端 QualityProfile 序列化出来的 JSON，脏数据不该让整个列表炸掉。
   */
  const qualityLabel = (ep: any): string => {
    if (!ep?.quality) return ''
    try {
      const p = JSON.parse(ep.quality)
      const parts = [p.resolution, p.source]
      if (Array.isArray(p.tags) && p.tags.length) parts.push(p.tags.join('+'))
      parts.push(p.group)
      return parts.filter(Boolean).join(' / ')
    } catch {
      return ''
    }
  }

  /** 洗版状态的说明文字，挂在质量摘要的 title 上，不占列表宽度 */
  const upgradeStateHint = (ep: any): string => {
    switch (ep?.upgradeState) {
      case 'REACHED': return '已达目标质量，不再洗版'
      case 'NO_BASELINE': return '无质量基线（订阅创建时该集就已在库中），不参与洗版'
      case 'PENDING': return '未达目标质量，会在洗版扫描中尝试升级'
      default: return '尚未评估洗版状态'
    }
  }

  /** 订阅级洗版开关。全局开关（PT洗版规则页）关闭时本项不生效 */
  const toggleUpgrade = async (row: any) => {
    try {
      await updatePtSubscriptionApi({ id: row.id, upgradeEnabled: row.upgradeEnabled })
      message.success(row.upgradeEnabled === '1' ? '已开启洗版' : '已关闭洗版')
    } catch (e) {
      // 请求失败时把开关状态还原（v-model 已经乐观更新过了）
      row.upgradeEnabled = row.upgradeEnabled === '1' ? '0' : '1'
      console.error(e)
    }
  }

  const toggleAutoSearch = async (row: any) => {
    try {
      await updatePtSubscriptionApi({ id: row.id, autoSearch: row.autoSearch })
      message.success(row.autoSearch === '1' ? '已开启自动补搜' : '已关闭自动补搜')
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
      message.success('已与媒体库对账')
      base.getList()
    } catch (e) {
      console.error(e)
    }
  }

  const handlePause = async (row: any) => {
    try {
      await pauseSubscriptionApi(row.id)
      message.success('已暂停')
      base.getList()
    } catch (e) {
      console.error(e)
    }
  }

  const handleResume = async (row: any) => {
    try {
      await resumeSubscriptionApi(row.id)
      message.success('已恢复')
      base.getList()
    } catch (e) {
      console.error(e)
    }
  }

  const handleRemove = async (row: any) => {
    try {
      await confirm({
        message: `确认删除订阅「${row.title}」？其集数追踪记录会一并删除。`,
        title: '警告',
        type: 'warning'
      })
      await deletePtSubscriptionApi(row.id)
      message.success('删除成功')
      base.getList()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
    }
  }

  // ---------- 批量操作 ----------

  const selectionMode = ref(false)

  /** 当前页所有项是否全部已选 */
  const isAllPageSelected = computed(() =>
    base.taskList.value.length > 0 && base.taskList.value.every((item: any) => base.selectedIds.value.includes(item.id))
  )
  /** 当前页部分选中（有选中的但不全） */
  const isIndeterminate = computed(() =>
    !isAllPageSelected.value && base.taskList.value.some((item: any) => base.selectedIds.value.includes(item.id))
  )

  const toggleSelectAllPage = (checked: string | number | boolean) => {
    if (checked) {
      for (const item of base.taskList.value) {
        if (!base.selectedIds.value.includes(item.id)) {
          base.selectedIds.value.push(item.id)
        }
      }
    } else {
      const pageIds = new Set(base.taskList.value.map((item: any) => item.id))
      base.selectedIds.value = base.selectedIds.value.filter((id: number) => !pageIds.has(id))
    }
  }

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
      await confirm({ message: `确认批量暂停选中的 ${base.selectedIds.value.length} 个订阅？`, title: '提示', type: 'warning' })
      const result = await batchPauseSubscriptionApi(base.selectedIds.value)
      message.success(formatBatchResultMessage(result))
      base.selectedIds.value = []
      base.getList()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
    }
  }

  const handleBatchResume = async () => {
    if (!base.selectedIds.value.length) return
    try {
      await confirm({ message: `确认批量恢复选中的 ${base.selectedIds.value.length} 个订阅？`, title: '提示', type: 'warning' })
      const result = await batchResumeSubscriptionApi(base.selectedIds.value)
      message.success(formatBatchResultMessage(result))
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
    progressOpen, progressLoading, progress, currentSubscription, showProgress, showProgressById,
    // 每集明细 + 手动重置
    episodeDetailOpen, episodeDetailLoading, episodeDetail, resettingEpisode,
    loadEpisodeDetail, handleResetEpisode, episodeStateLabel, episodeStateColor,
    qualityLabel, upgradeStateHint,
    // 匹配日志
    searchLogOpen, searchLogLoading, searchLogs, showSearchLogs,
    // 过滤规则覆盖
    filterOverrideOpen, filterOverrideSaving, filterOverrideForm,
    openFilterOverride, saveFilterOverride,
    // 搜索补集
    searchDialogOpen, searchDialogLoading, searchDialogKeyword, searchManualSelect,
    openSeasonSearch, openEpisodeSearch, confirmSearch,
    // 手动选择候选
    candidateDialogOpen, candidates, pushingSelected, pushSelectedCandidate, formatSize,
    // 一键补齐全部缺集
    searchAllMissingLoading, handleSearchAllMissing, toggleAutoSearch, toggleUpgrade,
    // 行操作
    handleRefresh, handlePause, handleResume, handleRemove,
    // 批量操作
    selectionMode, isAllPageSelected, isIndeterminate, toggleSelectAllPage,
    toggleSubSelect, isSubSelected, handleBatchPause, handleBatchResume,
    // 移动端分页 & 搜索面板
    totalPages, prevPage, nextPage, handleSizeChange, searchCollapsed
  }
}
