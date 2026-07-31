<template>
  <div class="dashboard">
    <!-- Stat Cards - 3 per row on desktop -->
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

    <!-- Charts -->
    <v-row class="chart-row">
      <v-col cols="12" md="4" v-for="(chart, index) in chartData" :key="index">
        <v-card class="chart-card">
          <div class="chart-header">
            <span class="chart-title">{{ chart.title }}</span>
            <v-select
              v-model="chart.range"
              :items="[{ title: '今日', value: 'today' }, { title: '昨日', value: 'yesterday' }, { title: '全部', value: 'all' }]"
              density="compact"
              variant="outlined"
              hide-details
              style="width: 100px"
              @update:model-value="chart.load()"
            />
          </div>
          <div :ref="el => setChartContainer(el, index)" class="echarts-container" />
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
// 按需引入：仪表盘只用到饼图，避免全量引入 echarts 拖大打包体积
import * as echarts from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getDashboardStatsApi, getCopyStatsApi, getStrmStatsApi, getRenameStatsApi } from '@/api/openlist/dashboard'

echarts.use([PieChart, TitleComponent, TooltipComponent, CanvasRenderer])
import type { Ref } from 'vue'

interface StatCard {
  label: string
  value: number | string
  icon: string
  type: 'primary' | 'success' | 'warning' | 'info'
}

interface ChartData {
  title: string
  range: string
  load: () => Promise<void>
  chart: any
}

const statCards = ref<StatCard[]>([])
const copyChart = ref<any>(null)
const strmChart = ref<any>(null)
const renameChart = ref<any>(null)

// Store container refs by index
const chartContainers: Ref<HTMLElement | null>[] = [ref(null), ref(null), ref(null)]

function setChartContainer(el: unknown, index: number) {
  if (el instanceof HTMLElement) {
    chartContainers[index].value = el
  }
}

const chartData = ref<ChartData[]>([])

let resizeHandler: (() => void) | null = null

const colorMap: Record<string, string> = {
  '成功': '#22c55e',
  '失败': '#ef4444',
  '未知': '#f59e0b',
  '处理中': '#0d9488'
}

const defaultColors = ['#0d9488', '#22c55e', '#f59e0b', '#ef4444', '#6366f1', '#8b5cf6', '#ec4899', '#14b8a6']

function getColor(name: string): string {
  if (colorMap[name]) return colorMap[name]
  const idx = Object.keys(colorMap).findIndex(k => name.includes(k))
  return idx >= 0 ? colorMap[Object.keys(colorMap)[idx]] : defaultColors[Object.keys(colorMap).length + idx % defaultColors.length]
}

function renderChart(chart: any, data: Record<string, number>, range: string) {
  const rangeTextMap: Record<string, string> = { today: '今日数据', yesterday: '昨日数据', all: '全部数据' }
  const title = rangeTextMap[range] || '全部数据'

  const chartData = Object.entries(data).map(([name, value]) => ({
    value,
    name,
    itemStyle: { color: getColor(name) }
  }))

  if (chartData.length > 0) {
    chart.setOption({
      title: { text: title, left: 'center', top: 'center', textStyle: { fontSize: 13, fontWeight: 'normal', color: '#64748b' } },
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      series: [{
        type: 'pie',
        radius: ['35%', '65%'],
        center: ['50%', '55%'],
        avoidLabelOverlap: false,
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 3 },
        label: { show: true, formatter: '{b}\n{c}', fontSize: 11 },
        emphasis: { label: { show: true, fontSize: 13, fontWeight: 'bold' } },
        labelLine: { length: 15, length2: 10 },
        minAngle: 5,
        data: chartData
      }]
    }, true)
  } else {
    chart.clear()
    chart.setOption({
      title: { text: '暂无数据', left: 'center', top: 'center', textStyle: { fontSize: 14, color: '#94a3b8' } },
      series: []
    }, true)
  }
}

const loadCopyChart = async () => {
  const range = chartData.value[0].range
  const container = chartContainers[0].value
  if (!container) {
    console.warn('copyChart container not found')
    return
  }
  if (!copyChart.value) {
    copyChart.value = echarts.init(container)
  }
  try {
    const data: any = await getCopyStatsApi(range)
    if (copyChart.value) renderChart(copyChart.value, data || {}, range)
  } catch (e) {
    console.error('Failed to load copy stats:', e)
    if (copyChart.value) renderChart(copyChart.value, {}, range)
  }
}

