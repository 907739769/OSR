import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { message } from '@/composables/useMessage'
import { getRoutePathForComponent } from '@/router'
import {
  getPtHealthApi,
  enableAutoSearchApi,
  searchMissingApi,
  type EpisodeHealthReport,
  type SubscriptionHealthItem
} from '@/api/openlist/ptHealth'

/**
 * 分档展示口径，与后端 EpisodeHealthBucket 一一对应。
 * 顺序即页面上标签页的顺序：处置成本从「点一下就能改」到「只能等」。
 */
export const BUCKET_META: Record<string, { label: string; color: string; icon: string; hint: string }> = {
  OVERDUE_MISSING: {
    label: '逾期缺失',
    color: 'error',
    icon: 'mdi-alert-circle-outline',
    hint: '已经播出好几天了，却一个资源都没匹配到。这一档是体检真正要抓的问题'
  },
  OVERDUE_IN_FLIGHT: {
    label: '在途逾期',
    color: 'info',
    icon: 'mdi-download-outline',
    hint: '已经推给下载器了，卡在下载或上传入库这一段。再补搜一次不解决问题'
  },
  BLOCKED: {
    label: '已熔断',
    color: 'warning',
    icon: 'mdi-cancel',
    hint: '连续失败达到阈值，自动重试已经停了，需要人工处理'
  },
  NO_AIR_DATE: {
    label: '无播出日期',
    color: 'default',
    icon: 'mdi-calendar-question',
    hint: '未定档、TMDb 未录入、或还没被同步任务扫到；电影订阅恒在这一档。算不出逾期天数'
  }
}

/**
 * 诊断展示口径，与后端 EpisodeHealthDiagnosis 一一对应。
 * advice 与后端枚举上的文案保持同义即可，不必逐字相同——通知走后端那份，页面走这份。
 */
export const DIAGNOSIS_META: Record<string, { label: string; color: string; advice: string }> = {
  AUTO_SEARCH_OFF: {
    label: '未开启自动补搜',
    color: 'error',
    advice: '这条订阅的自动补搜是关的，缺集不会被自动搜索。可以就地开启，或先「立即补搜」试一次'
  },
  BLOCKED: {
    label: '已熔断',
    color: 'warning',
    advice: '连续失败已达阈值，自动重试已停止。到订阅详情页重置该集，或换个资源'
  },
  SEARCH_NO_CANDIDATE: {
    label: '补搜落空·未搜到候选',
    color: 'warning',
    advice: '索引器上没有任何候选。检查订阅的标题/季号、索引器是否可用、该资源是否真的存在'
  },
  SEARCH_ALL_REJECTED: {
    label: '补搜落空·候选被过滤',
    color: 'warning',
    advice: '搜到了候选但全被过滤规则淘汰。按下方的淘汰原因放宽对应规则'
  },
  UPLOAD_PENDING: {
    label: '已下好·等待入库',
    color: 'info',
    advice: '种子里确实有这个文件，卡住的是上传或刮削。到「复制记录」页看有没有失败任务，不需要重下'
  },
  DOWNLOADING: {
    label: '下载中',
    color: 'info',
    advice: '已推送下载器。若长时间不动，到「下载记录」页看这条种子的状态'
  },
  SEARCHING: {
    label: '等待下一轮补搜',
    color: 'success',
    advice: '自动补搜已开启且尚未落空，下次到期时会再搜一次'
  }
}

export function bucketMeta(code: string) {
  return BUCKET_META[code] || { label: code || '未知', color: 'default', icon: 'mdi-help-circle-outline', hint: '' }
}

export function diagnosisMeta(code: string) {
  return DIAGNOSIS_META[code] || { label: code || '未知', color: 'default', advice: '' }
}

/** TMDb 海报。w92 够列表里的小图用，别拉 w200 白耗流量 */
export function posterUrl(path: string | null) {
  return path ? `https://image.tmdb.org/t/p/w92${path}` : ''
}

