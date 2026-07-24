<template>
  <div class="pt-stats-dashboard">
    <div class="toolbar">
      <span class="toolbar-label">统计范围</span>
      <el-radio-group v-model="rangeDays" size="default" @change="onRangeChange">
        <el-radio-button :label="7">近7天</el-radio-button>
        <el-radio-button :label="30">近30天</el-radio-button>
        <el-radio-button :label="90">近90天</el-radio-button>
      </el-radio-group>
      <el-button :icon="Refresh" class="refresh-btn" @click="loadAll">刷新</el-button>
    </div>

    <el-row :gutter="16" class="stat-row">
      <el-col :md="8" v-for="(stat, index) in statCards" :key="index">
        <el-card class="stat-card" :class="stat.type">
          <div class="stat-icon">
            <el-icon :size="28"><component :is="stat.icon" /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ stat.value }}</div>
            <div class="stat-label">{{ stat.label }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :md="24">
        <el-card class="chart-card">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">下载量趋势</span>
            </div>
          </template>
          <div ref="trendContainer" class="echarts-container trend-container" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :md="12">
        <el-card class="chart-card">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">索引器命中率</span>
              <span class="chart-subtitle">基于每订阅最近 200 条匹配记录</span>
            </div>
          </template>
          <div ref="indexerContainer" class="echarts-container" />
          <div v-if="noDataIndexerNames.length" class="no-data-indexers">
            暂无数据：{{ noDataIndexerNames.join('、') }}
          </div>
        </el-card>
      </el-col>
      <el-col :md="12">
        <el-card class="chart-card">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">失败原因分布</span>
            </div>
          </template>
          <div ref="failReasonContainer" class="echarts-container" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :md="24">
        <el-card class="chart-card">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">Top 活跃订阅</span>
            </div>
          </template>
          <el-table :data="topSubscriptions" v-loading="topSubscriptionsLoading" style="width: 100%">
            <el-table-column prop="title" label="订阅标题" min-width="180" />
            <el-table-column label="季/类型" width="100">
              <template #default="{ row }">
                <span v-if="row.mediaType === 'MOVIE'">电影</span>
                <span v-else-if="row.season != null">S{{ row.season }}</span>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column prop="downloadCount" label="下载次数" width="100" />
            <el-table-column prop="completedCount" label="完成数" width="100" />
            <el-table-column prop="failedCount" label="失败数" width="100" />
            <el-table-column label="上次命中时间" width="180">
              <template #default="{ row }">{{ row.lastMatchTime || '-' }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
// 按需引入：本页只用到 line/bar/pie，避免全量引入 echarts 拖大打包体积
import * as echarts from 'echarts/core'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { Document, Connection, Download, CircleCheck, Clock, Refresh } from '@element-plus/icons-vue'
import {
  getPtStatsOverviewApi,
  getPtStatsTrendApi,
  getPtStatsIndexerHitRateApi,
  getPtStatsFailReasonsApi,
  getPtStatsTopSubscriptionsApi,
  type PtStatsActiveSubscription
} from '@/api/openlist/ptStats'
import type { Component } from 'vue'

echarts.use([LineChart, BarChart, PieChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer])

interface StatCard {
  label: string
  value: number | string
  icon: Component
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

const defaultColors = ['#0d9488', '#22c55e', '#f59e0b', '#ef4444', '#6366f1', '#8b5cf6', '#ec4899', '#14b8a6']

// 失败原因分布的配色：照抄 views/dashboard/desktop.vue 的 colorMap/getColor 实现思路
// （同色系映射：名字里带"失败"字样的用红色，其余落到 defaultColors 轮转），
// 设计文档6.1节明确"直接照抄这段逻辑到本页面"，两处各自独立演化更简单，不抽公共 util。
const failReasonColorMap: Record<string, string> = {
  '成功': '#22c55e',
  '失败': '#ef4444',
  '未知': '#f59e0b',
  '处理中': '#0d9488'
}

function getFailReasonColor(name: string): string {
  if (failReasonColorMap[name]) return failReasonColorMap[name]
  const idx = Object.keys(failReasonColorMap).findIndex(k => name.includes(k))
  return idx >= 0
    ? failReasonColorMap[Object.keys(failReasonColorMap)[idx]]
    : defaultColors[(Object.keys(failReasonColorMap).length + idx) % defaultColors.length]
}

function emptyOption(text: string) {
  return { title: { text, left: 'center', top: 'center', textStyle: { fontSize: 14, color: '#94a3b8' } }, series: [] }
}

async function loadOverview() {
  try {
    const data = await getPtStatsOverviewApi()
    statCards.value = [
      { label: '总订阅数', value: data.totalSubscriptions, icon: Document, type: 'primary' },
      { label: '活跃订阅数', value: data.activeSubscriptions, icon: Connection, type: 'success' },
      { label: '下载记录总数', value: data.totalDownloadRecords, icon: Download, type: 'info' },
      { label: '成功率', value: data.totalDownloadRecords > 0 ? data.successRate + '%' : '--', icon: CircleCheck, type: 'success' },
      { label: '平均下载耗时', value: data.avgDurationMinutes > 0 ? Math.round(data.avgDurationMinutes) + ' 分钟' : '--', icon: Clock, type: 'warning' }
    ]
  } catch (e) {
    console.error('[PtStatsDashboard] Failed to load overview:', e)
    statCards.value = [
      { label: '总订阅数', value: '0', icon: Document, type: 'primary' },
      { label: '活跃订阅数', value: '0', icon: Connection, type: 'success' },
      { label: '下载记录总数', value: '0', icon: Download, type: 'info' },
      { label: '成功率', value: '--', icon: CircleCheck, type: 'success' },
      { label: '平均下载耗时', value: '--', icon: Clock, type: 'warning' }
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
      legend: { data: ['推送', '完成', '失败'], top: 0 },
      grid: { left: 40, right: 20, top: 40, bottom: 30 },
      xAxis: { type: 'category', data: data.map(p => p.date) },
      yAxis: { type: 'value' },
      series: [
        { name: '推送', type: 'line', data: data.map(p => p.pushedCount), itemStyle: { color: '#0d9488' } },
        { name: '完成', type: 'line', data: data.map(p => p.completedCount), itemStyle: { color: '#22c55e' } },
        { name: '失败', type: 'line', data: data.map(p => p.failedCount), itemStyle: { color: '#ef4444' } }
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
      legend: { data: ['通过', '淘汰'], top: 0 },
      grid: { left: 100, right: 20, top: 40, bottom: 20 },
      xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
      yAxis: { type: 'category', data: withData.map(i => i.indexerName) },
      series: [
        { name: '通过', type: 'bar', stack: 'total', itemStyle: { color: '#22c55e' },
          data: withData.map(i => Math.round(i.hitRate * 1000) / 10) },
        { name: '淘汰', type: 'bar', stack: 'total', itemStyle: { color: '#ef4444' },
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
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 3 },
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
})

onUnmounted(() => {
  resizeHandler && window.removeEventListener('resize', resizeHandler)
  trendChart?.dispose()
  indexerChart?.dispose()
  failReasonChart?.dispose()
})
</script>

<style scoped lang="scss">
.pt-stats-dashboard {
  padding: 24px;
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

  &:hover {
    transform: translateY(-2px);
    box-shadow: var(--osr-shadow-md);
  }

  :deep(.el-card__body) {
    display: flex;
    align-items: center;
    padding: 20px;
    gap: 16px;
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

  :deep(.el-card__header) {
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

@media (max-width: 768px) {
  .pt-stats-dashboard {
    padding: 16px;
  }

  .stat-card :deep(.el-card__body) {
    padding: 16px;
  }

  .echarts-container {
    height: 220px !important;
  }
}
</style>
