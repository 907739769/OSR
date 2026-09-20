import { computed, onMounted, onUnmounted, ref } from 'vue'
import { osrCssVar } from '@/composables/useThemeMode'
import { barSeries, chartBase, chartEmptyOption, lineSeries } from '@/plugins/echartsTheme'
import {
  getPtStatsFailReasonsApi,
  getPtStatsIndexerHitRateApi,
  getPtStatsOverviewApi,
  getPtStatsRejectReasonsApi,
  getPtStatsTopSubscriptionsApi,
  getPtStatsTrendApi,
  type PtStatsActiveSubscription,
  type PtStatsFailReason,
  type PtStatsIndexerHitRate,
  type PtStatsOverview,
  type PtStatsRejectReason,
  type PtStatsTrendPoint
} from '@/api/openlist/ptStats'

/**
 * PT 统计仪表盘的取数与图表选项，PC 与移动端<共用这一份>。
 *
 * ## 为什么收口
 *
 * 改造前两端各写了一份约 500 行的取数 + setOption，结果是可预期的漂移：
 * 「搜索淘汰原因分布」只有 PC 端有，移动端从来没加过；失败原因的配色函数被原样
 * 复制了两遍，于是同一个 bug 也存在两份。判断口径（哪张图受时间挡位影响、
 * 空态说什么）更是必须一致，否则同一份数据在两端给出不同的读数。
 *
 * ## 三条不要改坏的
 *
 * 1. **主题切换只重算选项，不重新取数。** 旧实现的 `osr-theme-change` 处理器直接
 *    调 `loadTrend()` 等取数函数，于是用户切一次深浅色就打一轮聚合查询（而那几个
 *    查询要扫下载记录全表）。现在颜色从 `themeTick` 派生：事件只 bump 一个计数，
 *    下面的 computed 重算出新颜色的 option，一个请求都不发。
 * 2. **失败原因按<b>码</b>配色，不按文案猜。** 旧实现从首页状态卡抄了一个按
 *    「名字里带不带『失败』」猜颜色的函数，而失败原因文案一个都命中不了，
 *    于是整张饼图恒为同一个红色。颜色还必须走 `--osr-*` 令牌：写死的十六进制
 *    在暗色主题下不跟着变，正是 `plugins/echartsTheme.ts` 当初要修的那个毛病。
 * 3. **取数失败要和「确实没有数据」区分开。** 失败时图上写「加载失败」、统计卡显示
 *    `--` 并把 `failed` 置位供页面提示，绝不退化成一排 0——用户会把 0 读成
 *    「系统很干净」（同首页统计卡那条）。
 */

export interface PtStatCard {
  key: string
  label: string
  value: number | string
  icon: string
  type: 'primary' | 'success' | 'warning' | 'info' | 'error'
}

/**
 * 失败原因分类 → 设计令牌。
 * 只有「种子丢失」是真错误（下载器侧出了状况），另外三类是资源本身不行：
 * 占位集都已退回、会继续搜索，不该用红色吓人（与下载记录页的 tag 配色同一条取向）。
 */
const FAIL_REASON_TOKENS: Record<string, string> = {
  TORRENT_NOT_FOUND: '--osr-error',
  ZOMBIE_TIMEOUT: '--osr-warning',
  NO_TARGET_EPISODE: '--osr-info',
  METADATA_TIMEOUT: '--osr-primary',
  OTHER: '--osr-text-secondary'
}

const FAIL_REASON_FALLBACK: Record<string, string> = {
  '--osr-error': '#C0362C',
  '--osr-warning': '#C98A1E',
  '--osr-info': '#4C6C93',
  '--osr-primary': '#B4690E',
  '--osr-text-secondary': '#7A7A7A'
}

/** 按失败分类码取颜色。未知码（后端新增而前端还没登记）落到中性色，不去猜 */
export function failReasonColor(code: string | undefined | null): string {
  const token = FAIL_REASON_TOKENS[code || 'OTHER'] || FAIL_REASON_TOKENS.OTHER
  return osrCssVar(token) || FAIL_REASON_FALLBACK[token]
}

