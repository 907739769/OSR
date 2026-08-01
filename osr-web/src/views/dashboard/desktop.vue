<template>
  <div class="dashboard">
    <!-- Welcome header -->
    <div class="welcome-header">
      <div class="welcome-text">
        <div class="welcome-title">欢迎回来，{{ userName }}</div>
        <div class="welcome-quote" title="点击换一句" @click="loadQuote">“{{ quote }}”</div>
      </div>
      <div class="welcome-date">
        <div class="welcome-weekday">{{ weekdayText }}</div>
        <div class="welcome-day">{{ dateText }}</div>
      </div>
    </div>

    <!-- Stat Cards -->
    <v-row class="stat-row" dense>
      <template v-if="statLoading">
        <v-col cols="6" md="2" v-for="i in 6" :key="'skeleton-' + i">
          <v-skeleton-loader type="list-item-avatar" class="stat-skeleton" />
        </v-col>
      </template>
      <template v-else>
        <v-col cols="6" md="2" v-for="(stat, index) in statCards" :key="index">
          <v-card
            class="stat-card"
            :class="[stat.type, { clickable: !!stat.path }]"
            @click="stat.path && router.push(stat.path)"
          >
            <div class="stat-icon">
              <v-icon :icon="stat.icon" size="18" />
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ stat.value }}</div>
              <div class="stat-label">{{ stat.label }}</div>
              <div v-if="stat.sparkKey" class="stat-spark">
                <MiniTrend :points="trendSeries[stat.sparkKey] || []" :tone="stat.type" />
              </div>
            </div>
          </v-card>
        </v-col>
      </template>
    </v-row>

    <!-- Middle: task pie chart (tabbed) + PT subscription overview -->
    <v-row class="middle-row">
      <v-col cols="12" md="8">
        <v-card class="chart-card">
          <div class="chart-header">
            <v-tabs v-model="activeTaskTab" density="compact" @update:model-value="loadTaskChart">
              <v-tab v-for="t in taskTabs" :key="t.key" :value="t.key">{{ t.title }}</v-tab>
            </v-tabs>
            <v-select
              v-model="taskDays"
              :items="[{ title: '近7天', value: 7 }, { title: '近14天', value: 14 }, { title: '近30天', value: 30 }]"
              density="compact"
              variant="outlined"
              hide-details
              class="task-days-select"
              @update:model-value="loadTaskChart"
            />
          </div>
          <div class="chart-wrap">
            <div ref="taskChartContainer" class="echarts-container" />
            <v-skeleton-loader v-if="chartLoading" type="image" class="chart-skeleton" height="260" />
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" md="4">
        <v-card class="pt-card">
          <div class="chart-header">
            <span class="chart-title">PT 订阅概览</span>
            <v-btn variant="text" size="small" color="primary" @click="goPtStats">查看详情</v-btn>
          </div>
          <div class="pt-overview-grid">
            <div v-for="item in ptOverviewItems" :key="item.label" class="pt-overview-item" :class="item.type">
              <span class="pt-overview-dot" />
              <div class="pt-overview-value">{{ formatPtValue(item) }}</div>
              <div class="pt-overview-label">{{ item.label }}</div>
            </div>
          </div>
          <v-divider class="my-2" />
          <div class="pt-top-list">
            <div class="pt-top-title">热门订阅 Top {{ ptTopSubscriptions.length }}</div>
            <div v-if="!ptTopSubscriptions.length" class="pt-top-empty">暂无数据</div>
            <div v-for="sub in ptTopSubscriptions" :key="sub.subId" class="pt-top-item">
              <div class="pt-top-main">
                <span class="pt-top-name" :title="sub.title">{{ sub.title }}</span>
                <div class="pt-top-progress">
                  <div class="pt-top-progress-bar" :style="{ width: ptProgressWidth(sub) + '%' }" />
                </div>
              </div>
              <span class="pt-top-count">{{ sub.completedCount }}/{{ sub.downloadCount }}</span>
            </div>
          </div>
        </v-card>
      </v-col>
    </v-row>

    <!-- Bottom: recent failures + quick links -->
    <v-row class="bottom-row">
      <v-col cols="12" md="8">
        <v-card class="chart-card">
          <div class="chart-header">
            <span class="chart-title">最近失败记录</span>
          </div>
          <div v-if="!recentFailures.length" class="empty-tip">
            <v-empty-state icon="mdi-check-circle-outline" title="暂无失败记录" />
          </div>
          <div v-else class="failure-list">
            <div
              v-for="f in recentFailures"
              :key="f.type + '-' + f.id"
              class="failure-item"
              @click="f.path && router.push(f.path)"
            >
              <v-icon :icon="f.icon" size="18" class="failure-icon" :color="f.color" />
              <span class="failure-tag-text">{{ f.typeLabel }}</span>
              <span class="failure-name" :title="f.name">{{ f.name }}</span>
              <span class="failure-time" :title="f.time">{{ formatRelativeTime(f.time) }}</span>
            </div>
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" md="4">
        <v-card class="chart-card">
          <div class="chart-header">
            <span class="chart-title">快捷入口</span>
          </div>
          <div class="quick-links">
            <div
              v-for="link in quickLinks"
              :key="link.path"
              class="quick-link-item"
              @click="link.path && router.push(link.path)"
            >
              <v-icon :icon="link.icon" />
              <span>{{ link.title }}</span>
            </div>
          </div>
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getRoutePathForComponent } from '@/router'
// 按需引入：仪表盘只用到折线图，避免全量引入 echarts 拖大打包体积
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, GridComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { osrCssVar } from '@/composables/useThemeMode'
import MiniTrend from '@/components/MiniTrend.vue'
import { getDashboardStatsApi, getDashboardTrendApi, type DashboardTrendPoint } from '@/api/openlist/dashboard'
import { getHitokotoApi } from '@/api/openlist/hitokoto'
import { getStrmRecordListApi } from '@/api/openlist/strmRecord'
import { getCopyRecordListApi } from '@/api/openlist/copyRecord'
import { getRenameDetailListApi } from '@/api/openlist/renameDetail'
import { getPtStatsOverviewApi, getPtStatsTopSubscriptionsApi, type PtStatsOverview, type PtStatsActiveSubscription } from '@/api/openlist/ptStats'
import { useMenuLinks } from '@/composables/useMenuLinks'

