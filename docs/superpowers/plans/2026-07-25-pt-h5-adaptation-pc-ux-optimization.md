# PT页面 H5适配 + PC交互优化 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 PT 订阅/下载记录/统计仪表盘三页补充 H5 移动端交互（批量操作、按钮收纳、Tab 图表），同时优化 PC 端卡片响应式与批量模式体验。

**架构：** H5 端复用现有 composables（`usePtSubscription`/`usePtDownloadRecord`）不改逻辑层，只在模板层加 batch-bar + 抽屉 + 排序。PT 统计仪表盘 H5 新建页面，用 ECharts + el-tabs 适配小屏。PC 端 6 项 CSS/模板微调，不涉及 composable 改动。

**技术栈：** Vue 3 + Element Plus + ECharts 5 + SCSS

---

### 任务 1：H5 ptSubscription — 批量选择 + batch-bar

**文件：**
- 修改：`openlist-web/src/views-mobile/ptSubscription/index.vue`

- [ ] **步骤 1：卡片加 `@click` 选中切换 + `.selected` class**

在模板中修改 `.sub-card`，加上点击事件和选中类：

```html
<div
  v-for="item in taskList"
  :key="item.id"
  class="sub-card"
  :class="{ selected: selectedIds.includes(item.id) }"
  @click="toggleSubSelect(item)"
>
```

同时在 `.sub-actions` 上加 `@click.stop` 防止操作按钮点击冒泡触发卡片选中：

```html
<div class="sub-actions" @click.stop>
```

- [ ] **步骤 2：模板底部加 batch-bar**

在 `</div>` (task-list 闭合标签) 之前、`<MobilePager` 之前加入：

```html
<!-- 批量操作 -->
<div class="batch-bar" v-if="selectedIds.length > 0">
  <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
  <el-button link type="warning" size="small" @click="handleBatchPause">批量暂停</el-button>
  <el-button link type="success" size="small" @click="handleBatchResume">批量恢复</el-button>
  <el-button link type="danger" size="small" @click="handleDelete()">批量删除</el-button>
  <el-button link size="small" @click="selectedIds.length = 0">取消</el-button>
</div>
```

- [ ] **步骤 3：加 `.batch-bar` 和 `.selected` 样式**

从 `ptDownloader` 的 mobile 页面抄 `.batch-bar` 样式，加 `.selected` 卡片选中样式：

```scss
.batch-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 14px;
  background: var(--osr-primary-light-9);
  border: 1px solid var(--osr-primary-light-7);
  border-radius: var(--osr-radius-md);
  font-size: 13px;

  .selected-count {
    font-weight: 600;
    color: var(--osr-primary);
    margin-right: 4px;
    white-space: nowrap;
  }

  .el-button {
    font-size: 12px;
    padding: 0 4px;
    height: auto;
  }
}

.sub-card {
  // ... 现有样式保持不变，追加：
  border: 2px solid transparent;
  transition: all var(--osr-transition-fast);

  &.selected {
    border-color: var(--osr-primary-light-5);
    background: var(--osr-primary-light-9);
  }

  &:active {
    transform: scale(0.99);
  }
}
```

注意：原来 `.sub-card` 样式里的 `border` 没有设置，需要把原先的 `border: none` 相关样式统一成 `border: 2px solid transparent`。检查原样式：原本 `.sub-card` 没有设置 `border` 属性（由 `background` + `border-radius` + `box-shadow` 构成卡片），所以直接追加 `border` + `&.selected`。

- [ ] **步骤 4：运行现有测试确认不破坏**

```bash
cd openlist-web && npx vitest run src/views/openlist/ptSubscription/__tests__/index.spec.ts
```