/** 统计卡。取数失败时 value 一律 `--`，不是 0 */
export function buildStatCards(data: PtStatsOverview | null): PtStatCard[] {
  const has = data !== null
  const hasRecords = has && data.totalDownloadRecords > 0
  return [
    { key: 'totalSubscriptions', label: '总订阅数', value: has ? data.totalSubscriptions : '--', icon: 'file-text', type: 'primary' },
    { key: 'activeSubscriptions', label: '活跃订阅数', value: has ? data.activeSubscriptions : '--', icon: 'network', type: 'success' },
    { key: 'totalDownloadRecords', label: '下载记录总数', value: has ? data.totalDownloadRecords : '--', icon: 'download', type: 'info' },
    { key: 'successRate', label: '成功率', value: hasRecords ? data.successRate + '%' : '--', icon: 'circle-check', type: 'success' },
    { key: 'failedCount', label: '失败数', value: has ? data.failedCount : '--', icon: 'circle-x', type: 'error' },
    { key: 'avgDuration', label: '平均下载耗时', value: has && data.avgDurationMinutes > 0 ? Math.round(data.avgDurationMinutes) + ' 分钟' : '--', icon: 'clock', type: 'warning' }
  ]
}

/**
 * 下载量趋势。平均耗时挂第二根 Y 轴——这个值后端一直在算、一直在传，
 * 而两端此前都没画，等于每次请求白算一次 AVG。
 */
export function buildTrendOption(data: PtStatsTrendPoint[] | null, failed = false) {
  if (failed) return chartEmptyOption('加载失败')
  if (!data || data.length === 0) return chartEmptyOption('暂无数据')
  const base = chartBase()
  const axisColor = osrCssVar('--osr-text-secondary') || '#64748b'
  const hasDuration = data.some(p => p.avgDurationMinutes != null)
  const series: any[] = [
    lineSeries({ name: '推送', data: data.map(p => p.pushedCount), tone: 'primary' }),
    lineSeries({ name: '完成', data: data.map(p => p.completedCount), tone: 'success' }),
    lineSeries({ name: '失败', data: data.map(p => p.failedCount), tone: 'error' })
  ]
  if (hasDuration) {
    series.push({
      ...lineSeries({ name: '平均耗时', data: data.map(p => (p.avgDurationMinutes == null ? '-' : Math.round(p.avgDurationMinutes))), tone: 'warning', area: false }),
      yAxisIndex: 1,
      lineStyle: { width: 2, type: 'dashed' as const, color: osrCssVar('--osr-warning') || '#C98A1E' },
      connectNulls: true
    })
  }
  return {
    ...base,
    legend: { ...base.legend, data: series.map(s => s.name), top: 0 },
    grid: { ...base.grid, top: 40, bottom: 30, right: hasDuration ? 46 : base.grid.right },
    xAxis: { ...base.xAxis, data: data.map(p => p.date) },
    yAxis: hasDuration
      ? [base.yAxis, { ...base.yAxis, name: '分钟', nameTextStyle: { color: axisColor, fontSize: 11 }, splitLine: { show: false }, axisLabel: { ...base.yAxis.axisLabel, formatter: '{value}' } }]
      : base.yAxis,
    series
  }
}

/**
 * 索引器命中率（100% 堆叠横条）。
 * tooltip 里必须带上样本量：只跑过 3 条日志的站点和跑过 300 条的站点，
 * 在百分比条上视觉权重一模一样，而「命中率 33%」在前者身上没有任何意义。
 */
export function buildIndexerOption(data: PtStatsIndexerHitRate[] | null, failed = false) {
  if (failed) return chartEmptyOption('加载失败')
  const withData = (data || []).filter(i => i.hasData)
  if (withData.length === 0) return chartEmptyOption('暂无数据')
  // 横向条形图：x/y 轴的角色与折线图相反，所以两条轴的样式要**互换着**取。
  // 直接铺 base.xAxis / base.yAxis 的话，虚线网格会画在分类轴那一侧（每个索引器
  // 名字后面拖一条线），而数值轴反倒没有刻度参考
  const base = chartBase()
  const totals = withData.map(i => i.acceptedCount + i.rejectedCount)
  return {
    ...base,
    tooltip: {
      ...base.tooltip,
      axisPointer: { type: 'shadow' },
      formatter: (params: any[]) => {
        const idx = params[0]?.dataIndex ?? 0
        const row = withData[idx]
        const lines = params.map(p => `${p.marker}${p.seriesName}：${p.value}%`)
        return `${row.indexerName}<br/>${lines.join('<br/>')}<br/>样本 ${row.acceptedCount} / ${totals[idx]} 条`
      }
    },
    legend: { ...base.legend, data: ['通过', '淘汰'], top: 0 },
    grid: { ...base.grid, left: 100, right: 20, top: 40, bottom: 20 },
    xAxis: { ...base.yAxis, type: 'value', max: 100, axisLabel: { ...base.yAxis.axisLabel, formatter: '{value}%' } },
    yAxis: { ...base.xAxis, type: 'category', data: withData.map(i => i.indexerName), splitLine: { show: false } },
    series: [
      barSeries({ name: '通过', tone: 'success', stack: 'total', data: withData.map(i => Math.round(i.hitRate * 1000) / 10) }),
      barSeries({ name: '淘汰', tone: 'error', stack: 'total', data: withData.map(i => Math.round((1 - i.hitRate) * 1000) / 10) })
    ]
  }
}