echarts.use([LineChart, TitleComponent, TooltipComponent, GridComponent, LegendComponent, CanvasRenderer])

const router = useRouter()
const userStore = useUserStore()
const userName = computed(() => userStore.userInfo?.userName || userStore.userInfo?.loginName || '管理员')
const weekdayText = new Date().toLocaleDateString('zh-CN', { weekday: 'long' })
const dateText = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })

/** 一言接口请求失败时的备用文案，与移动端首页保持一致 */
const FALLBACK_QUOTES = [
  '代码写得好，Bug就是少。',
  '生活明朗，万物可爱。',
  '愿你被这个世界温柔以待。',
  '不积跬步，无以至千里。',
  '心之所向，素履以往。'
]

function randomFallbackQuote(): string {
  return FALLBACK_QUOTES[Math.floor(Math.random() * FALLBACK_QUOTES.length)]
}

const quote = ref(randomFallbackQuote())

function loadQuote() {
  getHitokotoApi()
    .then((data) => {
      quote.value = data.from ? `${data.hitokoto} —— ${data.from}` : data.hitokoto
    })
    .catch((e) => {
      console.error('[Dashboard] 每日一言加载失败:', e)
    })
}

interface StatCard {
  label: string
  value: number | string
  icon: string
  type: 'primary' | 'success' | 'warning' | 'info'
  path?: string | null
  /** 有值时卡片右下角展示对应类型的近 7 天迷你趋势线 */
  sparkKey?: 'copy' | 'strm' | 'rename'
}

const statCards = ref<StatCard[]>([])
const statLoading = ref(true)
const chartLoading = ref(true)
const trendSeries = ref<Record<'copy' | 'strm' | 'rename', number[]>>({ copy: [], strm: [], rename: [] })

/** 近 7 天完整趋势数据（sparkline 与初始图表共用，避免重复请求） */
const trendCache = ref<Partial<Record<'copy' | 'strm' | 'rename', DashboardTrendPoint[]>>>({})

/** 统计卡 sparkline 数据：复用 getDashboardTrendApi 的 totalCount 序列 */
async function loadSparklines() {
  const types = ['copy', 'strm', 'rename'] as const
  const results = await Promise.allSettled(types.map((t) => getDashboardTrendApi(t, 7)))
  results.forEach((r, i) => {
    if (r.status === 'fulfilled' && Array.isArray(r.value)) {
      trendCache.value[types[i]] = r.value
      trendSeries.value[types[i]] = r.value.map((p) => p.totalCount)
    }
  })
}