预期：全部 PASS（H5 页面改动不影响 PC 端测试）

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/views-mobile/ptSubscription/index.vue
git commit -m "feat(h5): ptSubscription 新增批量选择卡片交互与 batch-bar"
```

---

### 任务 2：H5 ptSubscription — 操作抽屉收纳

**文件：**
- 修改：`openlist-web/src/views-mobile/ptSubscription/index.vue`

- [ ] **步骤 1：简化卡片底部按钮为 2+「···」**

将 `.sub-actions` 区域从 9 个按钮改为：

```html
<div class="sub-actions" @click.stop>
  <el-button link type="primary" size="small" @click="showProgress(item)">进度</el-button>
  <el-button link type="primary" size="small" @click="goDownloadRecords(item)">下载记录</el-button>
  <el-button link type="info" size="small" @click="openActionDrawer(item)">···</el-button>
</div>
```

- [ ] **步骤 2：添加 el-drawer 操作抽屉**

在模板底部（`</div>` 闭合 `.mobile-page` 之前）加入：

```html
<!-- 操作抽屉 -->
<el-drawer
  v-model="actionDrawerOpen"
  direction="btt"
  size="auto"
  :with-header="true"
  title="更多操作"
  append-to-body
  class="modern-drawer"
>
  <div class="drawer-actions" v-if="actionDrawerTarget">
    <el-button
      v-if="actionDrawerTarget.status !== 'PAUSED'"
      type="warning"
      @click="handlePause(actionDrawerTarget); actionDrawerOpen = false"
    >暂停</el-button>
    <el-button
      v-else
      type="success"
      @click="handleResume(actionDrawerTarget); actionDrawerOpen = false"
    >恢复</el-button>
    <el-button type="primary" @click="openSeasonSearch(actionDrawerTarget); actionDrawerOpen = false">搜索补齐</el-button>
    <el-button @click="handleRefresh(actionDrawerTarget); actionDrawerOpen = false">对账</el-button>
    <el-button @click="showSearchLogs(actionDrawerTarget); actionDrawerOpen = false">匹配日志</el-button>
    <el-button @click="openFilterOverride(actionDrawerTarget); actionDrawerOpen = false">过滤规则</el-button>
    <el-button type="danger" @click="handleRemove(actionDrawerTarget); actionDrawerOpen = false">删除</el-button>
  </div>
</el-drawer>
```

- [ ] **步骤 3：添加抽屉状态变量**

在 `<script setup>` 中加入：

```ts
/** 操作抽屉状态 */
const actionDrawerOpen = ref(false)
const actionDrawerTarget = ref<any>(null)

const openActionDrawer = (row: any) => {
  actionDrawerTarget.value = row
  actionDrawerOpen.value = true
}
```

需要在 import 中加 `ref`（已有）。

- [ ] **步骤 4：加抽屉样式**

```scss
.modern-drawer {
  :deep(.el-drawer__body) {
    padding: 16px;
  }
}

.drawer-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;

  .el-button {
    width: 100%;
    justify-content: center;
    height: 44px;
    font-size: 15px;
    border-radius: var(--osr-radius-md);
  }
}
```

- [ ] **步骤 5：运行 PC 端测试确认**

```bash
cd openlist-web && npx vitest run src/views/openlist/ptSubscription/__tests__/index.spec.ts
```

预期：全部 PASS

- [ ] **步骤 6：Commit**

```bash
git add openlist-web/src/views-mobile/ptSubscription/index.vue
git commit -m "feat(h5): ptSubscription 操作按钮收纳为底部抽屉"
```

---

### 任务 3：H5 ptSubscription — 排序选择器

**文件：**
- 修改：`openlist-web/src/views-mobile/ptSubscription/index.vue`

- [ ] **步骤 1：在搜索面板内加排序下拉**

在 `MobileSearchPanel` 的 `<el-form>` 内、最后一个 `<el-form-item>` 之后加入：

```html
<el-form-item label="排序" prop="sortBy">
  <el-select v-model="queryParams.sortBy" placeholder="排序" clearable style="width: 100%" @change="handleQuery">
    <el-option label="默认（最新创建）" value="" />
    <el-option label="上次命中时间" value="lastMatchTime" />
  </el-select>
