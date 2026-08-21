<template>
  <div class="mobile-pt-stats">
    <div class="toolbar">
      <span class="toolbar-label">统计范围</span>
      <v-btn-toggle v-model="rangeDays" color="primary" density="compact" variant="outlined" mandatory @update:model-value="onRangeChange">
        <v-btn :value="7" size="small">近7天</v-btn>
        <v-btn :value="30" size="small">近30天</v-btn>
        <v-btn :value="90" size="small">近90天</v-btn>
      </v-btn-toggle>
      <v-btn prepend-icon="mdi-refresh" size="small" variant="outlined" @click="loadAll">刷新</v-btn>
    </div>

    <div class="stat-grid">
      <v-card v-for="(stat, index) in statCards" :key="index" class="stat-card" :class="stat.type">
        <div class="stat-icon">
          <v-icon :icon="stat.icon" size="22" />
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ stat.value }}</div>
          <div class="stat-label">{{ stat.label }}</div>
        </div>
      </v-card>
    </div>

    <v-card class="chart-card">
      <div class="chart-header">
        <span class="chart-title">下载量趋势</span>
      </div>
      <div ref="trendContainer" class="echarts-container" />
    </v-card>

    <v-card class="chart-card">
      <div class="chart-header">
        <span class="chart-title">索引器命中率</span>
        <span class="chart-subtitle">基于每订阅最近 200 条匹配记录</span>
      </div>
      <div ref="indexerContainer" class="echarts-container" />
      <div v-if="noDataIndexerNames.length" class="no-data-indexers">
        暂无数据：{{ noDataIndexerNames.join('、') }}
      </div>
    </v-card>

    <v-card class="chart-card">
      <div class="chart-header">
        <span class="chart-title">失败原因分布</span>
      </div>
      <div ref="failReasonContainer" class="echarts-container" />
    </v-card>

    <v-card class="chart-card">
      <div class="chart-header">
        <span class="chart-title">Top 活跃订阅</span>
      </div>
      <div>
        <v-progress-linear v-if="topSubscriptionsLoading" indeterminate color="primary" class="list-loading" />
        <div v-for="row in topSubscriptions" :key="row.subId ?? row.title" class="top-sub-item">
          <div class="top-sub-title">
            <router-link
              v-if="row.subId"
              :to="{ path: '/openlist/ptSubscription', query: { id: row.subId } }"
              class="top-sub-link"
            >{{ row.title }}</router-link>
            <span v-else>{{ row.title }}</span>
            <span class="top-sub-season">
              {{ row.mediaType === 'MOVIE' ? '电影' : row.season != null ? `S${row.season}` : '-' }}
            </span>
          </div>
          <div class="top-sub-meta">
            <span>下载 {{ row.downloadCount }}</span>
            <span>完成 {{ row.completedCount }}</span>
            <span>失败 {{ row.failedCount }}</span>
          </div>
          <div class="top-sub-meta">
            <span>上次命中 {{ row.lastMatchTime || '-' }}</span>
          </div>
        </div>
        <v-empty-state v-if="!topSubscriptionsLoading && topSubscriptions.length === 0" icon="mdi-inbox-outline" title="暂无数据" />
      </div>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { osrCssVar } from '@/composables/useThemeMode'
import { barSeries, chartBase, chartEmptyOption, lineSeries } from '@/plugins/echartsTheme'
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

const trendContainer = ref<HTMLElement | null>(null)
const indexerContainer = ref<HTMLElement | null>(null)
const failReasonContainer = ref<HTMLElement | null>(null)

let trendChart: any = null
let indexerChart: any = null
let failReasonChart: any = null
let resizeHandler: (() => void) | null = null

