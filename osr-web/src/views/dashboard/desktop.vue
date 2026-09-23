<template>
  <div class="dashboard">
    <!-- Welcome header -->
    <div class="welcome-header">
      <div class="welcome-text">
        <div class="welcome-title">欢迎回来，{{ userName }}</div>
        <div class="welcome-quote" :title="`${quote}（点击换一句）`" @click="loadQuote">“{{ quote }}”</div>
      </div>
      <div class="welcome-date">
        <div class="welcome-weekday">{{ weekdayText }}</div>
        <div class="welcome-day">{{ dateText }}</div>
      </div>
    </div>

    <!-- 统计接口失败时明说失败并给重试，不再拿一排 0 冒充「系统很干净」 -->
    <v-alert v-if="statError && !statLoading" type="error" variant="tonal" density="compact" class="mb-3">
      统计数据加载失败，下方数字暂不可用。
      <template #append>
        <v-btn size="small" variant="text" @click="loadStats">重试</v-btn>
      </template>
    </v-alert>

    <!-- Stat Cards -->
    <v-row v-if="!statError || statLoading" class="stat-row" dense>
      <template v-if="statLoading">
        <v-col cols="6" md="2" v-for="i in 6" :key="'skeleton-' + i">
          <!-- 与下面的真卡片同一个 .stat-card 外壳，前三张带趋势线位（同步 / STRM / 重命名），数据到了不跳版 -->
          <v-card class="stat-card osr-sheen osr-skeleton" :style="{ '--osr-i': i - 1 }" aria-hidden="true">
            <span class="osr-bone osr-bone--block stat-icon" />
            <div class="stat-info">
              <span class="osr-bone stat-skeleton-value" />
              <span class="osr-bone osr-bone--caption stat-skeleton-label" />
              <span v-if="i <= 3" class="osr-bone osr-bone--block stat-spark" />
            </div>
          </v-card>
        </v-col>
      </template>
      <template v-else>
        <v-col cols="6" md="2" v-for="(stat, index) in statCards" :key="index">
          <!-- `--osr-i` 驱动 .osr-enter 的错位延迟（motion.scss），六张卡依次落位。
               挂在 v-card 上而不是 v-col 上：动画带 transform，挂在栅格列上会与
               Vuetify 的负外边距一起算，卡片入场时左右会漂 -->
          <v-card
            class="stat-card osr-enter"
            :class="[stat.type, { clickable: !!(stat.path || stat.anchor) }]"
            :style="{ '--osr-i': index }"
            @click="onStatClick(stat)"
          >
            <div class="stat-icon">
              <v-icon :icon="stat.icon" size="18" />
            </div>
            <div class="stat-info">
              <div class="stat-value"><AnimatedNumber :value="stat.value" /></div>
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

    <!-- Bottom: recent failures + todo + quick links -->
    <v-row class="bottom-row">
      <v-col id="dashboard-failures" cols="12" md="5">
        <RecentFailuresCard />
      </v-col>

      <v-col cols="12" md="3">
        <TodoCard ref="todoCard" />
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
import { chartBase, chartEmptyOption, lineSeries } from '@/plugins/echartsTheme'
import MiniTrend from '@/components/MiniTrend.vue'
import AnimatedNumber from '@/components/AnimatedNumber.vue'
import PtOverviewCard from './PtOverviewCard.vue'
import RecentFailuresCard from './RecentFailuresCard.vue'
import QuickLinksCard from './QuickLinksCard.vue'
import { getDashboardStatsApi, getDashboardTrendApi, type DashboardTrendPoint } from '@/api/openlist/dashboard'
import TodoCard from './TodoCard.vue'
import { useDashboardHeader } from '@/composables/useDashboardHeader'

echarts.use([LineChart, TitleComponent, TooltipComponent, GridComponent, LegendComponent, CanvasRenderer])

const router = useRouter()
const { displayName: userName } = useCurrentUser()
const { weekdayText, dateText, quote, refreshDate, loadQuote } = useDashboardHeader('Dashboard')

interface StatCard {
  label: string
  value: number | string
  icon: string
  type: 'primary' | 'success' | 'warning' | 'info'
  path?: string | null
  /** 点击后滚动到的锚点 id（没有独立落地页的统计，如失败数） */
  anchor?: string
  /** 有值时卡片右下角展示对应类型的近 7 天迷你趋势线 */
  sparkKey?: 'copy' | 'strm' | 'rename'
}

