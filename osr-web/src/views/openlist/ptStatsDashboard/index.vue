<template>
  <div class="pt-stats-dashboard">
    <PageHeader
      icon="mdi-chart-box-outline"
      title="PT 统计仪表盘"
      desc="下载量趋势、索引器命中率与失败原因分布"
    />

    <div class="toolbar">
      <span class="toolbar-label">统计范围</span>
      <v-btn-toggle v-model="rangeDays" color="primary" density="comfortable" variant="outlined" mandatory @update:model-value="onRangeChange">
        <v-btn :value="7">近7天</v-btn>
        <v-btn :value="30">近30天</v-btn>
        <v-btn :value="90">近90天</v-btn>
      </v-btn-toggle>
      <v-btn prepend-icon="mdi-refresh" variant="outlined" class="refresh-btn" @click="loadAll">刷新</v-btn>
    </div>

    <v-row class="stat-row">
      <v-col cols="12" md="4" v-for="(stat, index) in statCards" :key="index">
        <v-card class="stat-card" :class="stat.type">
          <div class="stat-icon">
            <v-icon :icon="stat.icon" size="28" />
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ stat.value }}</div>
            <div class="stat-label">{{ stat.label }}</div>
          </div>
        </v-card>
      </v-col>
    </v-row>

    <v-row class="chart-row">
      <v-col cols="12">
        <v-card class="chart-card">
          <v-card-title class="chart-header">
            <span class="chart-title">下载量趋势</span>
          </v-card-title>
          <v-card-text>
            <div ref="trendContainer" class="echarts-container trend-container" />
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <v-row class="chart-row">
      <v-col cols="12" md="6">
        <v-card class="chart-card">
          <v-card-title class="chart-header">
            <span class="chart-title">索引器命中率</span>
            <span class="chart-subtitle">基于每订阅最近 200 条匹配记录</span>
          </v-card-title>
          <v-card-text>
            <div ref="indexerContainer" class="echarts-container" />
            <div v-if="noDataIndexerNames.length" class="no-data-indexers">
              暂无数据：{{ noDataIndexerNames.join('、') }}
            </div>
          </v-card-text>
        </v-card>
      </v-col>
      <v-col cols="12" md="6">
        <v-card class="chart-card">
          <v-card-title class="chart-header">
            <span class="chart-title">失败原因分布</span>
          </v-card-title>
          <v-card-text>
            <div ref="failReasonContainer" class="echarts-container" />
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <v-row class="chart-row">
      <v-col cols="12">
        <v-card class="chart-card">
          <v-card-title class="chart-header">
            <span class="chart-title">Top 活跃订阅</span>
          </v-card-title>
          <v-data-table
            :headers="topSubHeaders"
            :items="topSubscriptions"
            :loading="topSubscriptionsLoading"
            items-per-page="-1"
            hide-default-footer
            class="modern-table"
          >
            <template #item.title="{ item }">
              <router-link
                :to="{ path: '/openlist/ptSubscription', query: { id: item.subId } }"
                class="stats-sub-link"
              >
                {{ item.title }}
              </router-link>
            </template>
            <template #item.seasonType="{ item }">
              <span v-if="item.mediaType === 'MOVIE'">电影</span>
              <span v-else-if="item.season != null">S{{ item.season }}</span>
              <span v-else>-</span>
            </template>
            <template #item.lastMatchTime="{ item }">{{ item.lastMatchTime || '-' }}</template>
            <template #no-data>
              <v-empty-state icon="mdi-inbox-outline" title="暂无数据" />
            </template>
          </v-data-table>
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { osrCssVar } from '@/composables/useThemeMode'
// 按需引入：本页只用到 line/bar/pie，避免全量引入 echarts 拖大打包体积
import * as echarts from 'echarts/core'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import {
  getPtStatsOverviewApi,
  getPtStatsTrendApi,
  getPtStatsIndexerHitRateApi,
  getPtStatsFailReasonsApi,
  getPtStatsTopSubscriptionsApi,
  type PtStatsActiveSubscription
} from '@/api/openlist/ptStats'

