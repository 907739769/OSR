<template>
  <div class="pt-stats-dashboard">
    <PageHeader
      icon="chart-column"
      title="PT 统计仪表盘"
      desc="下载量趋势、索引器命中率与失败/淘汰原因分布"
    />

    <div class="toolbar">
      <span class="toolbar-label">统计范围</span>
      <v-btn-toggle v-model="rangeDays" color="primary" density="comfortable" variant="outlined" mandatory @update:model-value="onRangeChange">
        <v-btn v-for="r in PT_STATS_RANGES" :key="r.value" :value="r.value">{{ r.label }}</v-btn>
      </v-btn-toggle>
      <span v-if="lastLoadedAt" class="toolbar-stamp">数据截止 {{ lastLoadedAt }}</span>
      <v-btn prepend-icon="refresh-cw" variant="outlined" class="refresh-btn" :loading="refreshing" @click="loadAll">刷新</v-btn>
    </div>

    <!-- 取数失败要明说。退化成一排 0 会被读成「系统很干净」，与首页统计卡同一条规矩 -->
    <v-alert v-if="anyFailed && !loading" type="error" variant="tonal" density="compact" class="mb-3">
      部分统计数据加载失败，相关图表显示为「加载失败」。
      <template #append>
        <v-btn size="small" variant="text" @click="loadAll">重试</v-btn>
      </template>
    </v-alert>

    <v-row class="stat-row">
      <template v-if="loading">
        <v-col cols="12" sm="6" md="4" v-for="i in 6" :key="'sk-' + i">
          <!-- 与真卡片同一个 .stat-card 外壳，数据到了不跳版 -->
          <v-card class="stat-card osr-sheen osr-skeleton" :style="{ '--osr-i': i - 1 }" aria-hidden="true">
            <span class="osr-bone osr-bone--block stat-icon" />
            <div class="stat-info">
              <span class="osr-bone stat-skeleton-value" />
              <span class="osr-bone osr-bone--caption stat-skeleton-label" />
            </div>
          </v-card>
        </v-col>
      </template>
      <template v-else>
        <v-col cols="12" sm="6" md="4" v-for="(stat, index) in statCards" :key="stat.key">
          <v-card
            class="stat-card osr-enter"
            :class="[stat.type, { 'stat-card--link': isCardClickable(stat) }]"
            :style="{ '--osr-i': index }"
            :title="stat.hint"
            v-on="isCardClickable(stat) ? { click: () => openCard(stat) } : {}"
          >
            <div class="stat-icon">
              <v-icon :icon="stat.icon" size="28" />
            </div>
            <div class="stat-info">
              <div class="stat-value"><AnimatedNumber :value="stat.value" /></div>
              <div class="stat-label">{{ stat.label }}</div>
            </div>
          </v-card>
        </v-col>
      </template>
    </v-row>

    <v-row class="chart-row">
      <v-col cols="12">
        <v-card class="chart-card">
          <v-card-title class="chart-header">
            <span class="chart-title">下载量趋势</span>
            <span class="chart-subtitle">推送按推送日、完成与平均耗时按完成日、失败按失败日统计；点折线上的点查看当天记录</span>
          </v-card-title>
          <v-card-text>
            <div class="chart-wrap">
              <div ref="trendContainer" class="echarts-container trend-container" />
              <v-skeleton-loader v-if="loading" type="image" class="chart-skeleton" height="300" />
            </div>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <v-row class="chart-row">
      <v-col cols="12" md="6">
        <v-card class="chart-card">
          <v-card-title class="chart-header">
            <span class="chart-title">索引器命中率</span>
            <span class="chart-subtitle">基于保留的匹配日志，不受时间范围影响</span>
          </v-card-title>
          <v-card-text>
            <div class="chart-wrap">
              <div ref="indexerContainer" class="echarts-container" />
              <v-skeleton-loader v-if="loading" type="image" class="chart-skeleton" height="260" />
            </div>
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
            <span class="chart-subtitle">按失败分类聚合，统计区间内失败的记录；点扇形查看明细</span>
          </v-card-title>
          <v-card-text>
            <div class="chart-wrap">
              <div ref="failReasonContainer" class="echarts-container" />
              <v-skeleton-loader v-if="loading" type="image" class="chart-skeleton" height="260" />
            </div>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <v-row class="chart-row">
      <v-col cols="12">
        <v-card class="chart-card">
          <v-card-title class="chart-header">
            <span class="chart-title">搜索淘汰原因分布</span>
            <span class="chart-subtitle">候选在推送前被过滤规则挡掉的分布，不受时间范围影响；占比过高说明规则可能过严</span>
          </v-card-title>
          <v-card-text>
            <div class="chart-wrap">
              <div ref="rejectReasonContainer" class="echarts-container" />
              <v-skeleton-loader v-if="loading" type="image" class="chart-skeleton" height="260" />
            </div>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <v-row class="chart-row">
      <v-col cols="12">
        <v-card class="chart-card">
          <v-card-title class="chart-header">
            <span class="chart-title">Top 活跃订阅</span>
            <div class="chart-actions">
              <span class="chart-subtitle">按统计区间内的下载次数排行，点表头可换排序</span>
              <v-btn-toggle v-model="topLimit" color="primary" density="compact" variant="outlined" mandatory @update:model-value="onTopLimitChange">
                <v-btn v-for="n in PT_STATS_TOP_LIMITS" :key="n" :value="n" size="small">{{ n }}</v-btn>
              </v-btn-toggle>
            </div>
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
                v-if="subscriptionLocation(item.subId)"
                :to="subscriptionLocation(item.subId)!"
                class="stats-sub-link"
              >
                {{ item.title }}
              </router-link>
              <span v-else>{{ item.title }}</span>
            </template>
            <template #item.records="{ item }">
              <router-link v-if="subscriptionRecordsLink(item)" :to="subscriptionRecordsLink(item)!" class="stats-sub-link">
                下载记录
              </router-link>
            </template>
            <template #item.seasonType="{ item }">
              <span v-if="item.mediaType === 'MOVIE'">电影</span>
              <span v-else-if="item.season != null">S{{ item.season }}</span>
              <span v-else>-</span>
            </template>
            <template #item.lastMatchTime="{ item }">{{ item.lastMatchTime || '-' }}</template>
            <template #no-data>
              <v-empty-state icon="inbox" title="暂无数据" />
            </template>
          </v-data-table>
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import AnimatedNumber from '@/components/AnimatedNumber.vue'
import { onMounted, ref } from 'vue'
import { PT_STATS_RANGES, PT_STATS_TOP_LIMITS, usePtStats } from '@/composables/usePtStats'
import { useEchart } from '@/composables/useEchart'
import { usePtStatsNavigation } from '@/composables/usePtStatsNavigation'
// 按需引入：本页只用到 line/bar/pie，避免全量引入 echarts 拖大打包体积
import * as echarts from 'echarts/core'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, BarChart, PieChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer])