function onStatClick(stat: StatCard) {
  if (stat.path) router.push(stat.path)
  else if (stat.anchor) document.getElementById(stat.anchor)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

const todoCard = ref<{ load: () => void } | null>(null)
const statCards = ref<StatCard[]>([])
const statLoading = ref(true)
const statError = ref(false)
const chartLoading = ref(true)
const trendSeries = ref<Record<'copy' | 'strm' | 'rename', number[]>>({ copy: [], strm: [], rename: [] })

type TrendType = 'copy' | 'strm' | 'rename'

/** 趋势缓存，键为 `类型:天数`。切 tab / 切天数来回切换不再重复请求；手动刷新与定时刷新时清空 */
const trendCache = new Map<string, DashboardTrendPoint[]>()

async function fetchTrend(type: TrendType, days: number): Promise<DashboardTrendPoint[]> {
  const key = `${type}:${days}`
  const hit = trendCache.get(key)
  if (hit) return hit
  const points = (await getDashboardTrendApi(type, days)) || []
  trendCache.set(key, points)
  return points
}

/** 统计卡 sparkline：三条近 7 天趋势并行取，单条失败不影响其余 */
async function loadSparklines() {
  const types: TrendType[] = ['copy', 'strm', 'rename']
  const results = await Promise.allSettled(types.map((t) => fetchTrend(t, 7)))
  results.forEach((r, i) => {
    if (r.status === 'fulfilled') {
      trendSeries.value[types[i]] = r.value.map((p) => p.totalCount)
    }
  })
}

async function loadStats() {
  // 后台刷新时保留旧数字，不闪骨架屏；只有首次加载或上次失败才显示加载态
  if (!statCards.value.length || statError.value) statLoading.value = true
  try {
    const statsData: any = await getDashboardStatsApi()
    const copyCount = statsData?.copyRecordCount ?? 0
    const strmCount = statsData?.strmRecordCount ?? 0
    const renameCount = statsData?.renameDetailCount ?? 0
    const successRate: number | null = statsData?.successRate ?? null
    const failedCount = statsData?.failedCount ?? 0
    const processingCount = statsData?.processingCount ?? 0
    statCards.value = [
      { label: 'COPY 任务', value: copyCount, icon: 'files', type: 'primary', path: getRoutePathForComponent('openlist/copyRecord/index'), sparkKey: 'copy' },
      { label: 'STRM 任务', value: strmCount, icon: 'video', type: 'success', path: getRoutePathForComponent('openlist/strmRecord/index'), sparkKey: 'strm' },
      { label: 'Rename 任务', value: renameCount, icon: 'square-pen', type: 'warning', path: getRoutePathForComponent('openlist/renameDetail/index'), sparkKey: 'rename' },
      { label: '成功率', value: successRate != null ? successRate + '%' : '--', icon: 'circle-check', type: 'info' },
      { label: '失败数', value: failedCount, icon: 'circle-x', type: 'warning', anchor: 'dashboard-failures' },
      { label: '处理中', value: processingCount, icon: 'loader-circle', type: 'primary', path: getRoutePathForComponent('openlist/copyRecord/index') }
    ]
    statError.value = false
  } catch (e) {
    console.error('[Dashboard] Failed to load stat cards:', e)
    statError.value = true
  } finally {
    statLoading.value = false
  }
}

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

  if (!points.length) {
    taskChart.clear()
    taskChart.setOption(chartEmptyOption('暂无数据'), true)
    return
  }

  // 外观全部来自 plugins/echartsTheme（坐标轴 / 网格 / 玻璃提示框 / 系列配色）。
  // 系列色改造前写死成 osrLight 的 #B4690E 等三个值，暗色下坐标轴换了、
  // 折线颜色没换，看起来像图表没刷新 —— 那是个真 bug，收到主题模块里一并修掉
  const base = chartBase()
  taskChart.setOption({
    ...base,
    legend: { ...base.legend, data: ['总数', '成功', '失败'] },
    xAxis: { ...base.xAxis, data: points.map(p => p.date.slice(5)) },
    series: [
      lineSeries({ name: '总数', data: points.map(p => p.totalCount), tone: 'primary' }),
      lineSeries({ name: '成功', data: points.map(p => p.successCount), tone: 'success' }),
      lineSeries({ name: '失败', data: points.map(p => p.failedCount), tone: 'error' })
    ]
  }, true)
}

let chartReqSeq = 0

async function loadTaskChart() {
  if (!taskChart) return
  const seq = ++chartReqSeq
  try {
    const points = await fetchTrend(activeTaskTab.value, taskDays.value)
    if (seq !== chartReqSeq) return // 已切 tab/天数，丢弃过期响应
    renderTrendChart(points)
  } catch (e) {
    if (seq !== chartReqSeq) return
    console.error('Failed to load task trend chart:', e)
    renderTrendChart([])
  }
}

/** 首屏并行：统计卡与趋势图互不依赖；sparkline 先填缓存，图表随后直接命中 */
async function loadAll() {
  await Promise.all([loadStats(), loadSparklines().then(loadTaskChart)])
  chartLoading.value = false
}

/** 页面重新可见且距上次加载超过该间隔才刷新，避免切标签页就狂发请求 */
const AUTO_REFRESH_MS = 60_000
let lastLoadedAt = Date.now()

function onVisibilityChange() {
  if (document.visibilityState !== 'visible') return
  refreshDate()
  if (Date.now() - lastLoadedAt < AUTO_REFRESH_MS) return
  lastLoadedAt = Date.now()
  trendCache.clear()
  loadAll()
  todoCard.value?.load()
}

let resizeHandler: (() => void) | null = null
let themeChangeHandler: (() => void) | null = null

onMounted(async () => {
  loadQuote()
  await nextTick()
  if (taskChartContainer.value) {
    taskChart = echarts.init(taskChartContainer.value)
  }
  await loadAll()
  lastLoadedAt = Date.now()

  resizeHandler = () => taskChart?.resize()
  window.addEventListener('resize', resizeHandler)
  document.addEventListener('visibilitychange', onVisibilityChange)

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

onUnmounted(() => {
  resizeHandler && window.removeEventListener('resize', resizeHandler)
  themeChangeHandler && document.removeEventListener('osr-theme-change', themeChangeHandler)
  document.removeEventListener('visibilitychange', onVisibilityChange)
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
  transition: transform var(--osr-transition-base), box-shadow var(--osr-transition-base);

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

/* 统计卡骨架：数字与标签位同 .stat-value / .stat-label 的行高 */
.stat-skeleton-value {
  width: 44px;
  height: 22px;
  margin: 1px 0;
}

.stat-skeleton-label {
  width: 64px;
  margin-top: 5px;
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