echarts.use([LineChart, BarChart, PieChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer])

interface StatCard {
  label: string
  value: number | string
  icon: string
  type: 'primary' | 'success' | 'warning' | 'info'
}

const rangeDays = ref(30)
const statCards = ref<StatCard[]>([])
const topSubscriptions = ref<PtStatsActiveSubscription[]>([])
const topSubscriptionsLoading = ref(false)
const noDataIndexerNames = ref<string[]>([])

const topSubHeaders = [
  { title: '订阅标题', key: 'title', minWidth: '180' },
  { title: '季/类型', key: 'seasonType', width: '100' },
  { title: '下载次数', key: 'downloadCount', width: '100' },
  { title: '完成数', key: 'completedCount', width: '100' },
  { title: '失败数', key: 'failedCount', width: '100' },
  { title: '上次命中时间', key: 'lastMatchTime', width: '180' }
]

const trendContainer = ref<HTMLElement | null>(null)
const indexerContainer = ref<HTMLElement | null>(null)
const failReasonContainer = ref<HTMLElement | null>(null)

let trendChart: any = null
let indexerChart: any = null
let failReasonChart: any = null
let resizeHandler: (() => void) | null = null

const defaultColors = ['#B4690E', '#3F8F5F', '#C98A1E', '#C0362C', '#4C6C93', '#8A5A9E', '#D98A2B', '#3B4B6B']

// 失败原因分布的配色：照抄 views/dashboard/desktop.vue 的 colorMap/getColor 实现思路
// （同色系映射：名字里带"失败"字样的用红色，其余落到 defaultColors 轮转），
// 设计文档6.1节明确"直接照抄这段逻辑到本页面"，两处各自独立演化更简单，不抽公共 util。
const failReasonColorMap: Record<string, string> = {
  '成功': '#3F8F5F',
  '失败': '#C0362C',
  '未知': '#C98A1E',
  '处理中': '#B4690E'
}

function getFailReasonColor(name: string): string {
  if (failReasonColorMap[name]) return failReasonColorMap[name]
  const idx = Object.keys(failReasonColorMap).findIndex(k => name.includes(k))
  return idx >= 0
    ? failReasonColorMap[Object.keys(failReasonColorMap)[idx]]
    : defaultColors[(Object.keys(failReasonColorMap).length + idx) % defaultColors.length]
}

function emptyOption(text: string) {
  return { title: { text, left: 'center', top: 'center', textStyle: { fontSize: 14, color: osrCssVar('--osr-text-placeholder') || '#94a3b8' } }, series: [] }
}

async function loadOverview() {
  try {
    const data = await getPtStatsOverviewApi()
    statCards.value = [
      { label: '总订阅数', value: data.totalSubscriptions, icon: 'mdi-file-document-outline', type: 'primary' },
      { label: '活跃订阅数', value: data.activeSubscriptions, icon: 'mdi-lan-connect', type: 'success' },
      { label: '下载记录总数', value: data.totalDownloadRecords, icon: 'mdi-download', type: 'info' },
      { label: '成功率', value: data.totalDownloadRecords > 0 ? data.successRate + '%' : '--', icon: 'mdi-check-circle-outline', type: 'success' },
      { label: '平均下载耗时', value: data.avgDurationMinutes > 0 ? Math.round(data.avgDurationMinutes) + ' 分钟' : '--', icon: 'mdi-clock-outline', type: 'warning' }
    ]
  } catch (e) {
    console.error('[PtStatsDashboard] Failed to load overview:', e)
    statCards.value = [
      { label: '总订阅数', value: '0', icon: 'mdi-file-document-outline', type: 'primary' },
      { label: '活跃订阅数', value: '0', icon: 'mdi-lan-connect', type: 'success' },
      { label: '下载记录总数', value: '0', icon: 'mdi-download', type: 'info' },
      { label: '成功率', value: '--', icon: 'mdi-check-circle-outline', type: 'success' },
      { label: '平均下载耗时', value: '--', icon: 'mdi-clock-outline', type: 'warning' }
    ]
  }
}

