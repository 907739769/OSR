<template>
  <div class="mobile-page">
    <!-- 统计卡片 横向滚动 -->
    <div class="stat-scroll" v-if="statCards.length">
      <div v-for="card in statCards" :key="card.label" class="stat-mini-card" :class="card.type">
        <el-icon :size="22"><component :is="card.icon" /></el-icon>
        <span class="stat-mini-value">{{ card.value }}</span>
        <span class="stat-mini-label">{{ card.label }}</span>
      </div>
    </div>

    <!-- 时间范围 + 刷新 -->
    <div class="toolbar-row">
      <el-radio-group v-model="rangeDays" size="small" @change="onRangeChange">
        <el-radio-button :label="7">7天</el-radio-button>
        <el-radio-button :label="30">30天</el-radio-button>
        <el-radio-button :label="90">90天</el-radio-button>
      </el-radio-group>
      <el-button :icon="Refresh" size="small" class="refresh-btn" @click="loadAll">刷新</el-button>
    </div>

    <!-- Tab 切换 -->
    <el-tabs v-model="activeTab" class="stats-tabs">
      <el-tab-pane label="下载趋势" name="trend">
        <div ref="trendContainer" class="echarts-container" />
      </el-tab-pane>
      <el-tab-pane label="索引器命中率" name="indexer">
        <div ref="indexerContainer" class="echarts-container" />
        <div v-if="noDataIndexerNames.length" class="no-data-indexers">
          暂无数据：{{ noDataIndexerNames.join('、') }}
        </div>
      </el-tab-pane>
      <el-tab-pane label="失败原因" name="failReason">
        <div ref="failReasonContainer" class="echarts-container" />
      </el-tab-pane>
      <el-tab-pane label="Top活跃订阅" name="topSubs">
        <div class="top-sub-list" v-loading="topSubscriptionsLoading">
          <div v-for="sub in topSubscriptions" :key="sub.subId" class="top-sub-card">
            <div class="top-sub-title">{{ sub.title }}</div>
            <div class="top-sub-meta">
              <span v-if="sub.mediaType === 'MOVIE'">电影</span>
              <span v-else-if="sub.season != null">S{{ sub.season }}</span>
              <span v-else>-</span>
              <span>下载 {{ sub.downloadCount }}</span>
              <span>完成 {{ sub.completedCount }}</span>
              <span>失败 {{ sub.failedCount }}</span>
            </div>
            <div class="top-sub-time">上次命中：{{ sub.lastMatchTime || '-' }}</div>
          </div>
          <el-empty v-if="!topSubscriptionsLoading && !topSubscriptions.length" description="暂无数据" />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { Document, Connection, Download, CircleCheck, Clock, Refresh } from '@element-plus/icons-vue'
import {
  getPtStatsOverviewApi, getPtStatsTrendApi, getPtStatsIndexerHitRateApi,
  getPtStatsFailReasonsApi, getPtStatsTopSubscriptionsApi,
  type PtStatsActiveSubscription
} from '@/api/openlist/ptStats'
import type { Component } from 'vue'

echarts.use([LineChart, BarChart, PieChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer])

interface StatCard {
  label: string; value: number | string; icon: Component; type: 'primary' | 'success' | 'warning' | 'info'
}

const rangeDays = ref(30)
const activeTab = ref('trend')
const statCards = ref<StatCard[]>([])
const topSubscriptions = ref<PtStatsActiveSubscription[]>([])
const topSubscriptionsLoading = ref(false)
const noDataIndexerNames = ref<string[]>([])

const trendContainer = ref<HTMLElement | null>(null)
const indexerContainer = ref<HTMLElement | null>(null)
const failReasonContainer = ref<HTMLElement | null>(null)

let trendChart: any = null, indexerChart: any = null, failReasonChart: any = null
let resizeHandler: (() => void) | null = null

const defaultColors = ['#0d9488', '#22c55e', '#f59e0b', '#ef4444', '#6366f1', '#8b5cf6', '#ec4899', '#14b8a6']