</el-form-item>
```

- [ ] **步骤 2：确认 composable 已有 sortBy 支持**

`usePtSubscription` 中的 `PtSubscriptionQuery` 已定义了 `sortBy?: string`，`defaultQuery` 也包含 `sortBy: undefined`，无需改动 composable。

- [ ] **步骤 3：Commit**

```bash
git add openlist-web/src/views-mobile/ptSubscription/index.vue
git commit -m "feat(h5): ptSubscription 搜索面板新增排序下拉"
```

---

### 任务 4：H5 ptDownloadRecord — 批量选择 + batch-bar

**文件：**
- 修改：`openlist-web/src/views-mobile/ptDownloadRecord/index.vue`

- [ ] **步骤 1：卡片加点击选中（仅 FAILED）**

修改 `.task-card`：

```html
<div
  v-for="item in taskList"
  :key="item.id"
  class="task-card"
  :class="{
    selected: selectedIds.includes(item.id),
    'task-card--failed': item.state === 'FAILED'
  }"
  @click="item.state === 'FAILED' && toggleRecordSelect(item)"
>
```

- [ ] **步骤 2：加 batch-bar**

在 `task-list` 闭合之后、`MobilePager` 之前加入：

```html
<!-- 批量操作 -->
<div class="batch-bar" v-if="selectedIds.length > 0">
  <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
  <el-button link type="primary" size="small" @click="handleBatchRetry">批量重试</el-button>
  <el-button link size="small" @click="selectedIds.length = 0">取消</el-button>
</div>
```

- [ ] **步骤 3：加 `.batch-bar` 和 `.selected` 样式**

```scss
.batch-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 14px;
  background: var(--osr-primary-light-9);
  border: 1px solid var(--osr-primary-light-7);
  border-radius: var(--osr-radius-md);
  font-size: 13px;

  .selected-count {
    font-weight: 600;
    color: var(--osr-primary);
    margin-right: 4px;
    white-space: nowrap;
  }

  .el-button {
    font-size: 12px;
    padding: 0 4px;
    height: auto;
  }
}

.task-card {
  // 现有样式基础上追加：
  border: 2px solid transparent;
  transition: all var(--osr-transition-fast);

  &.selected {
    border-color: var(--osr-primary-light-5);
    background: var(--osr-primary-light-9);
  }

  &:active {
    transform: scale(0.99);
  }
}
```

- [ ] **步骤 4：运行 PC 端测试确认**

```bash
cd openlist-web && npx vitest run src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts
```

预期：全部 PASS

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/views-mobile/ptDownloadRecord/index.vue
git commit -m "feat(h5): ptDownloadRecord 新增批量选择与批量重试 batch-bar"
```

---

### 任务 5：H5 ptStatsDashboard — 新建移动端仪表盘页面

**文件：**
- 创建：`openlist-web/src/views-mobile/ptStatsDashboard/index.vue`

- [ ] **步骤 1：编写统计卡片横向滚动区 + Tab 切换布局**

```html
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
```

- [ ] **步骤 2：编写 script setup（ECharts 初始化 + API 调用）**

```html
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
```

- [ ] **步骤 3：编写 SCSS 样式**

```scss
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
```

- [ ] **步骤 4：验证构建通过**

```bash
cd openlist-web && npx vue-tsc --noEmit --project tsconfig.json 2>&1 | head -30
```

预期：无新增类型错误

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/views-mobile/ptStatsDashboard/index.vue
git commit -m "feat(h5): 新建 ptStatsDashboard 移动端仪表盘（ECharts + Tab）"
```

---

### 任务 6：路由 — ptStatsDashboard 添加 createDeviceView

**文件：**
- 修改：`openlist-web/src/router/index.ts`

- [ ] **步骤 1：修改 componentMap 中 ptStatsDashboard 的注册**

找到第 113 行：

```ts
'openlist/ptStatsDashboard/index': () => import('@/views/openlist/ptStatsDashboard/index.vue')
```

改为：

```ts
'openlist/ptStatsDashboard/index': createDeviceView(
  () => import('@/views/openlist/ptStatsDashboard/index.vue'),
  () => import('@/views-mobile/ptStatsDashboard/index.vue')
),
```

- [ ] **步骤 2：验证 TypeScript 编译**

```bash
cd openlist-web && npx vue-tsc --noEmit --project tsconfig.json 2>&1 | head -30
```

预期：无新增类型错误

- [ ] **步骤 3：Commit**

```bash
git add openlist-web/src/router/index.ts
git commit -m "feat(router): ptStatsDashboard 支持 PC/H5 双端按设备加载"
```

---

### 任务 7：PC ptSubscription — 交互优化（6 项）

**文件：**
- 修改：`openlist-web/src/views/openlist/ptSubscription/index.vue`

- [ ] **步骤 1：批量模式下卡片可点击选中**

在 `.sub-card` 上加 `@click` 和动态 class：

```html
<div
  v-for="item in taskList"
  :key="item.id"
  class="sub-card"
  :class="{ selectable: selectionMode }"
  @click="selectionMode && toggleSubSelect(item)"