onMounted(async () => {
  loadQuote()
  try {
    const statsData: any = await getDashboardStatsApi()
    const copyCount = statsData?.copyRecordCount ?? 0
    const strmCount = statsData?.strmRecordCount ?? 0
    const renameCount = statsData?.renameDetailCount ?? 0
    const successRate = statsData?.successRate ?? 0
    const failedCount = statsData?.failedCount ?? 0
    const processingCount = statsData?.processingCount ?? 0
    statCards.value = [
      { label: 'COPY 任务', value: copyCount, icon: 'mdi-file-multiple-outline', type: 'primary', path: getRoutePathForComponent('openlist/copyRecord/index'), sparkKey: 'copy' },
      { label: 'STRM 任务', value: strmCount, icon: 'mdi-video-outline', type: 'success', path: getRoutePathForComponent('openlist/strmRecord/index'), sparkKey: 'strm' },
      { label: 'Rename 任务', value: renameCount, icon: 'mdi-pencil-outline', type: 'warning', path: getRoutePathForComponent('openlist/renameDetail/index'), sparkKey: 'rename' },
      { label: '成功率', value: successRate > 0 ? successRate + '%' : '--', icon: 'mdi-check-circle-outline', type: 'info' },
      { label: '失败数', value: failedCount, icon: 'mdi-close-circle-outline', type: 'warning' },
      { label: '处理中', value: processingCount, icon: 'mdi-loading mdi-spin', type: 'primary' }
    ]
  } catch (e) {
    console.error('[Dashboard] Failed to load stat cards:', e)
    statCards.value = [
      { label: 'COPY 任务', value: '0', icon: 'mdi-file-multiple-outline', type: 'primary' },
      { label: 'STRM 任务', value: '0', icon: 'mdi-video-outline', type: 'success' },
      { label: 'Rename 任务', value: '0', icon: 'mdi-pencil-outline', type: 'warning' },
      { label: '成功率', value: '--', icon: 'mdi-check-circle-outline', type: 'info' },
      { label: '失败数', value: '0', icon: 'mdi-close-circle-outline', type: 'warning' },
      { label: '处理中', value: '0', icon: 'mdi-loading mdi-spin', type: 'primary' }
    ]
  } finally {
    statLoading.value = false
  }
})

/* ============================================
   Task trend chart (tabbed: COPY / STRM / Rename)
   ============================================ */
const taskTabs = [
  { key: 'copy', title: 'COPY 任务' },
  { key: 'strm', title: 'STRM 任务' },
  { key: 'rename', title: 'Rename 任务' }
] as const
const activeTaskTab = ref<'copy' | 'strm' | 'rename'>('copy')
const taskDays = ref(7)
const taskChartContainer = ref<HTMLElement | null>(null)
let taskChart: any = null

function renderTrendChart(points: DashboardTrendPoint[]) {
  if (!taskChart) return
  const axisColor = osrCssVar('--osr-text-secondary') || '#64748b'
  const splitColor = osrCssVar('--osr-border-light') || '#f1f5f9'

  if (!points.length) {
    taskChart.clear()
    taskChart.setOption({
      title: { text: '暂无数据', left: 'center', top: 'center', textStyle: { fontSize: 14, color: osrCssVar('--osr-text-placeholder') || '#94a3b8' } },
      series: []
    }, true)
    return
  }

  taskChart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    legend: { data: ['总数', '成功', '失败'], top: 4, itemWidth: 12, itemHeight: 12, textStyle: { fontSize: 12, color: axisColor } },
    grid: { left: 36, right: 16, top: 40, bottom: 24 },
    xAxis: { type: 'category', data: points.map(p => p.date.slice(5)), axisLabel: { fontSize: 11, color: axisColor }, axisLine: { lineStyle: { color: splitColor } } },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { fontSize: 11, color: axisColor }, splitLine: { lineStyle: { color: splitColor } } },
    series: [
      { name: '总数', type: 'line', smooth: true, data: points.map(p => p.totalCount), itemStyle: { color: '#B4690E' }, lineStyle: { width: 2 }, areaStyle: { opacity: 0.1 } },
      { name: '成功', type: 'line', smooth: true, data: points.map(p => p.successCount), itemStyle: { color: '#3F8F5F' }, lineStyle: { width: 2 }, areaStyle: { opacity: 0.1 } },
      { name: '失败', type: 'line', smooth: true, data: points.map(p => p.failedCount), itemStyle: { color: '#C0362C' }, lineStyle: { width: 2 }, areaStyle: { opacity: 0.08 } }
    ]
  }, true)
}

let chartReqSeq = 0