const EMPTY_REPORT: EpisodeHealthReport = {
  overdueDays: 3,
  subscriptionCount: 0,
  episodeCount: 0,
  bucketCounts: {},
  diagnosisCounts: {},
  subscriptions: []
}

/**
 * 缺集体检共享逻辑，PC 与移动端共用。
 *
 * 刻意不套 useTaskList/useRecordList：那两个是分页列表的骨架，而体检接口本来就不分页——
 * 「一共几部剧有问题」是用户打开这个页面要问的第一个问题，把它藏进分页器里等于白做。
 */
export function usePtHealth() {
  const loading = ref(false)
  const acting = ref(false)
  const report = ref<EpisodeHealthReport>(EMPTY_REPORT)
  /** 当前筛选的分档；空串=全部 */
  const activeBucket = ref('')

  const load = async () => {
    loading.value = true
    try {
      report.value = (await getPtHealthApi()) || EMPTY_REPORT
    } catch (e) {
      console.error(e)
      report.value = EMPTY_REPORT
    } finally {
      loading.value = false
    }
  }

  /** 只保留在该分档下确实有集的订阅，且订阅里的集也只留该分档的——否则筛完还是整条全展开 */
  const subscriptions = computed<SubscriptionHealthItem[]>(() => {
    const bucket = activeBucket.value
    if (!bucket) return report.value.subscriptions
    return report.value.subscriptions
      .filter((s) => s.buckets.includes(bucket))
      .map((s) => ({ ...s, episodes: s.episodes.filter((e) => e.bucket === bucket) }))
  })

  /** 有集在场的分档才做成标签页，空档不显示 */
  const bucketTabs = computed(() =>
    Object.keys(BUCKET_META)
      .filter((key) => (report.value.bucketCounts[key] || 0) > 0)
      .map((key) => ({ key, count: report.value.bucketCounts[key], ...bucketMeta(key) }))
  )

  /**
   * 当前视图里还没开自动补搜的订阅。
   * 「一键开启」的作用域跟着筛选走：用户在「逾期缺失」标签下点它，改的就该是眼前这批。
   */
  const autoSearchOffIds = computed(() =>
    subscriptions.value.filter((s) => !s.autoSearch).map((s) => s.subId)
  )

  const handleEnableAutoSearch = async (ids?: number[]) => {
    const targets = ids && ids.length > 0 ? ids : autoSearchOffIds.value
    if (targets.length === 0) {
      message.info('当前列表里没有需要开启自动补搜的订阅')
      return
    }
    acting.value = true
    try {
      const count = await enableAutoSearchApi(targets)
      message.success(`已为 ${count} 条订阅开启自动补搜`)
      await load()
    } catch (e) {
      // 拦截器已经弹过后端的真实原因，这里再补一句通用文案只会把它盖掉
      console.error(e)
    } finally {
      acting.value = false
    }
  }

  const handleSearchNow = async (subId: number) => {
    acting.value = true
    try {
      const msg = await searchMissingApi(subId)
      message.success(msg || '补搜完成')
      await load()
    } catch (e) {
      console.error(e)
    } finally {
      acting.value = false
    }
  }

  const router = useRouter()
  /**
   * 跳到订阅页并展开它的进度弹窗。query 用 id 而不是 subId——订阅页两端读的都是 route.query.id。
   * 路径不写死：后端菜单 path 历史上有 /openlist 与 /openliststrm 两种前缀，写死会跳 404。
   */
  const openSubscription = (subId: number) => {
    const path = getRoutePathForComponent('openlist/ptSubscription/index')
    if (path) router.push({ path, query: { id: String(subId) } })
  }

  const setBucket = (bucket: string) => {
    activeBucket.value = bucket
  }

  load()

  return {
    loading, acting, report, activeBucket, subscriptions, bucketTabs, autoSearchOffIds,
    load, handleEnableAutoSearch, handleSearchNow, openSubscription, setBucket
  }
}