>
```

同时在 `.sub-card-checkbox` 上需要加 `@click.stop` 防止冒泡（checkbox 已有独立的 change 处理）：

```html
<el-checkbox
  v-if="selectionMode"
  class="sub-card-checkbox"
  :model-value="isSubSelected(item.id)"
  @change="toggleSubSelect(item)"
  @click.stop
/>
```

.scss 中追加 `.selectable` 样式：

```scss
.sub-card {
  // ... 现有样式末尾追加：
  &.selectable {
    cursor: pointer;
    &:hover {
      border-color: var(--osr-primary-light-5);
    }
  }
}
```

- [ ] **步骤 2：操作栏自适应**

修改 `.action-bar` 样式：

```scss
.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;

  .action-left {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }

  .action-right {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
  }
}
```

- [ ] **步骤 3：排序下拉加标签**

在排序 `<el-select>` 前加 `<span class="sort-label">排序：</span>`：

```html
<div class="action-right">
  <span class="sort-label">排序：</span>
  <el-select ...>
```

加样式：

```scss
.sort-label {
  font-size: 13px;
  color: var(--osr-text-secondary);
  white-space: nowrap;
  line-height: 32px;
}
```

- [ ] **步骤 4：卡片网格宽度上限**

```scss
.card-grid {
  grid-template-columns: repeat(auto-fill, minmax(340px, 480px));
}
```

- [ ] **步骤 5：骨架屏数量动态计算**

模板中 `v-for="n in 6"` 改为 `v-for="n in skeletonCount"`。script 中添加：

```ts
import { ref, reactive, onMounted, onUnmounted } from 'vue'

const skeletonCount = ref(6)

function updateSkeletonCount() {
  const cardMinWidth = 340 + 14
  const containerWidth = window.innerWidth - 32 - 32
  skeletonCount.value = Math.max(3, Math.min(12, Math.floor(containerWidth / cardMinWidth)))
}

onMounted(() => {
  updateSkeletonCount()
  window.addEventListener('resize', updateSkeletonCount)
})

onUnmounted(() => {
  window.removeEventListener('resize', updateSkeletonCount)
})
```

- [ ] **步骤 6：批量工具栏 sticky**

```scss
.batch-toolbar {
  // 现有样式基础上追加：
  position: sticky;
  top: 0;
  z-index: 2;
}
```

- [ ] **步骤 7：运行现有测试**

```bash
cd openlist-web && npx vitest run src/views/openlist/ptSubscription/__tests__/index.spec.ts
```

预期：全部 PASS（骨架屏测试 `v-for="n in 6"` 变为 `n in skeletonCount`，初始值 ref(6) 应该仍让测试通过——需要确认）

> 注意：骨架屏测试 `expect(wrapper.findAll('.sub-card-skeleton').length).toBe(6)` 依赖 `skeletonCount` 初始值为 6，`ref(6)` 满足此条件。但骨架屏现在不在 template 的第一层 div 中计数，而是用 `v-for="n in skeletonCount"`。测试中 wrapper 仍能正确找到 `.sub-card-skeleton`。

- [ ] **步骤 8：Commit**

```bash
git add openlist-web/src/views/openlist/ptSubscription/index.vue
git commit -m "feat(pc): ptSubscription 交互优化 — 卡片点击选中、响应式操作栏、排序标签、网格上限、骨架屏动态计数、sticky批量栏"
```

---

### 任务 8：PC ptDownloadRecord — 交互优化（6 项）

**文件：**
- 修改：`openlist-web/src/views/openlist/ptDownloadRecord/index.vue`

- [ ] **步骤 1：批量模式下卡片可点击选中（仅 FAILED）**

修改 `.record-card`：

```html
<div
  v-for="item in taskList"
  :key="item.id"
  class="record-card"
  :class="{
    'record-card--failed': item.state === 'FAILED',
    selectable: selectionMode && item.state === 'FAILED'
  }"
  @click="selectionMode && item.state === 'FAILED' && toggleRecordSelect(item)"
