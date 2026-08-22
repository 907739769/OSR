import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { getRoutePathForComponent } from '@/router'
import {
  getPtHealthApi,
  enableAutoSearchApi,
  searchMissingApi,
  setHealthIgnoredApi,
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
    icon: 'circle-alert',
    hint: '已经播出好几天了，却一个资源都没匹配到。这一档是体检真正要抓的问题'
  },
  OVERDUE_IN_FLIGHT: {
    label: '在途逾期',
    color: 'info',
    icon: 'download',
    hint: '已经推给下载器了，卡在下载或上传入库这一段。再补搜一次不解决问题'
  },
  BLOCKED: {
    label: '已熔断',
    color: 'warning',
    icon: 'ban',
    hint: '连续失败达到阈值，自动重试已经停了，需要人工处理'
  },
  NO_AIR_DATE: {
    label: '无播出日期',
    color: 'default',
    icon: 'calendar-off',
    hint: '未定档、TMDb 未录入、或还没被同步任务扫到，算不出逾期天数。电影订阅不参与体检，不会出现在这里'
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
  return BUCKET_META[code] || { label: code || '未知', color: 'default', icon: 'circle-question-mark', hint: '' }
}

export function diagnosisMeta(code: string) {
  return DIAGNOSIS_META[code] || { label: code || '未知', color: 'default', advice: '' }
}

/** TMDb 海报。w92 够列表里的小图用，别拉 w200 白耗流量 */
export function posterUrl(path: string | null) {
  return path ? `https://image.tmdb.org/t/p/w92${path}` : ''
}

/**
 * 空报告。做成工厂而不是模块级常量：常量会被多个 usePtHealth() 实例共享同一份引用，
 * 将来任何一处就地改动 report.value 都会污染到别的实例，而那种 bug 极难追。
 */
const emptyReport = (): EpisodeHealthReport => ({
  overdueDays: 3,
  subscriptionCount: 0,
  episodeCount: 0,
  bucketCounts: {},
  diagnosisCounts: {},
  ignoredCount: 0,
  subscriptions: []
})

/**
 * 缺集体检共享逻辑，PC 与移动端共用。
 *
 * 刻意不套 useTaskList/useRecordList：那两个是分页列表的骨架，而体检接口本来就不分页——
 * 「一共几部剧有问题」是用户打开这个页面要问的第一个问题，把它藏进分页器里等于白做。
 */