const loadStrmChart = async () => {
  const range = chartData.value[1].range
  const container = chartContainers[1].value
  if (!container) {
    console.warn('strmChart container not found')
    return
  }
  if (!strmChart.value) {
    strmChart.value = echarts.init(container)
  }
  try {
    const data: any = await getStrmStatsApi(range)
    if (strmChart.value) renderChart(strmChart.value, data || {}, range)
  } catch (e) {
    console.error('Failed to load strm stats:', e)
    if (strmChart.value) renderChart(strmChart.value, {}, range)
  }
}

const loadRenameChart = async () => {
  const range = chartData.value[2].range
  const container = chartContainers[2].value
  if (!container) {
    console.warn('renameChart container not found')
    return
  }
  if (!renameChart.value) {
    renameChart.value = echarts.init(container)
  }
  try {
    const data: any = await getRenameStatsApi(range)
    if (renameChart.value) renderChart(renameChart.value, data || {}, range)
  } catch (e) {
    console.error('Failed to load rename stats:', e)
    if (renameChart.value) renderChart(renameChart.value, {}, range)
  }
}

onMounted(async () => {
  // Load dashboard stats from API
  try {
    const statsData: any = await getDashboardStatsApi()
    const copyCount = statsData?.copyRecordCount ?? 0
    const strmCount = statsData?.strmRecordCount ?? 0
    const renameCount = statsData?.renameDetailCount ?? 0
    const successRate = statsData?.successRate ?? 0
    const failedCount = statsData?.failedCount ?? 0
    const processingCount = statsData?.processingCount ?? 0
    statCards.value = [
      { label: 'COPY 任务', value: copyCount, icon: 'mdi-file-multiple-outline', type: 'primary' },
      { label: 'STRM 任务', value: strmCount, icon: 'mdi-video-outline', type: 'success' },
      { label: 'Rename 任务', value: renameCount, icon: 'mdi-pencil-outline', type: 'warning' },
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

  // Set chartData
  chartData.value = [
    { title: 'COPY 任务', range: 'today', load: loadCopyChart, chart: null },
    { title: 'STRM 任务', range: 'today', load: loadStrmChart, chart: null },
    { title: 'Rename 任务', range: 'today', load: loadRenameChart, chart: null }
  ]

  // Wait for DOM to render
  await nextTick()

  // Load charts in parallel (previously serial awaits, tripling first-paint latency)
  await Promise.all([loadCopyChart(), loadStrmChart(), loadRenameChart()])

  resizeHandler = () => {
    copyChart.value?.resize()
    strmChart.value?.resize()
    renameChart.value?.resize()
  }
  window.addEventListener('resize', resizeHandler)
})

onUnmounted(() => {
  resizeHandler && window.removeEventListener('resize', resizeHandler)
  copyChart.value?.dispose()
  strmChart.value?.dispose()
  renameChart.value?.dispose()
})
</script>

<style scoped lang="scss">
.dashboard {
  padding: 24px;
}

/* ============================================
   Stat Cards
   ============================================ */
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

  display: flex;
  align-items: center;
  padding: 20px;
  gap: 16px;

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

  /* Color variants */
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
   Chart Cards
   ============================================ */
.chart-row {
  margin-bottom: -16px;
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
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--osr-border-light);
  background-color: var(--osr-surface);

  .chart-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--osr-text-primary);
  }
}

.echarts-container {
  height: 260px;
  width: 100%;
}

/* ============================================
   Responsive
   ============================================ */
@media (max-width: 768px) {
  .dashboard {
    padding: 16px;
  }

  .stat-card {
    padding: 16px;
  }

  .stat-icon {
    width: 44px !important;
    height: 44px !important;
  }

  .stat-icon .v-icon {
    font-size: 22px !important;
  }

  .stat-value {
    font-size: 20px !important;
  }

  .echarts-container {
    height: 220px !important;
  }
}
</style>