// 失败原因配色逻辑 —— 照抄 PC 端 getFailReasonColor
const failReasonColorMap: Record<string, string> = { '成功': '#22c55e', '失败': '#ef4444', '未知': '#f59e0b', '处理中': '#0d9488' }
function getFailReasonColor(name: string): string {
  if (failReasonColorMap[name]) return failReasonColorMap[name]
  const idx = Object.keys(failReasonColorMap).findIndex(k => name.includes(k))
  return idx >= 0 ? failReasonColorMap[Object.keys(failReasonColorMap)[idx]] : defaultColors[idx % defaultColors.length]
}

function emptyOption(text: string) {
  return { title: { text, left: 'center', top: 'center', textStyle: { fontSize: 14, color: '#94a3b8' } }, series: [] }
}

async function loadOverview() {
  try {
    const data = await getPtStatsOverviewApi()
    statCards.value = [
      { label: '总订阅', value: data.totalSubscriptions, icon: Document, type: 'primary' },
      { label: '活跃订阅', value: data.activeSubscriptions, icon: Connection, type: 'success' },
      { label: '记录总数', value: data.totalDownloadRecords, icon: Download, type: 'info' },
      { label: '成功率', value: data.totalDownloadRecords > 0 ? data.successRate + '%' : '--', icon: CircleCheck, type: 'success' },
      { label: '平均耗时', value: data.avgDurationMinutes > 0 ? Math.round(data.avgDurationMinutes) + '分' : '--', icon: Clock, type: 'warning' }
    ]
  } catch (e) {
    console.error('[PtStatsH5] overview:', e)
  }
}

async function loadTrend() {
  if (!trendContainer.value) return
  if (!trendChart) trendChart = echarts.init(trendContainer.value)
  try {
    const data = await getPtStatsTrendApi(rangeDays.value)
    if (!data?.length) { trendChart.clear(); trendChart.setOption(emptyOption('暂无数据'), true); return }
    trendChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: ['推送', '完成', '失败'], top: 0, textStyle: { fontSize: 11 } },
      grid: { left: 40, right: 20, top: 40, bottom: 30 },
      xAxis: { type: 'category', data: data.map(p => p.date), axisLabel: { rotate: 45, fontSize: 10 } },
      yAxis: { type: 'value' },
      series: [
        { name: '推送', type: 'line', data: data.map(p => p.pushedCount), itemStyle: { color: '#0d9488' } },
        { name: '完成', type: 'line', data: data.map(p => p.completedCount), itemStyle: { color: '#22c55e' } },
        { name: '失败', type: 'line', data: data.map(p => p.failedCount), itemStyle: { color: '#ef4444' } }
      ]
    }, true)
  } catch (e) { console.error('[PtStatsH5] trend:', e) }
}

async function loadIndexerHitRate() {
  if (!indexerContainer.value) return
  if (!indexerChart) indexerChart = echarts.init(indexerContainer.value)
  try {
    const data = await getPtStatsIndexerHitRateApi()
    noDataIndexerNames.value = (data || []).filter(i => !i.hasData).map(i => i.indexerName)
    const withData = (data || []).filter(i => i.hasData)
    if (!withData.length) { indexerChart.clear(); indexerChart.setOption(emptyOption('暂无数据'), true); return }
    indexerChart.setOption({
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      legend: { data: ['通过', '淘汰'], top: 0, textStyle: { fontSize: 11 } },
      grid: { left: 80, right: 20, top: 40, bottom: 20 },
      xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
      yAxis: { type: 'category', data: withData.map(i => i.indexerName.length > 10 ? i.indexerName.slice(0, 9) + '…' : i.indexerName) },
      series: [
        { name: '通过', type: 'bar', stack: 'total', itemStyle: { color: '#22c55e' }, data: withData.map(i => Math.round(i.hitRate * 1000) / 10) },
        { name: '淘汰', type: 'bar', stack: 'total', itemStyle: { color: '#ef4444' }, data: withData.map(i => Math.round((1 - i.hitRate) * 1000) / 10) }
      ]
    }, true)
  } catch (e) { console.error('[PtStatsH5] indexer:', e) }
}

