import { ref, reactive, computed, watch } from 'vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { useTaskList } from './useTaskList'
import { usePtStatusSocket } from './usePtStatusSocket'
import { bytesToGb, gbToBytes } from './sizeUnits'
import {
  getPtSubscriptionListApi,
  addPtSubscriptionApi,
  updatePtSubscriptionApi,
  deletePtSubscriptionApi,
  tmdbSearchApi,
  tmdbSeasonEpisodeCountApi,
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
import { getPtFilterConfigApi } from '@/api/openlist/ptFilterConfig'
import type { SearchParams } from '@/types'
import type { ListLoadOptions } from './useGridPageSize'

interface PtSubscriptionQuery extends SearchParams {
  title?: string
  mediaType?: string
  status?: string
  sortBy?: string
}

/**
 * PT 订阅 composable
 */
export function usePtSubscription(options: ListLoadOptions = {}) {
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
    pickedSeasonEpisodeCount.value = null
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

  /**
   * 所选季在 TMDb 上的集数。季号原本是个裸数字输入框，用户填错了要等订阅建完、
   * 回到列表看「共 N 集」才发现——而 `/tmdb-seasons` 这个接口一直都在，只是没人调。
   * null 表示还没查/查不到，此时整段提示不渲染。
   */
  const pickedSeasonEpisodeCount = ref<number | null>(null)
  const pickedSeasonCountLoading = ref(false)
  /** 只采信最后一次请求的结果：用户连点季号上下箭头会并发发出好几个请求，先发的可能后到 */
  let seasonCountRequestId = 0

  const loadPickedSeasonEpisodeCount = async () => {
    pickedSeasonEpisodeCount.value = null
    if (!picked.value || searchForm.mediaType === 'MOVIE') return
    const season = pickedSeason.value
    if (season === null || season === undefined || season < 0) return
    const requestId = ++seasonCountRequestId
    pickedSeasonCountLoading.value = true
    try {
      const count = await tmdbSeasonEpisodeCountApi(picked.value.tmdbId, season)
      if (requestId === seasonCountRequestId) {
        pickedSeasonEpisodeCount.value = typeof count === 'number' ? count : null
      }
    } catch (e) {
      // 查不到集数（季号不存在、TMDb 不可达）只是少了个提示，不该拦住用户订阅
      if (requestId === seasonCountRequestId) pickedSeasonEpisodeCount.value = null
      console.error(e)
    } finally {
      if (requestId === seasonCountRequestId) pickedSeasonCountLoading.value = false
    }
  }

  const pick = (item: any) => {
    picked.value = item
    pickedSeason.value = 1
    loadPickedSeasonEpisodeCount()
  }

  // 季号改了就重查集数。用 watch 而不是绑 @change：季号既能敲也能用数字框的上下箭头改，
  // 两条路径都要覆盖
  watch(pickedSeason, () => { loadPickedSeasonEpisodeCount() })

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

  /**
   * 缺集串默认只铺前 30 个。集号是逐个渲染的（每个还带一次点击入口），而一季的集数没有
   * 上界——TMDb 上长篇动画把上千集平铺在一季里是实际存在的形态，全量铺开会在一个几百像素
   * 宽的弹窗里塞进上千个节点。
   */
  const MISSING_PREVIEW_LIMIT = 30
  const missingExpanded = ref(false)

  const allMissingEpisodes = computed<number[]>(() => (progress.value?.missingEpisodes || []) as number[])
  const visibleMissingEpisodes = computed<number[]>(() =>
    missingExpanded.value ? allMissingEpisodes.value : allMissingEpisodes.value.slice(0, MISSING_PREVIEW_LIMIT)
  )
  const missingHiddenCount = computed(() => allMissingEpisodes.value.length - visibleMissingEpisodes.value.length)
  const expandMissing = () => { missingExpanded.value = true }

  /**
   * 缺集里还没播出的那批（后端 `SubscriptionProgress#unairedEpisodes`）。
   * 缺集串照旧显示全部——用户要知道这季还缺几集，藏起来会让缺集数与总集数对不上。
   */
  const unairedMissingEpisodes = computed<number[]>(() => (progress.value?.unairedEpisodes || []) as number[])

  /**
   * 「一键补齐全部」实际会跑的集：缺集减去未播出的。
   * <p>
   * 未播出的集站上不可能有资源，为它们各打一整轮索引器请求（后端单集最多 4~6 步、
   * 每个索引器 30 秒软上限）必然全部落空。一部刚播到第 3 集的 12 集新番，不排掉的话
   * 用户要为 9 个注定搜不到的集白等十几分钟，最后拿到一句「未搜索到匹配资源」。
   * </p>
   */
  const fillableMissingEpisodes = computed<number[]>(() => {
    const unaired = new Set(unairedMissingEpisodes.value)
    return allMissingEpisodes.value.filter((ep) => !unaired.has(ep))
  })

  const showProgress = async (row: any) => {
    currentSubscription.value = row
    progressOpen.value = true
    progressLoading.value = true
    progress.value = null
    missingExpanded.value = false
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
  /**
   * 只看被淘汰的记录。用户翻这张表基本只有一个目的——「这一轮为什么没抓到」，
   * 而通过的记录会把淘汰原因冲散（后端固定回最近 100 条，不分页）。
   */
  const searchLogRejectedOnly = ref(false)
  const visibleSearchLogs = computed(() =>
    searchLogRejectedOnly.value ? searchLogs.value.filter((log: any) => log.accepted !== '1') : searchLogs.value
  )

  const showSearchLogs = async (row: any) => {
    searchLogOpen.value = true
    searchLogLoading.value = true
    searchLogs.value = []
    searchLogRejectedOnly.value = false
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
    descriptionExcludeKeywords: { enabled: false, value: '' as string },
    resolutionWhitelist: { enabled: false, value: '' as string },
    resolutionPriority: { enabled: false, value: '' as string },
    preferredSize: { enabled: false, value: 0 as number }
  })

  const FILTER_OVERRIDE_KEYS = Object.keys(filterOverrideForm) as Array<keyof typeof filterOverrideForm>

  /** 前端用 GB 显示，后端存字节；换算见 composables/sizeUnits */
  const sizeFields = new Set<keyof typeof filterOverrideForm>(['minSize', 'maxSize', 'preferredSize'])

  /**
   * 全局过滤配置的快照，只用于在每一行旁边标一句「全局：40 GB」。
   * <p>
   * 弹窗顶上写着「不勾选的沿用全局过滤规则」，但在此之前页面并不说全局是多少——用户勾上
   * 「体积上限」的那一刻，输入框里是 0，他不知道自己正在把多少改成多少。
   * </p>
   * 整个会话只拉一次：这份配置在 PT 过滤规则页改动，改完再打开本弹窗时页面早已重新加载过。
   */
  const filterOverrideGlobal = ref<Record<string, any> | null>(null)

  /** 布尔型覆盖项的取值文案，与弹窗里的单选按钮保持同一套说法 */
  const BOOLEAN_OVERRIDE_LABELS: Record<string, string> = { '1': '是', '0': '否' }

  /**
   * 某一项的全局取值文案。取不到配置、或该项全局也没设，都返回空串让调用方整段不渲染——
   * 一句「全局：未知」帮不了任何判断，只会占掉一行。
   */
  const globalFilterHint = (key: string): string => {
    const config = filterOverrideGlobal.value
    if (!config) return ''
    const raw = (config as Record<string, any>)[key]
    if (raw === null || raw === undefined || raw === '') return ''
    if (sizeFields.has(key as keyof typeof filterOverrideForm)) {
      const gb = bytesToGb(raw as number)
      return gb ? `全局：${gb} GB` : '全局：不限'
    }
    if (key === 'freeOnly' || key === 'requireChineseSubtitle') {
      return `全局：${BOOLEAN_OVERRIDE_LABELS[String(raw)] ?? String(raw)}`
    }
    return `全局：${raw}`
  }

  const loadFilterOverrideGlobal = async () => {
    if (filterOverrideGlobal.value) return
    try {
      filterOverrideGlobal.value = (await getPtFilterConfigApi()) || {}
    } catch (e) {
      // 拿不到全局配置只是少了个参照，不该挡住用户配置覆盖项本身
      console.error('读取全局过滤配置失败，覆盖弹窗不展示全局参照', e)
      filterOverrideGlobal.value = {}
    }
  }

  /** 取消全部覆盖项。11 项逐个取消勾选太啰嗦，而「退回全局」是很常见的一次性意图 */
  const clearFilterOverride = () => {
    FILTER_OVERRIDE_KEYS.forEach((key) => {
      filterOverrideForm[key].enabled = false
    })
  }

  /** 当前有几项覆盖生效，用于弹窗标题与「全部清除」的禁用态 */
  const filterOverrideCount = computed(() =>
    FILTER_OVERRIDE_KEYS.filter((key) => filterOverrideForm[key].enabled).length
  )

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
        // 体积字段后端存字节，前端显示 GB（保留小数），未定义或0时展示0
        filterOverrideForm[key].value = sizeFields.has(key)
          ? bytesToGb(parsed[key] as number)
          : parsed[key]
      }
    })
    filterOverrideOpen.value = true
    // 不 await：弹窗要立刻打开，全局参照晚一个往返再出现即可
    loadFilterOverrideGlobal()
  }

  const saveFilterOverride = async () => {
    if (!filterOverrideSubId.value) return
    filterOverrideSaving.value = true
    try {
      const override: Record<string, any> = {}
      FILTER_OVERRIDE_KEYS.forEach((key) => {
        if (filterOverrideForm[key].enabled) {
          // 体积字段前端显示 GB，后端存字节（取整到整数字节）
          override[key] = sizeFields.has(key)
            ? gbToBytes(filterOverrideForm[key].value as number)
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
      // 拦截器已经把后端给出的具体原因弹出来了（「候选被过滤规则清光」「下载器并发已满」等，
      // 见 SubscriptionEngine 逐条失败路径）。这里再补一条「推送失败」只会把那条盖掉，
      // 用户看到的又变回没有原因的通用提示——本文件其余 catch 全都只 console.error
      console.error(e)
    } finally {
      pushingSelected.value = false
    }
  }

  /** 格式化体积为人类可读 */
  /** TMDb 海报路径拼完整图片地址，w200 宽度足够列表缩略图使用。
   *  列表卡片与「新增订阅」弹窗都要用，放这里而不是各写一份 */
  const posterUrl = (path: string) => `https://image.tmdb.org/t/p/w200${path}`

  const formatSize = (bytes: number) => {
    if (bytes === 0) return '0 B'
    const units = ['B', 'KB', 'MB', 'GB', 'TB']
    const k = 1024
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + units[i]
  }

  // ---------- 一键补齐全部缺集 ----------

  const searchAllMissingLoading = ref(false)
  /** 已跑完的集数 / 总集数，供弹窗显示「补齐中 3/26」 */
  const searchAllMissingDone = ref(0)
  const searchAllMissingTotal = ref(0)
  /** 用户点了「停止」：当前这一集搜完就收尾，不打断已发出的请求 */
  const searchAllMissingAborted = ref(false)

  const abortSearchAllMissing = () => { searchAllMissingAborted.value = true }

  const handleSearchAllMissing = async () => {
    if (!currentSubscription.value || !fillableMissingEpisodes.value.length) return
    // 订阅要和集号一起快照。这个循环每集都要等一次几十秒的检索（后端单索引器预算 30 秒是
    // 软上限），几十集就是十几二十分钟；期间弹窗点遮罩就能关，用户完全可能去点开另一条
    // 订阅的进度——那会改掉 currentSubscription.value，循环里剩下的集就变成「拿 A 的集号、
    // 按 B 的标题、推给 B 的订阅」，而界面上没有任何迹象。
    const sub = currentSubscription.value
    // 未播出的集在这一步就排掉（见 fillableMissingEpisodes）：它们和别的缺集一样是 MISSING，
    // 但站上不可能有资源，跑进循环只会让用户为一串必然落空的检索白等
    const missing = [...fillableMissingEpisodes.value]
    const skipped = unairedMissingEpisodes.value.length
    searchAllMissingAborted.value = false
    searchAllMissingDone.value = 0
    searchAllMissingTotal.value = missing.length
    searchAllMissingLoading.value = true
    let pushedCount = 0
    try {
      for (const ep of missing) {
        if (searchAllMissingAborted.value) break
        const keyword = `${sub.title} S${pad2(sub.season)}E${pad2(ep)}`
        try {
          const result = await searchSupplementApi(sub.id, { episode: ep, keyword })
          if (result.pushed) pushedCount++
        } catch (e) {
          console.error(`第${ep}集补搜失败：`, e)
        }
        searchAllMissingDone.value++
      }
      const done = searchAllMissingDone.value
      const stoppedTip = searchAllMissingAborted.value ? '（已中止）' : ''
      // 跳过数要说出来，否则「仍缺 12 集」却只搜了 3 集，用户会以为漏跑了
      const skippedTip = skipped ? `，另有 ${skipped} 集未播出已跳过` : ''
      message.success(`已搜索 ${done}/${missing.length} 集${stoppedTip}：${pushedCount} 集已推送下载${skippedTip}`)
      // 进度只在用户还停在这条订阅上时回写，否则会把他正在看的另一条订阅的弹窗内容改掉
      if (currentSubscription.value?.id === sub.id) {
        progress.value = await getSubscriptionProgressApi(sub.id)
      }
      base.getList()
    } finally {
      searchAllMissingLoading.value = false
    }
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

  /**
   * 季号文案。第 0 季在 TMDb 里是特别篇（建订阅弹窗那里也是这个说法），直接写「第 0 季」
   * 用户对不上号。电影没有季，返回空串让调用方整段不渲染。
   */
  const seasonLabel = (sub: any): string => {
    if (!sub || sub.mediaType === 'MOVIE') return ''
    const season = sub.season
    if (season === null || season === undefined) return ''
    return season === 0 ? '特别篇' : `第 ${season} 季`
  }

  /** 本地当天的 yyyy-MM-dd。不能用 toISOString()，那是 UTC，东八区在每天 08:00 前会早一天 */
  const todayString = (): string => {
    const now = new Date()
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
  }

  /**
   * 这一集的播出日期，只取日期部分。
   * <p>
   * `/episodes` 直接返回实体，`airDate` 是 `java.util.Date`，按全局 Jackson 格式序列化成
   * `2026-08-12 00:00:00`——时分秒恒为 0（同步任务按当天零点写入），展示出来只是噪音。
   * </p>
   * 缺失返回空串、调用方整段不渲染：一句「播出日期：未知」不帮用户做任何判断，只占地方。
   */
  const episodeAirDate = (ep: any): string => {
    const raw = ep?.airDate
    return raw ? String(raw).slice(0, 10) : ''
  }

  /**
   * 这一集还没播。未播出的集恒为 MISSING，而用户看到「缺失」第一反应是去查搜索链路、
   * 改关键词、换索引器——真实原因只是还没播，标出来能省掉一整轮排查。
   * <p>
   * 判据与后端 `SearchSupplementService#aired` 一致：只有日期确实晚于今天才算未播出，
   * <b>日期缺失一律按已播出处理</b>（未定档、TMDb 未录入、存量行还没被同步任务扫到都会是
   * 空值，不能让一个不可靠的字段单方面下结论）。
   */
  const episodeUnaired = (ep: any): boolean => {
    const date = episodeAirDate(ep)
    return date ? date > todayString() : false
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
  /** 同 toggleAutoSearch：乐观更新与回滚都在这一侧 */
  const toggleUpgrade = async (row: any, value?: string) => {
    const next = value ?? (row.upgradeEnabled === '1' ? '0' : '1')
    const prev = row.upgradeEnabled
    row.upgradeEnabled = next
    try {
      await updatePtSubscriptionApi({ id: row.id, upgradeEnabled: next })
      message.success(next === '1' ? '已开启洗版' : '已关闭洗版')
    } catch (e) {
      row.upgradeEnabled = prev
      console.error(e)
    }
  }

  /**
   * 开关由这里负责乐观更新与失败回滚。
   *
   * 以前是模板 `v-model="row.autoSearch"` 先改、这里只管发请求与回滚；订阅卡拆成子组件
   * 之后那就是在改 prop（vue/no-mutating-props），而且「谁改的」散在两处。现在改值这件事
   * 只发生在持有 taskList 的这一侧，模板退回 `:model-value` + 事件。
   */
  const toggleAutoSearch = async (row: any, value?: string) => {
    const next = value ?? (row.autoSearch === '1' ? '0' : '1')
    const prev = row.autoSearch
    row.autoSearch = next
    try {
      await updatePtSubscriptionApi({ id: row.id, autoSearch: next })
      message.success(next === '1' ? '已开启自动补搜' : '已关闭自动补搜')
    } catch (e) {
      row.autoSearch = prev
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

  /**
   * 进出批量模式。退出时必须清空已选——勾选框只在批量模式下渲染，残留的选择在界面上
   * 完全不可见，等用户下次点开「批量操作」，「已选 N 项」会凭空出现。
   * <p>
   * 这段原本只有移动端有，PC 页面在模板里内联写的 `selectionMode = !selectionMode`，
   * 于是两端行为不一致。放进 composable 是为了不再有第二份。
   * </p>
   */
  const toggleSelectionMode = () => {
    selectionMode.value = !selectionMode.value
    if (!selectionMode.value) base.selectedIds.value = []
  }

  /** 卡片选中（兼容原接口签名：入参是行对象，内部取 id）。全选/半选判定走 useTaskList 内置的 usePageSelection */
  const toggleSubSelect = (row: any) => base.toggleSelect(row.id)

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

  // PC 端卡片网格页把首次加载交给 useGridPageSize（要先量出列数）
  if (options.autoLoad !== false) base.getList()

  return {
    ...base,
    // 建订阅向导
    subscribeOpen, searchLoading, subscribeLoading, searchResults, searchForm,
    picked, pickedSeason, openSubscribeDialog, doSearch, pick, confirmSubscribe,
    pickedSeasonEpisodeCount, pickedSeasonCountLoading,
    // 进度
    progressOpen, progressLoading, progress, currentSubscription, showProgress, showProgressById,
    // 缺集串截断展示
    visibleMissingEpisodes, missingHiddenCount, missingExpanded, expandMissing,
    unairedMissingEpisodes, fillableMissingEpisodes,
    // 每集明细 + 手动重置
    episodeDetailOpen, episodeDetailLoading, episodeDetail, resettingEpisode,
    loadEpisodeDetail, handleResetEpisode, episodeStateLabel, episodeStateColor,
    qualityLabel, upgradeStateHint, seasonLabel, episodeAirDate, episodeUnaired,
    // 匹配日志
    searchLogOpen, searchLogLoading, searchLogs, showSearchLogs,
    searchLogRejectedOnly, visibleSearchLogs,
    // 过滤规则覆盖
    filterOverrideOpen, filterOverrideSaving, filterOverrideForm,
    openFilterOverride, saveFilterOverride,
    globalFilterHint, clearFilterOverride, filterOverrideCount,
    // 搜索补集
    searchDialogOpen, searchDialogLoading, searchDialogKeyword, searchManualSelect,
    openSeasonSearch, openEpisodeSearch, confirmSearch,
    // 手动选择候选
    candidateDialogOpen, candidates, pushingSelected, pushSelectedCandidate, formatSize, posterUrl,
    // 一键补齐全部缺集
    searchAllMissingLoading, handleSearchAllMissing, toggleAutoSearch, toggleUpgrade,
    searchAllMissingDone, searchAllMissingTotal, searchAllMissingAborted, abortSearchAllMissing,
    // 行操作
    handleRefresh, handlePause, handleResume, handleRemove,
    // 批量操作
    selectionMode, toggleSelectionMode,
    toggleSubSelect, isSubSelected, handleBatchPause, handleBatchResume,
    // 移动端分页 & 搜索面板
    totalPages, prevPage, nextPage, handleSizeChange, searchCollapsed
  }
}
