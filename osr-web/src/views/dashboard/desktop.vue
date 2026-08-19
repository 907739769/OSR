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
        <PtOverviewCard />
      </v-col>
    </v-row>

    <!-- Bottom: recent failures + quick links -->
    <v-row class="bottom-row">
      <v-col cols="12" md="8">
        <RecentFailuresCard />
      </v-col>

      <v-col cols="12" md="4">
        <QuickLinksCard />
      </v-col>
    </v-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useCurrentUser } from '@/composables/useCurrentUser'
import { getRoutePathForComponent } from '@/router'
// 按需引入：仪表盘只用到折线图，避免全量引入 echarts 拖大打包体积
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, GridComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { osrCssVar } from '@/composables/useThemeMode'
import MiniTrend from '@/components/MiniTrend.vue'
import PtOverviewCard from './PtOverviewCard.vue'
import RecentFailuresCard from './RecentFailuresCard.vue'
import QuickLinksCard from './QuickLinksCard.vue'
import { getDashboardStatsApi, getDashboardTrendApi, type DashboardTrendPoint } from '@/api/openlist/dashboard'
import { getHitokotoApi } from '@/api/openlist/hitokoto'

echarts.use([LineChart, TitleComponent, TooltipComponent, GridComponent, LegendComponent, CanvasRenderer])

const router = useRouter()
const { displayName: userName } = useCurrentUser()
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




/* ============================================
   Quick links
   ============================================ */

let resizeHandler: (() => void) | null = null

onMounted(async () => {
  await nextTick()
  if (taskChartContainer.value) {
    taskChart = echarts.init(taskChartContainer.value)
  }
  // 先取 sparkline 趋势（填充 trendCache），loadTaskChart 复用初始 tab 数据避免重复请求
  // PT 概览由 PtOverviewCard 自己在 onMounted 里取
  await loadSparklines()
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
/* 图表卡外观。原先与 .pt-card 写在同一条分组选择器里，PT 概览拆成组件后各留各的 */
.chart-card {
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
   Responsive
   ============================================ */
@media (max-width: 768px) {
  .dashboard {
    padding: 16px;
  }

  .echarts-container {
    height: 220px !important;
  }
}</style>