async function loadFailReasons() {
  if (!failReasonContainer.value) return
  if (!failReasonChart) failReasonChart = echarts.init(failReasonContainer.value)
  try {
    const data = await getPtStatsFailReasonsApi(rangeDays.value)
    if (!data?.length) { failReasonChart.clear(); failReasonChart.setOption(emptyOption('暂无数据'), true); return }
    failReasonChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      series: [{
        type: 'pie', radius: ['35%', '65%'], center: ['50%', '55%'],
        avoidLabelOverlap: false, itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 3 },
        label: { show: true, formatter: '{b}\n{c}', fontSize: 10 },
        labelLine: { length: 15, length2: 10 }, minAngle: 5,
        data: data.map(item => ({ value: item.count, name: item.reason, itemStyle: { color: getFailReasonColor(item.reason) } }))
      }]
    }, true)
  } catch (e) { console.error('[PtStatsH5] failReasons:', e) }
}

async function loadTopSubscriptions() {
  topSubscriptionsLoading.value = true
  try { topSubscriptions.value = (await getPtStatsTopSubscriptionsApi(rangeDays.value, 10)) || [] }
  catch (e) { console.error('[PtStatsH5] topSubs:', e) }
  finally { topSubscriptionsLoading.value = false }
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
  resizeHandler = () => { trendChart?.resize(); indexerChart?.resize(); failReasonChart?.resize() }
  window.addEventListener('resize', resizeHandler)
})

onUnmounted(() => {
  resizeHandler && window.removeEventListener('resize', resizeHandler)
  trendChart?.dispose(); indexerChart?.dispose(); failReasonChart?.dispose()
})
</script>

<style lang="scss" scoped>
.mobile-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-bottom: 8px;
  min-height: calc(100vh - 120px);
}

.stat-scroll {
  display: flex;
  gap: 10px;
  overflow-x: auto;
  padding: 2px 0;
  -webkit-overflow-scrolling: touch;
  &::-webkit-scrollbar { display: none; }
}

.stat-mini-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  width: 130px;
  padding: 14px 10px;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);

  .stat-mini-value { font-size: 20px; font-weight: 700; color: var(--osr-text-primary); line-height: 1.2; }
  .stat-mini-label { font-size: 11px; color: var(--osr-text-secondary); }

  &.primary { background: var(--osr-primary-light-9); .el-icon { color: var(--osr-primary); } }
  &.success { background: var(--osr-success-light); .el-icon { color: var(--osr-success); } }
  &.warning { background: var(--osr-warning-light); .el-icon { color: var(--osr-warning); } }
  &.info { background: var(--osr-info-light); .el-icon { color: var(--osr-info); } }
}

.toolbar-row {
  display: flex;
  align-items: center;
  gap: 8px;

  .refresh-btn { margin-left: auto; }
}

.stats-tabs {
  flex: 1;

  :deep(.el-tabs__header) { margin-bottom: 8px; }
  :deep(.el-tabs__nav-wrap::after) { height: 1px; }
}

.echarts-container {
  height: 280px;
  width: 100%;
}

.no-data-indexers {
  margin-top: 6px;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.top-sub-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 120px;
}

.top-sub-card {
  padding: 12px;
  background: var(--osr-surface);
  border-radius: var(--osr-radius-md);
  box-shadow: var(--osr-shadow-base);

  .top-sub-title {
    font-size: 14px;
    font-weight: 600;
    color: var(--osr-text-primary);
    margin-bottom: 6px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .top-sub-meta {
    display: flex;
    gap: 10px;
    font-size: 12px;
    color: var(--osr-text-secondary);
    margin-bottom: 4px;
  }

  .top-sub-time {
    font-size: 11px;
    color: var(--osr-text-secondary);
  }
}
</style>