async function loadTaskChart() {
  if (!taskChart) return
  // 初始 tab 近 7 天数据已由 loadSparklines 取过，直接复用避免重复请求
  const cached = trendCache.value[activeTaskTab.value]
  if (cached && taskDays.value === 7) {
    renderTrendChart(cached)
    return
  }
  const seq = ++chartReqSeq
  try {
    const points = await getDashboardTrendApi(activeTaskTab.value, taskDays.value)
    if (seq !== chartReqSeq) return // 已切 tab/天数，丢弃过期响应
    renderTrendChart(points || [])
  } catch (e) {
    if (seq !== chartReqSeq) return
    console.error('Failed to load task trend chart:', e)
    renderTrendChart([])
  }
}

/* ============================================
   PT subscription overview
   ============================================ */
const ptOverview = ref<Partial<PtStatsOverview>>({})
const ptTopSubscriptions = ref<PtStatsActiveSubscription[]>([])

/** PT 概览四格：key 对应 PtStatsOverview 字段，type 决定色点 */
const ptOverviewItems = [
  { key: 'totalSubscriptions', label: '订阅总数', type: 'primary', suffix: '' },
  { key: 'activeSubscriptions', label: '活跃订阅', type: 'success', suffix: '' },
  { key: 'successRate', label: '下载成功率', type: 'warning', suffix: '%' },
  { key: 'avgDurationMinutes', label: '平均耗时', type: 'info', suffix: '分' }
] as const

function formatPtValue(item: { key: string; suffix: string }): string {
  const v = (ptOverview.value as any)?.[item.key]
  if (v == null) return '--'
  return item.suffix ? `${v}${item.suffix}` : String(v)
}

function ptProgressWidth(sub: PtStatsActiveSubscription): number {
  if (!sub.downloadCount) return 0
  return Math.min(100, Math.round((sub.completedCount / sub.downloadCount) * 100))
}

function goPtStats() {
  const path = getRoutePathForComponent('openlist/ptStatsDashboard/index')
  if (path) router.push(path)
}

async function loadPtOverview() {
  try {
    ptOverview.value = await getPtStatsOverviewApi()
  } catch (e) {
    console.error('[Dashboard] Failed to load PT overview:', e)
  }
  try {
    ptTopSubscriptions.value = await getPtStatsTopSubscriptionsApi(7, 5)
  } catch (e) {
    console.error('[Dashboard] Failed to load PT top subscriptions:', e)
  }
}

/* ============================================
   Recent failures (merged from strm/copy/rename)
   ============================================ */
interface FailureItem {
  type: string
  typeLabel: string
  color: string
  icon: string
  id: number | string
  name: string
  time: string
  path: string | null
}

