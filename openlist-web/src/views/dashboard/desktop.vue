<template>
  <div class="dashboard">
    <!-- Welcome header -->
    <div class="welcome-header">
      <div class="welcome-text">
        <div class="welcome-title">欢迎回来，{{ userName }}</div>
        <div class="welcome-quote">{{ quote }}</div>
      </div>
      <div class="welcome-date">{{ todayText }}</div>
    </div>

    <!-- Stat Cards -->
    <v-row class="stat-row" dense>
      <v-col cols="6" md="2" v-for="(stat, index) in statCards" :key="index">
        <v-card
          class="stat-card"
          :class="[stat.type, { clickable: !!stat.path }]"
          @click="stat.path && router.push(stat.path)"
        >
          <div class="stat-icon">
            <v-icon :icon="stat.icon" size="16" />
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ stat.value }}</div>
            <div class="stat-label">{{ stat.label }}</div>
          </div>
        </v-card>
      </v-col>
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
          <div ref="taskChartContainer" class="echarts-container" />
        </v-card>
      </v-col>

      <v-col cols="12" md="4">
        <v-card class="pt-card">
          <div class="chart-header">
            <span class="chart-title">PT 订阅概览</span>
            <v-btn variant="text" size="small" color="primary" @click="goPtStats">查看详情</v-btn>
          </div>
          <div class="pt-overview-grid">
            <div class="pt-overview-item">
              <div class="pt-overview-value">{{ ptOverview.totalSubscriptions ?? '--' }}</div>
              <div class="pt-overview-label">订阅总数</div>
            </div>
            <div class="pt-overview-item">
              <div class="pt-overview-value">{{ ptOverview.activeSubscriptions ?? '--' }}</div>
              <div class="pt-overview-label">活跃订阅</div>
            </div>
            <div class="pt-overview-item">
              <div class="pt-overview-value">{{ ptOverview.successRate != null ? ptOverview.successRate + '%' : '--' }}</div>
              <div class="pt-overview-label">下载成功率</div>
            </div>
            <div class="pt-overview-item">
              <div class="pt-overview-value">{{ ptOverview.avgDurationMinutes != null ? ptOverview.avgDurationMinutes + '分' : '--' }}</div>
              <div class="pt-overview-label">平均耗时</div>
            </div>
          </div>
          <v-divider class="my-2" />
          <div class="pt-top-list">
            <div class="pt-top-title">热门订阅 Top {{ ptTopSubscriptions.length }}</div>
            <div v-if="!ptTopSubscriptions.length" class="pt-top-empty">暂无数据</div>
            <div v-for="sub in ptTopSubscriptions" :key="sub.subId" class="pt-top-item">
              <span class="pt-top-name" :title="sub.title">{{ sub.title }}</span>
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
          <div v-if="!recentFailures.length" class="empty-tip">暂无失败记录</div>
          <div v-else class="failure-list">
            <div
              v-for="f in recentFailures"
              :key="f.type + '-' + f.id"
              class="failure-item"
              @click="f.path && router.push(f.path)"
            >
              <v-chip size="small" :color="f.color" variant="tonal" class="failure-tag">{{ f.typeLabel }}</v-chip>
              <span class="failure-name" :title="f.name">{{ f.name }}</span>
              <span class="failure-time">{{ f.time }}</span>
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
            <v-btn
              v-for="link in quickLinks"
              :key="link.label"
              variant="tonal"
              size="small"
              :prepend-icon="link.icon"
              :disabled="!link.path"
              @click="link.path && router.push(link.path)"
            >
              {{ link.label }}
            </v-btn>
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
import { getDashboardStatsApi, getDashboardTrendApi, type DashboardTrendPoint } from '@/api/openlist/dashboard'
import { getHitokotoApi } from '@/api/openlist/hitokoto'
import { getStrmRecordListApi } from '@/api/openlist/strmRecord'
import { getCopyRecordListApi } from '@/api/openlist/copyRecord'
import { getRenameDetailListApi } from '@/api/openlist/renameDetail'
import { getPtStatsOverviewApi, getPtStatsTopSubscriptionsApi, type PtStatsOverview, type PtStatsActiveSubscription } from '@/api/openlist/ptStats'

echarts.use([LineChart, TitleComponent, TooltipComponent, GridComponent, LegendComponent, CanvasRenderer])

const router = useRouter()
const userStore = useUserStore()
const userName = computed(() => userStore.userInfo?.userName || userStore.userInfo?.loginName || '管理员')
const todayText = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })

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
}

const statCards = ref<StatCard[]>([])

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
      { label: 'COPY 任务', value: copyCount, icon: 'mdi-file-multiple-outline', type: 'primary', path: getRoutePathForComponent('openlist/copyRecord/index') },
      { label: 'STRM 任务', value: strmCount, icon: 'mdi-video-outline', type: 'success', path: getRoutePathForComponent('openlist/strmRecord/index') },
      { label: 'Rename 任务', value: renameCount, icon: 'mdi-pencil-outline', type: 'warning', path: getRoutePathForComponent('openlist/renameDetail/index') },
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
  taskChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['总数', '成功', '失败'], top: 4, itemWidth: 12, itemHeight: 12, textStyle: { fontSize: 12 } },
    grid: { left: 36, right: 16, top: 40, bottom: 24 },
    xAxis: { type: 'category', data: points.map(p => p.date.slice(5)), axisLabel: { fontSize: 11 } },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { fontSize: 11 } },
    series: [
      { name: '总数', type: 'line', smooth: true, data: points.map(p => p.totalCount), itemStyle: { color: '#6366f1' }, lineStyle: { width: 2 } },
      { name: '成功', type: 'line', smooth: true, data: points.map(p => p.successCount), itemStyle: { color: '#22c55e' }, lineStyle: { width: 2 } },
      { name: '失败', type: 'line', smooth: true, data: points.map(p => p.failedCount), itemStyle: { color: '#ef4444' }, lineStyle: { width: 2 } }
    ]
  }, true)
}