>
```

checkbox 加 `@click.stop`：

```html
<el-checkbox
  v-if="selectionMode && item.state === 'FAILED'"
  class="record-card-checkbox"
  :model-value="selectedIds.includes(item.id)"
  @change="toggleRecordSelect(item)"
  @click.stop
/>
```

.scss 追加：

```scss
.record-card {
  &.selectable {
    cursor: pointer;
    &:hover {
      border-color: var(--osr-primary-light-5);
    }
  }
}
```

- [ ] **步骤 2：操作栏自适应**

```scss
.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
```

- [ ] **步骤 3：卡片网格宽度上限**

```scss
.card-grid {
  grid-template-columns: repeat(auto-fill, minmax(320px, 480px));
}
```

- [ ] **步骤 4：骨架屏数量动态计算**

跟任务 7 步骤 5 相同的方式，在 script 中加 `skeletonCount` ref + `onMounted`/`onUnmounted` resize listener。模板 `v-for="n in 6"` 改为 `v-for="n in skeletonCount"`。

```ts
import { ref, onMounted, onUnmounted } from 'vue'

const skeletonCount = ref(6)

function updateSkeletonCount() {
  const cardMinWidth = 320 + 14
  const containerWidth = window.innerWidth - 32 - 32
  skeletonCount.value = Math.max(3, Math.min(12, Math.floor(containerWidth / cardMinWidth)))
}

onMounted(() => {
  updateSkeletonCount()
  window.addEventListener('resize', updateSkeletonCount)
})

onUnmounted(() => {
  window.removeEventListener('resize', updateSkeletonCount)
})
```

- [ ] **步骤 5：批量工具栏 sticky**

```scss
.batch-toolbar {
  position: sticky;
  top: 0;
  z-index: 2;
}
```

- [ ] **步骤 6：运行现有测试**

```bash
cd openlist-web && npx vitest run src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts
```

预期：全部 PASS

- [ ] **步骤 7：Commit**

```bash
git add openlist-web/src/views/openlist/ptDownloadRecord/index.vue
git commit -m "feat(pc): ptDownloadRecord 交互优化 — 卡片点击选中、响应式操作栏、网格上限、骨架屏动态计数、sticky批量栏"
```

---

### 任务 9：更新 PC 端测试（适配新增的交互行为）

**文件：**
- 修改：`openlist-web/src/views/openlist/ptSubscription/__tests__/index.spec.ts`
- 修改：`openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`

- [ ] **步骤 1：ptSubscription 测试 — 新增批量模式卡片点击测试**

在 `describe('PtSubscription 批量操作', ...)` 块末尾追加测试用例：

```ts
it('批量模式下点击卡片调用 toggleSubSelect', async () => {
  const toggleSubSelect = vi.fn()
  ;(usePtSubscription as any).mockReturnValue(baseComposable({
    selectionMode: ref(true),
    taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }]),
    toggleSubSelect
  }))
  const wrapper = mount(PtSubscriptionPage, mountOptions)
  await wrapper.find('.sub-card').trigger('click')
  expect(toggleSubSelect).toHaveBeenCalled()
})

it('批量模式下卡片带有 selectable class', () => {
  ;(usePtSubscription as any).mockReturnValue(baseComposable({
    selectionMode: ref(true),
    taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
  }))
  const wrapper = mount(PtSubscriptionPage, mountOptions)
  expect(wrapper.find('.sub-card').classes()).toContain('selectable')
})

