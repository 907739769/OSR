import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { message } from '@/composables/useMessage'
import { useUserStore } from '@/stores/user'
import { getRoutePathForComponent } from '@/router'
import { getPtIndexerListApi } from '@/api/openlist/ptIndexer'
import {
  searchResourceApi,
  listResourceDownloadersApi,
  pushResourceApi,
  type ResourceItem
} from '@/api/openlist/ptSearch'
import { useCandidateFilter } from './useCandidateFilter'
import { formatSize } from './sizeUnits'

/**
 * 解析不出季号也解析不出集号的按电影处理，与后端 `ResourceSearchService#looksLikeMovie` 同一判据：
 * 那边据此决定直接下载落进「电影」还是「剧集」目录，这边据此决定转订阅时先搜电影还是剧集。
 */
export const looksLikeMovie = (item: Pick<ResourceItem, 'parsedSeason' | 'parsedEpisode'>) =>
  item.parsedSeason == null && item.parsedEpisode == null

/** 转为订阅时带去订阅页的查询参数：片名用解析出来的（去掉了分辨率、发布组那些），解析不出才用原标题 */
export const subscribeQuery = (item: ResourceItem) => ({
  subscribe: item.parsedTitle || item.title,
  mediaType: looksLikeMovie(item) ? 'MOVIE' : 'TV'
})

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
  const keyword = ref('')
  const indexerIds = ref<number[]>([])
  const indexerOptions = ref<{ id: number; name: string }[]>([])
  const searching = ref(false)
  /** 搜过至少一次：区分「还没搜」与「搜了但没有结果」，两者的空态文案不同 */
  const searched = ref(false)
  const results = ref<ResourceItem[]>([])
  const rejectedCount = ref(0)
  /** 只看全局规则会放行的 */
  const passedOnly = ref(false)

  const { filter, facets, filteredCandidates, filterCount, resetFilter } = useCandidateFilter(results)
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
    searching.value = true
    try {
      const res = await searchResourceApi({
        keyword: kw,
        indexerIds: indexerIds.value.length ? [...indexerIds.value] : undefined
      })
      results.value = res?.items || []
      rejectedCount.value = res?.rejectedCount || 0
      searched.value = true
    } catch (e) {
      // 具体原因（没有启用的索引器、所选站点全部不可用）已由拦截器弹出
      console.error('[资源搜索] 搜索失败:', e)
    } finally {
      searching.value = false
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

  return {
    isAdmin,
    keyword, indexerIds, indexerOptions, searching, searched, results, rejectedCount, handleSearch,
    filter, facets, activeFilterCount, clearFilters, passedOnly, visibleResults,
    pushOpen, pushTarget, downloaders, downloaderId, pushing, openPush, confirmPush,
    toSubscribe,
    formatSize
  }
}