/** 后端时间转相对时间（x 分钟前/小时前/天前），无效值原样返回 */
function formatRelativeTime(time: string): string {
  if (!time) return ''
  const t = new Date(time).getTime()
  if (Number.isNaN(t)) return time
  const min = Math.floor((Date.now() - t) / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  const day = Math.floor(hour / 24)
  if (day < 30) return `${day} 天前`
  return new Date(time).toLocaleDateString('zh-CN')
}

const recentFailures = ref<FailureItem[]>([])

async function loadRecentFailures() {
  const items: FailureItem[] = []
  const strmPath = getRoutePathForComponent('openlist/strmRecord/index')
  const copyPath = getRoutePathForComponent('openlist/copyRecord/index')
  const renamePath = getRoutePathForComponent('openlist/renameDetail/index')

  try {
    const res: any = await getStrmRecordListApi({ pageNum: 1, pageSize: 5, strmStatus: '0' })
    for (const r of res?.records || []) {
      items.push({ type: 'strm', typeLabel: 'STRM', color: 'success', icon: 'mdi-video-outline', id: r.strmId, name: r.strmFileName, time: r.createTime, path: strmPath })
    }
  } catch (e) {
    console.error('[Dashboard] Failed to load strm failures:', e)
  }

  try {
    const res: any = await getCopyRecordListApi({ pageNum: 1, pageSize: 5, copyStatus: '0' })
    for (const r of res?.records || []) {
      items.push({ type: 'copy', typeLabel: 'COPY', color: 'primary', icon: 'mdi-file-multiple-outline', id: r.copyId, name: r.copySrcFileName, time: r.createTime, path: copyPath })
    }
  } catch (e) {
    console.error('[Dashboard] Failed to load copy failures:', e)
  }

  try {
    const res: any = await getRenameDetailListApi({ pageNum: 1, pageSize: 5, status: '0' })
    for (const r of res?.records || []) {
      items.push({ type: 'rename', typeLabel: 'Rename', color: 'warning', icon: 'mdi-pencil-outline', id: r.id, name: r.originalName, time: r.createTime, path: renamePath })
    }
  } catch (e) {
    console.error('[Dashboard] Failed to load rename failures:', e)
  }

  items.sort((a, b) => (a.time < b.time ? 1 : -1))
  recentFailures.value = items.slice(0, 8)
}

/* ============================================
   Quick links
   ============================================ */
const quickLinks = useMenuLinks()

let resizeHandler: (() => void) | null = null

onMounted(async () => {
  await nextTick()
  if (taskChartContainer.value) {
    taskChart = echarts.init(taskChartContainer.value)
  }
  // 先取 sparkline 趋势（填充 trendCache），loadTaskChart 复用初始 tab 数据避免重复请求
  await Promise.all([loadSparklines(), loadPtOverview(), loadRecentFailures()])
  await loadTaskChart()
  chartLoading.value = false

  resizeHandler = () => taskChart?.resize()
  window.addEventListener('resize', resizeHandler)

  // 主题切换后重绘图表（canvas 无法用 CSS 变量）
  themeChangeHandler = () => {
    if (taskChartContainer.value) {
      taskChart?.dispose()
      taskChart = echarts.init(taskChartContainer.value)
      loadTaskChart()
    }
  }
  document.addEventListener('osr-theme-change', themeChangeHandler)
})

let themeChangeHandler: (() => void) | null = null

onUnmounted(() => {
  resizeHandler && window.removeEventListener('resize', resizeHandler)
  themeChangeHandler && document.removeEventListener('osr-theme-change', themeChangeHandler)
  taskChart?.dispose()
})
</script>

<style scoped lang="scss">
.dashboard {
  padding: 0;
}

.welcome-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;

  .welcome-text {
    min-width: 0;
  }

  .welcome-title {
    font-size: 20px;
    font-weight: 700;
    color: var(--osr-text-primary);
  }

  .welcome-quote {
    font-size: 12px;
    color: var(--osr-text-secondary);
    margin-top: 4px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 480px;
    cursor: pointer;
    transition: color var(--osr-transition-fast);

    &:hover {
      color: var(--osr-primary);
    }
  }

  .welcome-date {
    flex-shrink: 0;
    background: var(--osr-primary-subtle);
    color: var(--osr-primary);
    padding: 8px 18px;
    border-radius: var(--osr-radius-md);
    text-align: center;

    .welcome-weekday {
      font-size: 15px;
      font-weight: 600;
      line-height: 1.3;
    }

    .welcome-day {
      font-size: 12px;
      margin-top: 2px;
      opacity: 0.9;
    }
  }
}

/* ============================================
   Stat Cards
   ============================================ */
.stat-row {
  margin-bottom: 8px;
}

.stat-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);
  margin-bottom: 12px;
  cursor: default;
  transition: all var(--osr-transition-base);

  &:hover {
    transform: translateY(-2px);
    box-shadow: var(--osr-shadow-md);
  }

  &.clickable {
    cursor: pointer;
  }

  display: flex;
  align-items: center;
  padding: 12px 14px;
  gap: 12px;

  .stat-icon {
    width: 40px;
    height: 40px;
    border-radius: var(--osr-radius-lg);
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }

  .stat-info {
    flex: 1;
    min-width: 0;

    .stat-value {
      font-size: 20px;
      font-weight: 700;
      color: var(--osr-text-primary);
      line-height: 1.2;
    }

    .stat-label {
      font-size: 12px;
      color: var(--osr-text-secondary);
      margin-top: 1px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .stat-spark {
      margin-top: 4px;
      height: 24px;
    }
  }

  &.primary .stat-icon {
    background-color: var(--osr-primary-subtle);
    color: var(--osr-primary);
  }
  &.success .stat-icon {
    background-color: var(--osr-success-light);
    color: var(--osr-success);
  }
  &.warning .stat-icon {
    background-color: var(--osr-warning-light);
    color: var(--osr-warning);
  }
  &.info .stat-icon {
    background-color: var(--osr-info-light);
    color: var(--osr-info);
  }
}

/* ============================================
   Shared card chrome
   ============================================ */
.middle-row,
.bottom-row {
  margin-bottom: 8px;
}