async function loadTaskChart() {
  if (!taskChart) return
  try {
    const points = await getDashboardTrendApi(activeTaskTab.value, taskDays.value)
    renderTrendChart(points || [])
  } catch (e) {
    console.error('Failed to load task trend chart:', e)
    renderTrendChart([])
  }
}

/* ============================================
   PT subscription overview
   ============================================ */
const ptOverview = ref<Partial<PtStatsOverview>>({})
const ptTopSubscriptions = ref<PtStatsActiveSubscription[]>([])

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
  id: number | string
  name: string
  time: string
  path: string | null
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
      items.push({ type: 'strm', typeLabel: 'STRM', color: 'success', id: r.strmId, name: r.strmFileName, time: r.createTime, path: strmPath })
    }
  } catch (e) {
    console.error('[Dashboard] Failed to load strm failures:', e)
  }

  try {
    const res: any = await getCopyRecordListApi({ pageNum: 1, pageSize: 5, copyStatus: '0' })
    for (const r of res?.records || []) {
      items.push({ type: 'copy', typeLabel: 'COPY', color: 'primary', id: r.copyId, name: r.copySrcFileName, time: r.createTime, path: copyPath })
    }
  } catch (e) {
    console.error('[Dashboard] Failed to load copy failures:', e)
  }

  try {
    const res: any = await getRenameDetailListApi({ pageNum: 1, pageSize: 5, status: '0' })
    for (const r of res?.records || []) {
      items.push({ type: 'rename', typeLabel: 'Rename', color: 'warning', id: r.id, name: r.originalName, time: r.createTime, path: renamePath })
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
const quickLinks = computed(() => [
  { label: 'STRM 任务', icon: 'mdi-video-outline', path: getRoutePathForComponent('openlist/strmTask/index') },
  { label: 'COPY 任务', icon: 'mdi-file-multiple-outline', path: getRoutePathForComponent('openlist/copyTask/index') },
  { label: 'Rename 任务', icon: 'mdi-pencil-outline', path: getRoutePathForComponent('openlist/renameTask/index') },
  { label: 'PT 订阅', icon: 'mdi-bookmark-outline', path: getRoutePathForComponent('openlist/ptSubscription/index') },
  { label: 'PT 统计', icon: 'mdi-chart-line', path: getRoutePathForComponent('openlist/ptStatsDashboard/index') },
  { label: '孤儿扫描', icon: 'mdi-file-search-outline', path: getRoutePathForComponent('openlist/renameOrphan/index') }
])

let resizeHandler: (() => void) | null = null

onMounted(async () => {
  await nextTick()
  if (taskChartContainer.value) {
    taskChart = echarts.init(taskChartContainer.value)
  }
  await Promise.all([loadTaskChart(), loadPtOverview(), loadRecentFailures()])

  resizeHandler = () => taskChart?.resize()
  window.addEventListener('resize', resizeHandler)
})

onUnmounted(() => {
  resizeHandler && window.removeEventListener('resize', resizeHandler)
  taskChart?.dispose()
})
</script>

<style scoped lang="scss">
.dashboard {
  padding: 24px;
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
  }

  .welcome-date {
    flex-shrink: 0;
    font-size: 13px;
    color: var(--osr-text-secondary);
    padding-top: 2px;
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
  padding: 8px 10px;
  gap: 8px;

  .stat-icon {
    width: 28px;
    height: 28px;
    border-radius: var(--osr-radius-md);
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }

  .stat-info {
    flex: 1;
    min-width: 0;

    .stat-value {
      font-size: 16px;
      font-weight: 700;
      color: var(--osr-text-primary);
      line-height: 1.2;
    }

    .stat-label {
      font-size: 11px;
      color: var(--osr-text-secondary);
      margin-top: 1px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  &.primary .stat-icon {
    background-color: var(--osr-primary-light-9);
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

  .pt-overview-value {
    font-size: 20px;
    font-weight: 700;
    color: var(--osr-text-primary);
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
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px dashed var(--osr-border-light);

  &:last-child {
    border-bottom: none;
  }

  .pt-top-name {
    color: var(--osr-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    flex: 1;
    margin-right: 8px;
  }

  .pt-top-count {
    color: var(--osr-text-secondary);
    flex-shrink: 0;
  }
}

/* ============================================
   Recent failures
   ============================================ */
.empty-tip {
  padding: 40px 0;
  text-align: center;
  color: var(--osr-text-secondary);
  font-size: 13px;
}

.failure-list {
  padding: 4px 20px 12px;
}

.failure-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--osr-border-light);
  cursor: pointer;

  &:last-child {
    border-bottom: none;
  }

  &:hover .failure-name {
    color: var(--osr-primary);
  }

  .failure-tag {
    flex-shrink: 0;
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
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 16px 20px;
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