async function loadTrend() {
  if (!trendContainer.value) return
  if (!trendChart) trendChart = echarts.init(trendContainer.value)
  try {
    const data = await getPtStatsTrendApi(rangeDays.value)
    if (!data || data.length === 0) {
      trendChart.clear()
      trendChart.setOption(emptyOption('暂无数据'), true)
      return
    }
    trendChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: ['推送', '完成', '失败'], top: 0, textStyle: { color: osrCssVar('--osr-text-secondary') } },
      grid: { left: 40, right: 20, top: 40, bottom: 30 },
      xAxis: { type: 'category', data: data.map(p => p.date), axisLabel: { color: osrCssVar('--osr-text-secondary') } },
      yAxis: { type: 'value', axisLabel: { color: osrCssVar('--osr-text-secondary') }, splitLine: { lineStyle: { color: osrCssVar('--osr-border-light') } } },
      series: [
        { name: '推送', type: 'line', data: data.map(p => p.pushedCount), itemStyle: { color: '#B4690E' } },
        { name: '完成', type: 'line', data: data.map(p => p.completedCount), itemStyle: { color: '#3F8F5F' } },
        { name: '失败', type: 'line', data: data.map(p => p.failedCount), itemStyle: { color: '#C0362C' } }
      ]
    }, true)
  } catch (e) {
    console.error('[PtStatsDashboard] Failed to load trend:', e)
    if (trendChart) {
      trendChart.clear()
      trendChart.setOption(emptyOption('加载失败'), true)
    }
  }
}

async function loadIndexerHitRate() {
  if (!indexerContainer.value) return
  if (!indexerChart) indexerChart = echarts.init(indexerContainer.value)
  try {
    const data = await getPtStatsIndexerHitRateApi()
    noDataIndexerNames.value = (data || []).filter(i => !i.hasData).map(i => i.indexerName)
    const withData = (data || []).filter(i => i.hasData)
    if (withData.length === 0) {
      indexerChart.clear()
      indexerChart.setOption(emptyOption('暂无数据'), true)
      return
    }
    indexerChart.setOption({
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      legend: { data: ['通过', '淘汰'], top: 0, textStyle: { color: osrCssVar('--osr-text-secondary') } },
      grid: { left: 100, right: 20, top: 40, bottom: 20 },
      xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%', color: osrCssVar('--osr-text-secondary') }, splitLine: { lineStyle: { color: osrCssVar('--osr-border-light') } } },
      yAxis: { type: 'category', data: withData.map(i => i.indexerName), axisLabel: { color: osrCssVar('--osr-text-secondary') } },
      series: [
        { name: '通过', type: 'bar', stack: 'total', itemStyle: { color: '#3F8F5F' },
          data: withData.map(i => Math.round(i.hitRate * 1000) / 10) },
        { name: '淘汰', type: 'bar', stack: 'total', itemStyle: { color: '#C0362C' },
          data: withData.map(i => Math.round((1 - i.hitRate) * 1000) / 10) }
      ]
    }, true)
  } catch (e) {
    console.error('[PtStatsDashboard] Failed to load indexer hit rate:', e)
    if (indexerChart) {
      indexerChart.clear()
      indexerChart.setOption(emptyOption('加载失败'), true)
    }
  }
}

async function loadFailReasons() {
  if (!failReasonContainer.value) return
  if (!failReasonChart) failReasonChart = echarts.init(failReasonContainer.value)
  try {
    const data = await getPtStatsFailReasonsApi(rangeDays.value)
    if (!data || data.length === 0) {
      failReasonChart.clear()
      failReasonChart.setOption(emptyOption('暂无数据'), true)
      return
    }
    failReasonChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
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
          itemStyle: { color: getFailReasonColor(item.reason) }
        }))
      }]
    }, true)
  } catch (e) {
    console.error('[PtStatsDashboard] Failed to load fail reasons:', e)
    if (failReasonChart) {
      failReasonChart.clear()
      failReasonChart.setOption(emptyOption('加载失败'), true)
    }
  }
}