.chart-card,
.pt-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);
  margin-bottom: 16px;
  transition: box-shadow var(--osr-transition-base);
  height: 100%;

  &:hover {
    box-shadow: var(--osr-shadow-md);
  }
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 20px;
  border-bottom: 1px solid var(--osr-border-light);
  background-color: var(--osr-surface);

  .chart-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--osr-text-primary);
  }

  :deep(.v-tabs) {
    flex: 0 1 auto;
  }
}

.task-days-select {
  flex: 0 0 auto;
  width: 88px;
  margin-left: auto;

  :deep(.v-field__input) {
    font-size: 12px;
    padding-top: 2px;
    padding-bottom: 2px;
  }
}

.echarts-container {
  height: 260px;
  width: 100%;
}

.chart-wrap {
  position: relative;

  .chart-skeleton {
    position: absolute;
    inset: 0;
    z-index: 1;
  }
}

.stat-skeleton {
  border-radius: var(--osr-radius-lg);
}

/* ============================================
   PT overview card
   ============================================ */
.pt-overview-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  padding: 16px 20px 4px;
}

.pt-overview-item {
  text-align: center;
  position: relative;

  .pt-overview-dot {
    position: absolute;
    top: 2px;
    left: 50%;
    transform: translateX(-50%);
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--osr-primary);
    opacity: 0.85;
  }

  &.success .pt-overview-dot { background: var(--osr-success); }
  &.warning .pt-overview-dot { background: var(--osr-warning); }
  &.info .pt-overview-dot { background: var(--osr-info); }

  .pt-overview-value {
    font-size: 20px;
    font-weight: 700;
    color: var(--osr-text-primary);
    margin-top: 6px;
  }

  .pt-overview-label {
    font-size: 12px;
    color: var(--osr-text-secondary);
    margin-top: 2px;
  }
}

.pt-top-list {
  padding: 0 20px 16px;

  .pt-top-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--osr-text-secondary);
    margin-bottom: 8px;
  }

  .pt-top-empty {
    font-size: 13px;
    color: var(--osr-text-secondary);
    padding: 8px 0;
  }
}

.pt-top-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px dashed var(--osr-border-light);

  &:last-child {
    border-bottom: none;
  }

  .pt-top-main {
    flex: 1;
    min-width: 0;
  }

  .pt-top-name {
    display: block;
    color: var(--osr-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    margin-bottom: 3px;
  }

  .pt-top-progress {
    height: 4px;
    border-radius: 2px;
    background: var(--osr-primary-subtle);
    overflow: hidden;

    .pt-top-progress-bar {
      height: 100%;
      border-radius: 2px;
      background: linear-gradient(90deg, var(--osr-primary-accent), var(--osr-primary));
      transition: width var(--osr-transition-base);
    }
  }

  .pt-top-count {
    color: var(--osr-text-secondary);
    flex-shrink: 0;
    font-size: 12px;
  }
}

/* ============================================
   Recent failures
   ============================================ */
.empty-tip {
  padding: 8px 0;
}

.failure-list {
  padding: 4px 20px 12px;
}

.failure-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--osr-border-light);
  cursor: pointer;

  &:last-child {
    border-bottom: none;
  }

  &:hover .failure-name {
    color: var(--osr-primary);
  }

  .failure-icon {
    flex-shrink: 0;
  }

  .failure-tag-text {
    flex-shrink: 0;
    font-size: 11px;
    font-weight: 600;
    color: var(--osr-text-secondary);
  }

  .failure-name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 13px;
    color: var(--osr-text-primary);
    transition: color var(--osr-transition-base);
  }

  .failure-time {
    flex-shrink: 0;
    font-size: 12px;
    color: var(--osr-text-secondary);
  }
}

/* ============================================
   Quick links
   ============================================ */
.quick-links {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 10px;
  padding: 16px 20px;

  .quick-link-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    padding: 12px 8px;
    border-radius: var(--osr-radius-md);
    background: var(--osr-bg-page);
    cursor: pointer;
    transition: all var(--osr-transition-fast);

    .v-icon {
      color: var(--osr-primary);
      font-size: 20px;
    }

    span {
      font-size: 12px;
      color: var(--osr-text-secondary);
      text-align: center;
      line-height: 1.3;
      overflow: hidden;
      text-overflow: ellipsis;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
    }

    &:hover {
      background: var(--osr-primary-subtle);
      transform: translateY(-1px);
    }
  }
}

/* ============================================
   Responsive
   ============================================ */
@media (max-width: 768px) {
  .dashboard {
    padding: 16px;
  }

  .echarts-container {
    height: 220px !important;
  }
}
</style>
