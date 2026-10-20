import { ref, computed, reactive, toRaw, getCurrentInstance, onMounted, onUnmounted, type Ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from '@/composables/useMessage'
import { useUserStore } from '@/stores/user'
import { getRoutePathForComponent } from '@/router'
import { getPtIndexerListApi } from '@/api/openlist/ptIndexer'
import {
  searchResourceApi,
  listResourceDownloadersApi,
  pushResourceApi,
  refreshSubscribedApi,
  type ResourceItem,
  type ResourceSearchResult,
  type WorkRef
} from '@/api/openlist/ptSearch'
import { useCandidateFilter, emptyCandidateFilter, type CandidateFilterState } from './useCandidateFilter'
import { loadCachedSearch, saveCachedSearch } from './ptSearchCache'
import { formatRelativeTime } from './relativeTime'
import { formatSize } from './sizeUnits'

/**
 * 解析不出季号也解析不出集号的按电影处理，与后端 `ResourceSearchService#looksLikeMovie` 同一判据：
 * 那边据此决定直接下载落进「电影」还是「剧集」目录，这边据此决定转订阅时先搜电影还是剧集。
 */
export const looksLikeMovie = (item: Pick<ResourceItem, 'parsedSeason' | 'parsedEpisode'>) =>
  item.parsedSeason == null && item.parsedEpisode == null

/**
 * 转为订阅时带去订阅页的查询参数。
 * 识别出作品时用它的规范名与类型：订阅页拿这个词去搜 TMDb，规范名一搜就是它，
 * 而罗马音 / 拼音命名的种子（`Gei a ma de qing shu`）拿解析出的片名去搜多半落空。
 * 没识别出才退回解析出的片名（去掉了分辨率、发布组那些），再不行用原标题。
 */
export const subscribeQuery = (item: ResourceItem) => {
  if (item.matchedTitle) {
    return { subscribe: item.matchedTitle, mediaType: item.mediaType === 'MOVIE' ? 'MOVIE' : 'TV' }
  }
  return { subscribe: item.parsedTitle || item.title, mediaType: looksLikeMovie(item) ? 'MOVIE' : 'TV' }
}

const emptyLookup = () => ({
  enabled: true, distinctWorks: 0, identifiedWorks: 0, skipped: false, truncated: false, unavailable: false
})

const workKey = (w: WorkRef) => `${w.mediaType}/${w.tmdbId}`

/**
 * 搜索状态放在模块级，不放在页面组件里：这一页不在 keep-alive 里，状态跟着组件走的话，
 * 「转为订阅」跳走再回来就是一张空页；一次几十秒的搜索没等完就切走，结果回来也没处放。
 * 放在这里，页面卸载不影响在途的请求，回来时没搜完的接着转圈、搜完的直接摆出来。
 *
 * 按用户归属：换了账号（同一个 SPA 里退出再登录）就整份重建，不把上一个人的结果和「已订阅」标记留给下一个人。
 * 刷新与 PWA 被杀之后的那一层由 `ptSearchCache` 的 localStorage 负责，建状态时从那里恢复。
 */
interface SearchState {
  ownerId: number | null
  keyword: Ref<string>
  indexerIds: Ref<number[]>
  searching: Ref<boolean>
  /** 搜过至少一次：区分「还没搜」与「搜了但没有结果」，两者的空态文案不同 */
  searched: Ref<boolean>
  results: Ref<ResourceItem[]>
  rejectedCount: Ref<number>
  lookup: Ref<ReturnType<typeof emptyLookup>>
  /** 产出当前这批结果的关键词与站点：输入框里的值可能已经改过，不能拿它说明结果 */
  resultKeyword: Ref<string>
  resultIndexerIds: Ref<number[]>
  /** 当前这批结果搜完的时刻（毫秒）；恢复出来的旧结果靠它说清「多久以前」 */
  searchedAt: Ref<number | null>
  /** 只看全局规则会放行的 */
  passedOnly: Ref<boolean>
  filter: CandidateFilterState
  /** 每发一次搜索加一：只认最后一次的响应，先发后到的旧响应直接丢掉 */
  seq: number
}

let shared: SearchState | null = null

function applyResult(state: SearchState, res: ResourceSearchResult | null | undefined) {
  state.results.value = res?.items || []
  state.rejectedCount.value = res?.rejectedCount || 0
  state.lookup.value = {
    enabled: res?.tmdbLookupEnabled ?? true,
    distinctWorks: res?.distinctWorks ?? 0,
    identifiedWorks: res?.identifiedWorks ?? 0,
    skipped: res?.tmdbLookupSkipped ?? false,
    truncated: res?.tmdbLookupTruncated ?? false,
    unavailable: res?.tmdbLookupUnavailable ?? false
  }
}

/** 把当前这批结果写进 localStorage；没搜过、或用户未知时不写 */
function persist(state: SearchState) {
  if (!state.searched.value || state.searchedAt.value == null) return
  const lookup = state.lookup.value
  saveCachedSearch(state.ownerId, {
    keyword: state.resultKeyword.value,
    indexerIds: [...state.resultIndexerIds.value],
    searchedAt: state.searchedAt.value,
    result: {
      candidateCount: state.results.value.length,
      rejectedCount: state.rejectedCount.value,
      tmdbLookupEnabled: lookup.enabled,
      distinctWorks: lookup.distinctWorks,
      identifiedWorks: lookup.identifiedWorks,
      tmdbLookupSkipped: lookup.skipped,
      tmdbLookupTruncated: lookup.truncated,
      tmdbLookupUnavailable: lookup.unavailable,
      items: toRaw(state.results.value).map(i => toRaw(i))
    }
  })
}

function createState(ownerId: number | null): SearchState {
  const state: SearchState = {
    ownerId,
    keyword: ref(''),
    indexerIds: ref<number[]>([]),
    searching: ref(false),
    searched: ref(false),
    results: ref<ResourceItem[]>([]),
    rejectedCount: ref(0),
    lookup: ref(emptyLookup()),
    resultKeyword: ref(''),
    resultIndexerIds: ref<number[]>([]),
    searchedAt: ref<number | null>(null),
    passedOnly: ref(false),
    filter: reactive<CandidateFilterState>(emptyCandidateFilter()),
    seq: 0
  }
  const cached = loadCachedSearch(ownerId)
  if (cached) {
    applyResult(state, cached.result)
    state.keyword.value = cached.keyword
    state.indexerIds.value = [...cached.indexerIds]
    state.resultKeyword.value = cached.keyword
    state.resultIndexerIds.value = [...cached.indexerIds]
    state.searchedAt.value = cached.searchedAt
    state.searched.value = true
  }
  return state
}

function sharedState(ownerId: number | null): SearchState {
  if (!shared || shared.ownerId !== ownerId) {
    shared = createState(ownerId)
  }
  return shared
}

/** 只给测试用：丢掉模块级状态，下一次调用从 localStorage（或空白）重建 */
export function resetResourceSearchState() {
  shared = null
}

/**
 * 资源搜索页（PC 与移动端共用）：不建订阅，按关键词搜全部站点，看站上有什么。
 *
 * 结果里的「规则」一列只标注不淘汰——用户来这里正是要看站上到底有什么；被全局过滤规则挡的那些
 * 恰好回答了「为什么我订了却没自动下」。筛选复用订阅内候选弹窗的 `useCandidateFilter`，两处判据一致。
 */
export function usePtResourceSearch() {
  const router = useRouter()
  const userStore = useUserStore()

  /** 与后端 `BaseController#isAdmin`（`ROLE_admin`）同一判据。直接下载只对管理员开放 */
  const isAdmin = computed(() => (userStore.roles || []).includes('admin'))

  // ---------- 搜索 ----------
  const state = sharedState(userStore.userInfo?.userId ?? null)
  const {
    keyword, indexerIds, searching, searched, results, rejectedCount, passedOnly, lookup,
    resultKeyword, resultIndexerIds, searchedAt
  } = state
  const indexerOptions = ref<{ id: number; name: string }[]>([])

  /**
   * 「多久以前」要跟着时间走：页面开着放半小时，还写「刚刚」就是在说假话。
   * 只在组件里挂计时器（单测直接调 composable 时没有组件实例）
   */
  const now = ref(Date.now())
  if (getCurrentInstance()) {
    let timer: ReturnType<typeof setInterval> | undefined
    onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, 60_000) })
    onUnmounted(() => clearInterval(timer))
  }
  /** 当前这批结果是多久以前搜的；恢复出来的旧结果不能装成刚搜的（「免费」标签几小时就会过期） */
  const resultAge = computed(() => (searchedAt.value == null ? '' : formatRelativeTime(searchedAt.value, now.value)))

  /**
   * 识别出的不同作品数，按「媒体类型 + matchedTmdbId」去重：一部剧 12 集是 12 行、1 个作品；
   * tv/1399 与 movie/1399 是两部作品。它与 lookup.distinctWorks 不是同一口径（那是标题组数，
   * 同一部作品的中文名与英文名是两组），所以两者不能摆成「X / Y」。
   */
  const matchedWorks = computed(() =>
    new Set(results.value.filter(i => i.matchedTmdbId).map(i => `${i.mediaType}/${i.matchedTmdbId}`)).size)

  /** 识别这一步完整跑过：只有这时没带作品的那一行才能说「识别不出」，否则真相是「没识别」 */
  const lookupComplete = computed(() =>
    lookup.value.enabled && !lookup.value.skipped && !lookup.value.truncated && !lookup.value.unavailable)

  /**
   * 识别没跑、没跑完、或整体被上限挡掉时必须有这句话。
   * 少了它，用户看到的是一片空白，而空白会被读成「这些种子不是任何作品」——真相是没识别。
   * 文案里不写死上限数字：那个 20 在后端，写在这儿早晚和它漂移。
   */
  const lookupNote = computed(() => {
    if (!lookup.value.enabled) return '未识别作品：识别功能已关闭'
    if (lookup.value.unavailable) return '未识别作品：TMDb key 未配置'
    if (lookup.value.truncated) return `识别未跑完：${lookup.value.distinctWorks} 组标题只识别了 ${lookup.value.identifiedWorks} 组，稍后再搜一次会更全`
    if (lookup.value.skipped) {
      return `标题太杂，只识别了一部分：${lookup.value.distinctWorks} 组不同标题里只问了种子最多的那些，认出 ${matchedWorks.value} 个作品；换个更具体的关键词会更全`
    }
    return ''
  })

  /** 识别跑完时的一句话小结；没有可识别的标题时不说话 */
  const lookupSummary = computed(() => {
    if (!lookupComplete.value || !lookup.value.distinctWorks) return ''
    const missed = lookup.value.distinctWorks - lookup.value.identifiedWorks
    const head = matchedWorks.value ? `识别出 ${matchedWorks.value} 个作品` : '没有识别出作品'
    return missed > 0 ? `${head}，${missed} 组标题识别不出` : head
  })

  const { filter, facets, filteredCandidates, filterCount, resetFilter } = useCandidateFilter(results, state.filter)
  const visibleResults = computed<ResourceItem[]>(() =>
    passedOnly.value ? filteredCandidates.value.filter((i: ResourceItem) => !i.ruleRejection) : filteredCandidates.value)
  const activeFilterCount = computed(() => filterCount.value + (passedOnly.value ? 1 : 0))
  const clearFilters = () => {
    resetFilter()
    passedOnly.value = false
  }

  /** 站点下拉只列启用中的；拉回来后把已选里不再启用的剔掉（理由同订阅内搜索弹窗） */
  const loadIndexerOptions = async () => {
    try {
      const res = await getPtIndexerListApi({ pageNum: 1, pageSize: 200, enabled: '1' })
      indexerOptions.value = (res.records || []).map((r: any) => ({ id: r.id, name: r.name }))
      const alive = new Set(indexerOptions.value.map(o => o.id))
      indexerIds.value = indexerIds.value.filter(id => alive.has(id))
    } catch (e) {
      console.error('[资源搜索] 加载站点列表失败:', e)
    }
  }

  const handleSearch = async () => {
    const kw = keyword.value.trim()
    if (kw.length < 2) {
      message.warning('关键词至少 2 个字')
      return
    }
    const seq = ++state.seq
    const ids = [...indexerIds.value]
    searching.value = true
    try {
      const res = await searchResourceApi({ keyword: kw, indexerIds: ids.length ? ids : undefined })
      // 期间又发了一次搜索、或者换了账号：这次的响应已经不是用户要看的了
      if (seq !== state.seq || shared !== state) return
      applyResult(state, res)
      // 新的一批结果，上一批的筛选取值在这里未必存在。页面开着时 useCandidateFilter 的 watch 也会清，
      // 但搜索是在页面卸载期间完成的话没有 watch 在跑，回来会带着一套对不上的筛选
      Object.assign(state.filter, emptyCandidateFilter())
      resultKeyword.value = kw
      resultIndexerIds.value = ids
      searchedAt.value = Date.now()
      now.value = searchedAt.value
      searched.value = true
      persist(state)
    } catch (e) {
      // 具体原因（没有启用的索引器、所选站点全部不可用）已由拦截器弹出
      console.error('[资源搜索] 搜索失败:', e)
    } finally {
      // 后发的那次还在跑时不能替它把转圈停掉
      if (seq === state.seq) searching.value = false
    }
  }

  /** 照着当前这批结果的关键词与站点再搜一次（输入框可能已经改过，这里搜的是结果上写着的那个） */
  const researchLast = () => {
    keyword.value = resultKeyword.value
    indexerIds.value = [...resultIndexerIds.value]
    return handleSearch()
  }

  /**
   * 重判「已订阅」：用户多半是从「转为订阅」回来的，刚订上的那部剧还标着未订阅，读起来就是没订成功。
   * 为此重搜是几十秒的索引器检索，这里只是一条按 tmdb_id 的查询。
   * 原地改每一行而不是换掉整个数组：换数组会触发筛选的 watch，把用户的筛选条件清掉。
   */
  const refreshSubscribed = async () => {
    const works = new Map<string, WorkRef>()
    for (const i of results.value) {
      if (i.matchedTmdbId && i.mediaType) {
        const w = { tmdbId: i.matchedTmdbId, mediaType: i.mediaType }
        works.set(workKey(w), w)
      }
    }
    if (!works.size) return
    const seq = state.seq
    try {
      const subscribed = new Set(((await refreshSubscribedApi([...works.values()])) || []).map(workKey))
      // 期间搜了新的一批：这份答案是给旧那批的
      if (seq !== state.seq || shared !== state) return
      for (const i of results.value) {
        if (i.matchedTmdbId && i.mediaType) i.subscribed = subscribed.has(workKey({ tmdbId: i.matchedTmdbId, mediaType: i.mediaType }))
      }
      persist(state)
    } catch (e) {
      // 刷新不到就保留旧标记；silent 调用，不弹提示
      console.error('[资源搜索] 刷新已订阅标记失败:', e)
    }
  }

  // ---------- 直接下载 ----------
  const pushOpen = ref(false)
  const pushTarget = ref<ResourceItem | null>(null)
  const downloaders = ref<{ id: number; name: string }[]>([])
  const downloaderId = ref<number | null>(null)
  const pushing = ref(false)

  /** 每次打开都重拉下载器：下载器页刚停用/新增的要立刻反映出来；只有一台时直接选上 */
  const openPush = async (item: ResourceItem) => {
    pushTarget.value = item
    pushOpen.value = true
    try {
      downloaders.value = (await listResourceDownloadersApi()) || []
    } catch (e) {
      downloaders.value = []
      console.error('[资源搜索] 加载下载器失败:', e)
    }
    if (!downloaders.value.some(d => d.id === downloaderId.value)) {
      downloaderId.value = downloaders.value.length === 1 ? downloaders.value[0].id : null
    }
  }

  const confirmPush = async () => {
    if (!pushTarget.value) return
    if (!downloaderId.value) {
      message.warning('请选择下载器')
      return
    }
    pushing.value = true
    try {
      const msg = await pushResourceApi({ ...pushTarget.value, downloaderId: downloaderId.value })
      message.success(typeof msg === 'string' && msg ? msg : '已推送')
      pushOpen.value = false
    } catch (e) {
      // 下载器拒绝的真实原因已由拦截器弹出，不要用通用文案盖掉
      console.error('[资源搜索] 推送失败:', e)
    } finally {
      pushing.value = false
    }
  }

  // ---------- 转为订阅 ----------
  /** 跳到订阅页并打开建订阅弹窗、用解析出的片名搜好 TMDb；没有订阅页权限时说清楚而不是跳个 404 */
  const toSubscribe = (item: ResourceItem) => {
    const path = getRoutePathForComponent('openlist/ptSubscription/index')
    if (!path) {
      message.warning('没有「订阅管理」页面的权限，无法转为订阅')
      return
    }
    router.push({ path, query: subscribeQuery(item) })
  }

  loadIndexerOptions()
  // 进页面时手里已经有结果（上次留下的、或从 localStorage 恢复的）：重判一次已订阅
  if (results.value.length && !searching.value) refreshSubscribed()

  return {
    isAdmin,
    keyword, indexerIds, indexerOptions, searching, searched, results, rejectedCount, handleSearch,
    resultKeyword, resultAge, researchLast, refreshSubscribed,
    lookup, matchedWorks, lookupNote, lookupSummary, lookupComplete,
    filter, facets, activeFilterCount, clearFilters, passedOnly, visibleResults,
    pushOpen, pushTarget, downloaders, downloaderId, pushing, openPush, confirmPush,
    toSubscribe,
    formatSize
  }
}