/** 失败原因分布（环形图）。颜色按分类码取，见 failReasonColor */
export function buildFailReasonOption(data: PtStatsFailReason[] | null, failed = false) {
  if (failed) return chartEmptyOption('加载失败')
  if (!data || data.length === 0) return chartEmptyOption('暂无数据')
  return {
    tooltip: { ...chartBase().tooltip, trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    series: [{
      type: 'pie',
      radius: ['35%', '65%'],
      center: ['50%', '55%'],
      avoidLabelOverlap: false,
      itemStyle: { borderRadius: 6, borderColor: osrCssVar('--osr-surface') || '#fff', borderWidth: 3 },
      label: { show: true, formatter: '{b}\n{c}', fontSize: 11 },
      labelLine: { length: 15, length2: 10 },
      minAngle: 5,
      data: data.map(item => ({
        value: item.count,
        name: item.reason,
        itemStyle: { color: failReasonColor(item.code) }
      }))
    }]
  }
}

/**
 * 搜索淘汰原因分布（横向柱状）。
 * 用横向柱状而不是饼图：淘汰原因多达 17 类且标签较长，饼图的标签会挤成一团；
 * 而且这里要看的是「哪一条规则最能挡」的排序，柱状比扇形更好比较。
 */
export function buildRejectReasonOption(data: PtStatsRejectReason[] | null, failed = false) {
  if (failed) return chartEmptyOption('加载失败')
  if (!data || data.length === 0) return chartEmptyOption('暂无数据')
  const sorted = [...data].sort((a, b) => a.count - b.count)
  const base = chartBase()
  return {
    ...base,
    tooltip: { ...base.tooltip, axisPointer: { type: 'shadow' }, formatter: '{b}: {c}' },
    legend: { show: false },
    grid: { left: 8, right: 24, top: 12, bottom: 8, containLabel: true },
    xAxis: { ...base.yAxis, type: 'value', minInterval: 1 },
    yAxis: { ...base.xAxis, type: 'category', data: sorted.map(i => i.reason), axisLabel: { ...base.xAxis.axisLabel, fontSize: 11 }, splitLine: { show: false } },
    series: [{
      type: 'bar',
      barMaxWidth: 18,
      itemStyle: { borderRadius: [0, 4, 4, 0], color: osrCssVar('--osr-warning') || '#C98A1E' },
      label: { show: true, position: 'right', fontSize: 11 },
      data: sorted.map(i => i.count)
    }]
  }
}

/** 时间挡位。三档与后端 ALLOWED_DAYS 白名单一一对应，改这里要同时改那边 */
export const PT_STATS_RANGES = [
  { value: 7, label: '近7天' },
  { value: 30, label: '近30天' },
  { value: 90, label: '近90天' }
]

/** Top 活跃订阅的条数挡位。上限 50 是后端 MAX_LIMIT，再大后端也会截断 */
export const PT_STATS_TOP_LIMITS = [10, 20, 50]

export function usePtStats() {
  const rangeDays = ref(30)
  const topLimit = ref(10)
  const overview = ref<PtStatsOverview | null>(null)
  const trend = ref<PtStatsTrendPoint[] | null>(null)
  const indexers = ref<PtStatsIndexerHitRate[] | null>(null)
  const failReasons = ref<PtStatsFailReason[] | null>(null)
  const rejectReasons = ref<PtStatsRejectReason[] | null>(null)
  const topSubscriptions = ref<PtStatsActiveSubscription[]>([])

  const failed = ref<Record<string, boolean>>({})
  /** 首屏加载：只有它为真才显示骨架屏 */
  const loading = ref(true)
  /** 手动刷新/换挡位：按钮转圈但保留旧数字，不闪骨架屏（同首页统计卡） */
  const refreshing = ref(false)
  const topSubscriptionsLoading = ref(false)
  const lastLoadedAt = ref('')
  /** 主题切换只 bump 这个计数：下面的 option computed 会重算颜色，但一个请求都不发 */
  const themeTick = ref(0)

  const anyFailed = computed(() => Object.values(failed.value).some(Boolean))
  const statCards = computed(() => buildStatCards(overview.value))
  // 每个 option 都读一次 themeTick：主题一换，这几个 computed 连同里面的令牌取值一起重算，
  // 而取数函数一个都不调（旧实现在这里直接调 loadXxx，切个深浅色就扫一遍下载记录全表）
  const trendOption = computed(() => {
    void themeTick.value
    return buildTrendOption(trend.value, failed.value.trend)
  })
  const indexerOption = computed(() => {
    void themeTick.value
    return buildIndexerOption(indexers.value, failed.value.indexer)
  })
  const failReasonOption = computed(() => {
    void themeTick.value
    return buildFailReasonOption(failReasons.value, failed.value.failReason)
  })
  const rejectReasonOption = computed(() => {
    void themeTick.value
    return buildRejectReasonOption(rejectReasons.value, failed.value.rejectReason)
  })
  const noDataIndexerNames = computed(() => (indexers.value || []).filter(i => !i.hasData).map(i => i.indexerName))

  async function run<T>(key: string, fn: () => Promise<T>, apply: (v: T) => void) {
    try {
      const data = await fn()
      apply(data)
      failed.value = { ...failed.value, [key]: false }
    } catch (e) {
      console.error(`[PtStats] ${key} 加载失败:`, e)
      failed.value = { ...failed.value, [key]: true }
      apply(null as any)
    }
  }

  const loadOverview = () => run('overview', getPtStatsOverviewApi, v => (overview.value = v))
  const loadTrend = () => run('trend', () => getPtStatsTrendApi(rangeDays.value), v => (trend.value = v))
  const loadIndexers = () => run('indexer', getPtStatsIndexerHitRateApi, v => (indexers.value = v))
  const loadFailReasons = () => run('failReason', () => getPtStatsFailReasonsApi(rangeDays.value), v => (failReasons.value = v))
  const loadRejectReasons = () => run('rejectReason', getPtStatsRejectReasonsApi, v => (rejectReasons.value = v))
  const loadTopSubscriptions = async () => {
    topSubscriptionsLoading.value = true
    try {
      await run('topSubscriptions', () => getPtStatsTopSubscriptionsApi(rangeDays.value, topLimit.value), v => (topSubscriptions.value = v || []))
    } finally {
      topSubscriptionsLoading.value = false
    }
  }

  function stampLoadedAt() {
    lastLoadedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  }

  async function loadAll() {
    refreshing.value = true
    try {
      await Promise.all([loadOverview(), loadTrend(), loadIndexers(), loadFailReasons(), loadRejectReasons(), loadTopSubscriptions()])
    } finally {
      refreshing.value = false
      loading.value = false
      stampLoadedAt()
    }
  }

  /** 换时间挡位只重取受它影响的三块：命中率与淘汰分布不带 days（口径见后端注释） */
  async function onRangeChange() {
    refreshing.value = true
    try {
      await Promise.all([loadTrend(), loadFailReasons(), loadTopSubscriptions()])
    } finally {
      refreshing.value = false
      stampLoadedAt()
    }
  }

  /** 换条数挡位只重取这一块 */
  async function onTopLimitChange() {
    await loadTopSubscriptions()
  }

  const onThemeChange = () => (themeTick.value += 1)
  onMounted(() => document.addEventListener('osr-theme-change', onThemeChange))
  onUnmounted(() => document.removeEventListener('osr-theme-change', onThemeChange))

  return {
    rangeDays,
    topLimit,
    loading,
    refreshing,
    failed,
    anyFailed,
    lastLoadedAt,
    overview,
    statCards,
    trendOption,
    indexerOption,
    failReasonOption,
    rejectReasonOption,
    noDataIndexerNames,
    topSubscriptions,
    topSubscriptionsLoading,
    loadAll,
    onRangeChange,
    onTopLimitChange
  }
}