export function usePtHealth() {
  const loading = ref(false)
  /**
   * 正在执行动作的那一条订阅 id；null 表示没有。
   * <p>
   * 原先是一个全局的 acting 布尔量，整页每一行的按钮都绑它——点第 3 行的「立即补搜」，
   * 十几行的按钮同时转圈并禁用，用户看不出到底是哪一行在跑；而这个动作本来就要跑
   * 三十秒到一分钟，整页看起来就是卡死了。请求级/行级的状态不能存进一个共享槽位。
   * </p>
   */
  const actingSubId = ref<number | null>(null)
  /** 批量开启自动补搜的进行中标志，与逐行动作分开 */
  const batchActing = ref(false)
  const report = ref<EpisodeHealthReport>(emptyReport())
  /**
   * 这次加载是否失败了。
   * <p>
   * 不能只靠「报告为空」来表达失败：空报告渲染出来是一个绿色对勾 +「没有发现缺集」——
   * <b>接口挂了和一切正常长得一模一样</b>，而拦截器那条错误提示几秒后就消失了。
   * 对一个体检页来说这是最不该给的错误答案。
   * </p>
   */
  const loadFailed = ref(false)
  /** 上次成功加载的时刻，页面开着放一天时用来提示数据有多旧 */
  const lastLoadedAt = ref<Date | null>(null)
  /** 当前筛选的分档；空串=全部 */
  const activeBucket = ref('')
  /** 当前筛选的诊断；空串=全部。与分档是两个维度，可叠加 */
  const activeDiagnosis = ref('')
  /**
   * 是否把已忽略的订阅也列出来。
   * 忽略必须配一个能找回来的入口，否则它就是个不可撤销的操作——
   * 转移做种那边「停止重试必须配一个解除入口」是同一条教训。
   */
  const includeIgnored = ref(false)

  const load = async () => {
    loading.value = true
    try {
      report.value = (await getPtHealthApi(includeIgnored.value)) || emptyReport()
      loadFailed.value = false
      lastLoadedAt.value = new Date()
      // 选中的档/诊断可能被这一轮动作清空（比如刚把「已熔断」那两集处理掉）。
      // 标签会因为计数归零而消失，筛选却还留着——那时列表是空的、没有任何 chip 是选中态、
      // 顶上却写着「12 部作品 47 集」，用户根本看不出自己还在筛选中。
      resetFiltersIfGone()
    } catch (e) {
      console.error(e)
      report.value = emptyReport()
      loadFailed.value = true
    } finally {
      loading.value = false
    }
  }

  /** 选中的筛选项在新报告里已经没有条目了就退回「全部」 */
  const resetFiltersIfGone = () => {
    if (activeBucket.value && !(report.value.bucketCounts[activeBucket.value] > 0)) {
      activeBucket.value = ''
    }
    if (activeDiagnosis.value && !(report.value.diagnosisCounts[activeDiagnosis.value] > 0)) {
      activeDiagnosis.value = ''
    }
  }

  /**
   * 按分档 + 诊断两维筛选。
   * <p>
   * 命中的订阅里，集也只留符合条件的那些——否则筛完还是整条全展开，等于没筛。
   * 两维是叠加关系（「逾期缺失」且「候选被过滤」），因为它们回答的是不同的问题：
   * 分档说的是处置方向（去看搜索链路还是去看下载链路），诊断说的是具体成因。
   * </p>
   */
  const subscriptions = computed<SubscriptionHealthItem[]>(() => {
    const bucket = activeBucket.value
    const diagnosis = activeDiagnosis.value
    if (!bucket && !diagnosis) return report.value.subscriptions
    return report.value.subscriptions
      .filter((s) => (!bucket || s.buckets.includes(bucket))
        && (!diagnosis || s.diagnoses.includes(diagnosis)))
      .map((s) => ({
        ...s,
        episodes: s.episodes.filter((e) => (!bucket || e.bucket === bucket)
          && (!diagnosis || e.diagnosis === diagnosis))
      }))
      // 诊断挂在集上，按诊断筛完可能把一条订阅的集全滤光（该订阅的其它集是别的诊断）
      .filter((s) => s.episodes.length > 0)
  })

  /** 当前筛选下的作品数与集数。左上角的汇总要跟着筛选走，否则会和选中的档位数字打架 */
  const filteredCount = computed(() => ({
    subscriptionCount: subscriptions.value.length,
    episodeCount: subscriptions.value.reduce((sum, s) => sum + s.episodes.length, 0)
  }))

  /** 是否处于筛选态，供页面决定汇总文案说「共」还是「筛出」 */
  const filtering = computed(() => Boolean(activeBucket.value || activeDiagnosis.value))

  /** 有集在场的分档才做成标签页，空档不显示 */
  const bucketTabs = computed(() =>
    Object.keys(BUCKET_META)
      .filter((key) => (report.value.bucketCounts[key] || 0) > 0)
      .map((key) => ({ key, count: report.value.bucketCounts[key], ...bucketMeta(key) }))
  )

  /**
   * 诊断标签页。后端一直在算并返回 diagnosisCounts，此前前端一次都没用过。
   * 诊断比分档更贴近「我现在该做什么」：「未开启自动补搜」这一档正好对应页头那个批量按钮，
   * 「候选被过滤」对应去松过滤规则，两者的动作完全不同。
   */
  const diagnosisTabs = computed(() =>
    Object.keys(DIAGNOSIS_META)
      .filter((key) => (report.value.diagnosisCounts[key] || 0) > 0)
      .map((key) => ({ key, count: report.value.diagnosisCounts[key], ...diagnosisMeta(key) }))
  )

  /**
   * 当前视图里还没开自动补搜的订阅。
   * 「一键开启」的作用域跟着筛选走：用户在「逾期缺失」标签下点它，改的就该是眼前这批。
   */
  const autoSearchOffIds = computed(() =>
    subscriptions.value.filter((s) => !s.autoSearch).map((s) => s.subId)
  )

  /**
   * 开启自动补搜。不传 ids 就是批量开启当前筛选下的全部。
   * <p>
   * 批量那条要二次确认：auto_search 默认关是刻意的设计（每条开着的订阅每轮都要向每个
   * 索引器打满一整份检索计划，追完的老剧全开会长期空转），一次开几十条是有持续代价的
   * 配置变更，不是一次性动作。逐行开启只影响一条，不打断用户。
   * </p>
   */
  const handleEnableAutoSearch = async (ids?: number[]) => {
    const isBatch = !ids || ids.length === 0
    const targets = isBatch ? autoSearchOffIds.value : ids
    if (targets.length === 0) {
      message.info('当前列表里没有需要开启自动补搜的订阅')
      return
    }
    if (isBatch) {
      try {
        await confirm({
          message: `确认为当前列表里的 ${targets.length} 条订阅开启自动补搜？`
            + '开启后它们每轮心跳都会向所有索引器发起检索，直到缺集补齐或你手动关掉。',
          title: '提示',
          type: 'warning'
        })
      } catch {
        return
      }
    }
    batchActing.value = isBatch
    if (!isBatch) actingSubId.value = targets[0]
    try {
      const count = await enableAutoSearchApi(targets)
      message.success(`已为 ${count} 条订阅开启自动补搜`)
      await load()
    } catch (e) {
      // 拦截器已经弹过后端的真实原因，这里再补一句通用文案只会把它盖掉
      console.error(e)
    } finally {
      batchActing.value = false
      actingSubId.value = null
    }
  }

  const handleSearchNow = async (subId: number) => {
    actingSubId.value = subId
    try {
      const msg = await searchMissingApi(subId)
      message.success(msg || '补搜完成')
      await load()
    } catch (e) {
      console.error(e)
    } finally {
      actingSubId.value = null
    }
  }

  /**
   * 忽略/取消忽略。
   * <p>
   * 不加二次确认：这个动作完全可逆，而且「显示已忽略(N)」那个入口一直摆在页面上，
   * 用确认框拦一道只是白增摩擦。真正需要确认的是批量开启自动补搜那种有持续代价的配置变更。
   * </p>
   */
  const handleSetIgnored = async (subId: number, ignored: boolean) => {
    actingSubId.value = subId
    try {
      await setHealthIgnoredApi([subId], ignored)
      message.success(ignored
        ? '已忽略，这条订阅不再出现在体检与逾期提醒里（抓取照常）'
        : '已取消忽略')
      await load()
    } catch (e) {
      console.error(e)
    } finally {
      actingSubId.value = null
    }
  }

  /** 切换「显示已忽略」。要重新拉一次——已忽略的条目后端默认不回传 */
  const toggleIncludeIgnored = async () => {
    includeIgnored.value = !includeIgnored.value
    await load()
  }

  /** 这一行是不是正在跑动作。批量进行中时所有行也一并锁住，避免并发改同一批数据 */
  const isActing = (subId: number) => batchActing.value || actingSubId.value === subId
  /** 有任何动作在跑（批量按钮与刷新按钮据此禁用） */
  const anyActing = computed(() => batchActing.value || actingSubId.value !== null)

  const router = useRouter()
  /**
   * 跳到订阅页并展开它的进度弹窗。query 用 id 而不是 subId——订阅页两端读的都是 route.query.id。
   * 路径不写死：后端菜单 path 历史上有 /openlist 与 /openliststrm 两种前缀，写死会跳 404。
   */
  const openSubscription = (subId: number) => {
    const path = getRoutePathForComponent('openlist/ptSubscription/index')
    if (path) router.push({ path, query: { id: String(subId) } })
  }

  /** 点已选中的档再点一次就取消筛选——比强迫用户去找「全部」那颗 chip 顺手 */
  const setBucket = (bucket: string) => {
    activeBucket.value = activeBucket.value === bucket ? '' : bucket
  }

  const setDiagnosis = (diagnosis: string) => {
    activeDiagnosis.value = activeDiagnosis.value === diagnosis ? '' : diagnosis
  }

  load()

  return {
    loading, loadFailed, lastLoadedAt, report,
    activeBucket, activeDiagnosis, subscriptions, filteredCount, filtering,
    bucketTabs, diagnosisTabs, autoSearchOffIds,
    actingSubId, batchActing, isActing, anyActing,
    includeIgnored, handleSetIgnored, toggleIncludeIgnored,
    load, handleEnableAutoSearch, handleSearchNow, openSubscription, setBucket, setDiagnosis
  }
}