it('非批量模式下卡片不带 selectable class', () => {
  ;(usePtSubscription as any).mockReturnValue(baseComposable({
    selectionMode: ref(false),
    taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
  }))
  const wrapper = mount(PtSubscriptionPage, mountOptions)
  expect(wrapper.find('.sub-card').classes()).not.toContain('selectable')
})
```

- [ ] **步骤 2：ptSubscription 测试 — 骨架屏动态计数测试更新**

原测试 `expect(wrapper.findAll('.sub-card-skeleton').length).toBe(6)` 仍有 `skeletonCount = ref(6)` 保证通过。追加一个测试验证 skeletonCount 存在但不依赖屏幕宽度：

```ts
it('骨架屏数量根据页面宽度动态变化（至少 3 张）', () => {
  ;(usePtSubscription as any).mockReturnValue(baseComposable({
    taskList: ref([]),
    loading: ref(true)
  }))
  const wrapper = mount(PtSubscriptionPage, mountOptions)
  const count = wrapper.findAll('.sub-card-skeleton').length
  expect(count).toBeGreaterThanOrEqual(3)
  expect(count).toBeLessThanOrEqual(12)
})
```

- [ ] **步骤 3：ptDownloadRecord 测试 — 新增批量模式卡片点击测试**

```ts
it('批量模式下点击 FAILED 卡片调用 toggleRecordSelect', async () => {
  const toggleRecordSelect = vi.fn()
  ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
    selectionMode: ref(true),
    taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }]),
    toggleRecordSelect
  }))
  const wrapper = mount(PtDownloadRecordPage)
  await wrapper.find('.record-card').trigger('click')
  expect(toggleRecordSelect).toHaveBeenCalled()
})

it('批量模式下 FAILED 卡片带有 selectable class', () => {
  ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
    selectionMode: ref(true),
    taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }])
  }))
  const wrapper = mount(PtDownloadRecordPage)
  expect(wrapper.find('.record-card').classes()).toContain('selectable')
})

it('批量模式下非 FAILED 卡片不带 selectable class', () => {
  ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
    selectionMode: ref(true),
    taskList: ref([{ id: 1, title: 'A', state: 'COMPLETED' }])
  }))
  const wrapper = mount(PtDownloadRecordPage)
  expect(wrapper.find('.record-card').classes()).not.toContain('selectable')
})
```

- [ ] **步骤 4：ptDownloadRecord 测试 — 骨架屏动态计数测试更新**

```ts
it('骨架屏数量根据页面宽度动态变化（至少 3 张）', () => {
  ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
    taskList: ref([]),
    loading: ref(true)
  }))
  const wrapper = mount(PtDownloadRecordPage)
  const count = wrapper.findAll('.record-card-skeleton').length
  expect(count).toBeGreaterThanOrEqual(3)
  expect(count).toBeLessThanOrEqual(12)
})
```

- [ ] **步骤 5：运行所有测试**

```bash
cd openlist-web && npx vitest run src/views/openlist/ptSubscription/__tests__/index.spec.ts src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts
```

预期：全部 PASS

- [ ] **步骤 6：Commit**

```bash
git add openlist-web/src/views/openlist/ptSubscription/__tests__/index.spec.ts openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts
git commit -m "test: 新增 PC 端批量模式卡片点击与骨架屏动态计数测试"
```

---

### 任务 10：全量测试验证 + 最终检查

- [ ] **步骤 1：运行全部前端测试**

```bash
cd openlist-web && npx vitest run
```

预期：全部 PASS，无回归

- [ ] **步骤 2：TypeScript 类型检查**

```bash
cd openlist-web && npx vue-tsc --noEmit
```

预期：无类型错误

- [ ] **步骤 3：ESLint 检查**

```bash
cd openlist-web && npm run lint
```

预期：无新增 lint 错误

- [ ] **步骤 4：构建验证**

```bash
cd openlist-web && npm run build
```

预期：构建成功

- [ ] **步骤 5：最终 Commit**

```bash
git add -A
git commit -m "chore: PT 页面 H5 适配与 PC 交互优化完成"
```