const {
  rangeDays,
  topLimit,
  loading,
  refreshing,
  anyFailed,
  lastLoadedAt,
  statCards,
  trendOption,
  indexerOption,
  failReasonOption,
  rejectReasonOption,
  noDataIndexerNames,
  topSubscriptions,
  topSubscriptionsLoading,
  loadAll,
  onRangeChange,
  onTopLimitChange
} = usePtStats()

// 合成列（季/类型）不是数据库字段，排序键传过去无处可落，标 sortable:false；
// 其余列由 v-data-table 在本页数据上客户端排序（本表只有 10 行，不走后端）
const topSubHeaders = [
  { title: '订阅标题', key: 'title', minWidth: '180' },
  { title: '季/类型', key: 'seasonType', width: '100', sortable: false },
  { title: '下载次数', key: 'downloadCount', width: '100' },
  { title: '完成数', key: 'completedCount', width: '100' },
  { title: '未解决失败', key: 'failedCount', width: '110' },
  { title: '上次命中时间', key: 'lastMatchTime', width: '180' },
  { title: '', key: 'records', width: '100', sortable: false }
]

const trendContainer = ref<HTMLElement | null>(null)
const indexerContainer = ref<HTMLElement | null>(null)
const failReasonContainer = ref<HTMLElement | null>(null)
const rejectReasonContainer = ref<HTMLElement | null>(null)

// 统计卡、趋势点、失败扇形、Top 订阅都能下钻到对应明细（跳转口径与移动端共用）
const { openCard, isCardClickable, onTrendClick, onFailReasonClick, subscriptionRecordsLink, subscriptionLocation } =
  usePtStatsNavigation(rangeDays)

useEchart(trendContainer, trendOption, onTrendClick)
useEchart(indexerContainer, indexerOption)
useEchart(failReasonContainer, failReasonOption, onFailReasonClick)
useEchart(rejectReasonContainer, rejectReasonOption)

onMounted(loadAll)
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

  .toolbar-stamp {
    font-size: 12px;
    color: var(--osr-text-secondary);
  }

  .refresh-btn {
    margin-left: auto;
  }
}

.stat-row {
  margin-bottom: 24px;
}

/* 统计卡骨架：数字与标签位同 .stat-value / .stat-label 的行高 */
.stat-skeleton-value {
  width: 64px;
  height: 28px;
}

.stat-skeleton-label {
  width: 80px;
  margin-top: 8px;
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

  &--link {
    cursor: pointer;
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
  &.error .stat-icon {
    background-color: var(--osr-error-light);
    color: var(--osr-error);
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
  gap: 12px;

  .chart-actions {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .chart-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--osr-text-primary);
    white-space: nowrap;
  }

  .chart-subtitle {
    font-size: 12px;
    color: var(--osr-text-secondary);
    text-align: right;
  }
}

/* 骨架屏盖在图表容器上而不是替换它：容器要一直在 DOM 里，
   否则 ECharts 挂载时拿到的是 0 宽高，数据到达后画出一张空图 */
.chart-wrap {
  position: relative;
}

.chart-skeleton {
  position: absolute;
  inset: 0;
  border-radius: var(--osr-radius-md);
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