async function loadTopSubscriptions() {
  topSubscriptionsLoading.value = true
  try {
    const data = await getPtStatsTopSubscriptionsApi(rangeDays.value, 10)
    topSubscriptions.value = data || []
  } catch (e) {
    console.error('[PtStatsDashboard] Failed to load top subscriptions:', e)
    topSubscriptions.value = []
  } finally {
    topSubscriptionsLoading.value = false
  }
}

async function loadAll() {
  await Promise.all([loadOverview(), loadTrend(), loadIndexerHitRate(), loadFailReasons(), loadTopSubscriptions()])
}

async function onRangeChange() {
  // 索引器命中率(2.4)和总览(2.1)不受时间挡位影响，只重新加载 trend/failReasons/topSubscriptions
  await Promise.all([loadTrend(), loadFailReasons(), loadTopSubscriptions()])
}

onMounted(async () => {
  await nextTick()
  await loadAll()

  resizeHandler = () => {
    trendChart?.resize()
    indexerChart?.resize()
    failReasonChart?.resize()
  }
  window.addEventListener('resize', resizeHandler)

  // 主题切换后重绘图表（canvas 无法用 CSS 变量）
  themeChangeHandler = () => {
    loadTrend()
    loadIndexerHitRate()
    loadFailReasons()
  }
  document.addEventListener('osr-theme-change', themeChangeHandler)
})

let themeChangeHandler: (() => void) | null = null

onUnmounted(() => {
  resizeHandler && window.removeEventListener('resize', resizeHandler)
  themeChangeHandler && document.removeEventListener('osr-theme-change', themeChangeHandler)
  trendChart?.dispose()
  indexerChart?.dispose()
  failReasonChart?.dispose()
})
</script>

<style scoped lang="scss">
.pt-stats-dashboard {
  padding: 24px;
}

/* 本页根容器不是 .page-container，PageHeader 的下间距在这里补 */
:deep(.page-header) {
  margin-bottom: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;

  .toolbar-label {
    font-size: 14px;
    color: var(--osr-text-secondary);
  }

  .refresh-btn {
    margin-left: auto;
  }
}

.stat-row {
  margin-bottom: 24px;
}

.stat-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);
  margin-bottom: 16px;
  cursor: default;
  transition: all var(--osr-transition-base);

  display: flex;
  align-items: center;
  padding: 20px;
  gap: 16px;

  &:hover {
    transform: translateY(-2px);
    box-shadow: var(--osr-shadow-md);
  }

  .stat-icon {
    width: 52px;
    height: 52px;
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
      font-size: 24px;
      font-weight: 700;
      color: var(--osr-text-primary);
      line-height: 1.2;
    }

    .stat-label {
      font-size: 13px;
      color: var(--osr-text-secondary);
      margin-top: 2px;
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

.chart-row {
  margin-bottom: 16px;
}

.chart-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);
  margin-bottom: 16px;
  transition: box-shadow var(--osr-transition-base);

  &:hover {
    box-shadow: var(--osr-shadow-md);
  }

  :deep(.v-card-title) {
    padding: 16px 20px;
    border-bottom: 1px solid var(--osr-border-light);
    background-color: var(--osr-surface);
  }
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .chart-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--osr-text-primary);
  }

  .chart-subtitle {
    font-size: 12px;
    color: var(--osr-text-secondary);
  }
}

.echarts-container {
  height: 260px;
  width: 100%;
}

.trend-container {
  height: 300px;
}

.no-data-indexers {
  margin-top: 8px;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.stats-sub-link {
  color: var(--osr-primary);
  text-decoration: none;
  font-weight: 500;
  &:hover {
    text-decoration: underline;
    color: var(--osr-primary-hover);
  }
}

@media (max-width: 768px) {
  .pt-stats-dashboard {
    padding: 16px;
  }

  .stat-card {
    padding: 16px;
  }

  .echarts-container {
    height: 220px !important;
  }
}
</style>