const defaultColors = ['#B4690E', '#3F8F5F', '#C98A1E', '#C0362C', '#4C6C93', '#8A5A9E', '#D98A2B', '#3B4B6B']

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
  return chartEmptyOption(text)
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
    const base = chartBase()
    trendChart.setOption({
      ...base,
      legend: { ...base.legend, data: ['推送', '完成', '失败'], top: 0 },
      grid: { ...base.grid, top: 40, bottom: 30 },
      xAxis: { ...base.xAxis, data: data.map(p => p.date) },
      series: [
        lineSeries({ name: '推送', data: data.map(p => p.pushedCount), tone: 'primary' }),
        lineSeries({ name: '完成', data: data.map(p => p.completedCount), tone: 'success' }),
        lineSeries({ name: '失败', data: data.map(p => p.failedCount), tone: 'error' })
      ]
    }, true)
  } catch (e) {
    console.error('[PtStatsDashboard] Failed to load trend:', e)
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
    // 横向条形图：两条轴的角色与折线图相反，样式要互换着取（同 PC 端注释）
    const base = chartBase()
    indexerChart.setOption({
      ...base,
      tooltip: { ...base.tooltip, axisPointer: { type: 'shadow' } },
      legend: { ...base.legend, data: ['通过', '淘汰'], top: 0 },
      grid: { left: 100, right: 20, top: 40, bottom: 20 },
      xAxis: { ...base.yAxis, type: 'value', max: 100, axisLabel: { ...base.yAxis.axisLabel, formatter: '{value}%' } },
      yAxis: { ...base.xAxis, type: 'category', data: withData.map(i => i.indexerName), splitLine: { show: false } },
      series: [
        barSeries({ name: '通过', tone: 'success', stack: 'total',
          data: withData.map(i => Math.round(i.hitRate * 1000) / 10) }),
        barSeries({ name: '淘汰', tone: 'error', stack: 'total',
          data: withData.map(i => Math.round((1 - i.hitRate) * 1000) / 10) })
      ]
    }, true)
  } catch (e) {
    console.error('[PtStatsDashboard] Failed to load indexer hit rate:', e)
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
.mobile-pt-stats {
  padding-bottom: 8px;
}

.toolbar-label {
  font-size: 13px;
  color: var(--osr-text-secondary);
  white-space: nowrap;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.stat-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 12px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;

  .stat-icon {
    width: 40px;
    height: 40px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }

  .stat-info {
    flex: 1;
    min-width: 0;

    .stat-value {
      font-size: 18px;
      font-weight: 700;
      color: var(--osr-text-primary);
      line-height: 1.2;
    }

    .stat-label {
      font-size: 12px;
      color: var(--osr-text-secondary);
      margin-top: 2px;
    }
  }

  &.primary .stat-icon { background: var(--osr-primary-subtle); color: var(--osr-primary); }
  &.success .stat-icon { background: var(--osr-success-light); color: var(--osr-success); }
  &.warning .stat-icon { background: var(--osr-warning-light); color: var(--osr-warning); }
  &.info .stat-icon { background: var(--osr-info-light); color: var(--osr-info); }
}

.chart-card {
  padding: 12px;
  margin-bottom: 12px;

  .chart-header {
    margin-bottom: 8px;

    .chart-title {
      font-size: 14px;
      font-weight: 600;
      color: var(--osr-text-primary);
    }

    .chart-subtitle {
      font-size: 11px;
      color: var(--osr-text-secondary);
      display: block;
    }
  }
}

.echarts-container {
  height: 220px;
  width: 100%;
}

.no-data-indexers {
  margin-top: 8px;
  font-size: 11px;
  color: var(--osr-text-secondary);
}

.top-sub-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--osr-border-light);

  &:last-child { border-bottom: none; }

  .top-sub-title {
    display: flex;
    align-items: baseline;
    gap: 6px;
    font-size: 13px;
    font-weight: 500;
    color: var(--osr-text-primary);
    overflow: hidden;

    .top-sub-link {
      color: var(--osr-primary);
      text-decoration: none;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .top-sub-season {
      flex-shrink: 0;
      font-size: 11px;
      font-weight: 400;
      color: var(--osr-text-secondary);
    }
  }

  .top-sub-meta {
    display: flex;
    gap: 12px;
    margin-top: 4px;
    font-size: 11px;
    color: var(--osr-text-secondary);
  }
}

.list-loading {
  border-radius: var(--osr-radius-base);
  margin-bottom: 8px;
}

</style>
