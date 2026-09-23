<template>
  <div class="mobile-pt-stats">
    <div class="toolbar">
      <span class="toolbar-label">统计范围</span>
      <v-btn-toggle v-model="rangeDays" color="primary" density="compact" variant="outlined" mandatory @update:model-value="onRangeChange">
        <v-btn v-for="r in PT_STATS_RANGES" :key="r.value" :value="r.value" size="small">{{ r.label }}</v-btn>
      </v-btn-toggle>
      <v-btn prepend-icon="refresh-cw" size="small" variant="outlined" :loading="refreshing" @click="loadAll">刷新</v-btn>
      <span v-if="lastLoadedAt" class="toolbar-stamp">数据截止 {{ lastLoadedAt }}</span>
    </div>

    <!-- 取数失败要明说，不能拿一排 0 冒充「系统很干净」（同 PC 端与首页） -->
    <v-alert v-if="anyFailed && !loading" type="error" variant="tonal" density="compact" class="mb-3">
      部分统计数据加载失败。
      <template #append>
        <v-btn size="small" variant="text" @click="loadAll">重试</v-btn>
      </template>
    </v-alert>

    <div class="stat-grid">
      <template v-if="loading">
        <v-skeleton-loader v-for="i in 6" :key="'sk-' + i" type="list-item-avatar" class="stat-skeleton" />
      </template>
      <template v-else>
        <v-card
          v-for="stat in statCards"
          :key="stat.key"
          class="stat-card"
          :class="[stat.type, { 'stat-card--link': isCardClickable(stat) }]"
          v-on="isCardClickable(stat) ? { click: () => openCard(stat) } : {}"
        >
          <div class="stat-icon">
            <v-icon :icon="stat.icon" size="22" />
          </div>
          <div class="stat-info">
            <div class="stat-value"><AnimatedNumber :value="stat.value" /></div>
            <div class="stat-label">{{ stat.label }}</div>
            <!-- 移动端没有悬停，口径说明直接写在卡片上 -->
            <div class="stat-hint">{{ stat.hint }}</div>
          </div>
        </v-card>
      </template>
    </div>

    <v-card class="chart-card">
      <div class="chart-header">
        <span class="chart-title">下载量趋势</span>
        <span class="chart-subtitle">推送按推送日、完成按完成日、失败按失败日统计；点折线上的点查看当天记录</span>
      </div>
      <div ref="trendContainer" class="echarts-container" />
    </v-card>

    <v-card class="chart-card">
      <div class="chart-header">
        <span class="chart-title">索引器命中率</span>
        <span class="chart-subtitle">基于保留的匹配日志，不受时间范围影响</span>
      </div>
      <div ref="indexerContainer" class="echarts-container" />
      <div v-if="noDataIndexerNames.length" class="no-data-indexers">
        暂无数据：{{ noDataIndexerNames.join('、') }}
      </div>
    </v-card>

    <v-card class="chart-card">
      <div class="chart-header">
        <span class="chart-title">失败原因分布</span>
        <span class="chart-subtitle">按失败分类聚合，统计区间内失败的记录；点扇形查看明细</span>
      </div>
      <div ref="failReasonContainer" class="echarts-container" />
    </v-card>

    <v-card class="chart-card">
      <div class="chart-header">
        <span class="chart-title">搜索淘汰原因分布</span>
        <span class="chart-subtitle">候选在推送前被规则挡掉的分布，不受时间范围影响</span>
      </div>
      <div ref="rejectReasonContainer" class="echarts-container reject-container" />
    </v-card>

    <v-card class="chart-card">
      <div class="chart-header top-sub-header">
        <span class="chart-title">Top 活跃订阅</span>
        <v-btn-toggle v-model="topLimit" color="primary" density="compact" variant="outlined" mandatory @update:model-value="onTopLimitChange">
          <v-btn v-for="n in PT_STATS_TOP_LIMITS" :key="n" :value="n" size="x-small">{{ n }}</v-btn>
        </v-btn-toggle>
      </div>
      <div>
        <v-progress-linear v-if="topSubscriptionsLoading" indeterminate color="primary" class="list-loading" />
        <div v-for="row in topSubscriptions" :key="row.subId ?? row.title" class="top-sub-item">
          <div class="top-sub-title">
            <router-link
              v-if="row.subId && subscriptionLocation(row.subId)"
              :to="subscriptionLocation(row.subId)!"
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
            <span>未解决失败 {{ row.failedCount }}</span>
            <router-link v-if="subscriptionRecordsLink(row)" :to="subscriptionRecordsLink(row)!" class="top-sub-link top-sub-records">下载记录</router-link>
          </div>
          <div class="top-sub-meta">
            <span>上次命中 {{ row.lastMatchTime || '-' }}</span>
          </div>
        </div>
        <v-empty-state v-if="!topSubscriptionsLoading && topSubscriptions.length === 0" icon="inbox" title="暂无数据" />
      </div>
    </v-card>
  </div>
</template>

<script setup lang="ts">
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

// 取数、图表选项、时间挡位语义全部来自共用的 usePtStats，与 PC 端同一份：
// 两端各写一份的结果是「搜索淘汰原因分布」曾经只有 PC 有
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

const trendContainer = ref<HTMLElement | null>(null)
const indexerContainer = ref<HTMLElement | null>(null)
const failReasonContainer = ref<HTMLElement | null>(null)
const rejectReasonContainer = ref<HTMLElement | null>(null)

// 统计卡、趋势点、失败扇形、Top 订阅都能下钻到对应明细（跳转口径与 PC 端共用）
const { openCard, isCardClickable, onTrendClick, onFailReasonClick, subscriptionRecordsLink, subscriptionLocation } =
  usePtStatsNavigation(rangeDays)

useEchart(trendContainer, trendOption, onTrendClick)
useEchart(indexerContainer, indexerOption)
useEchart(failReasonContainer, failReasonOption, onFailReasonClick)
useEchart(rejectReasonContainer, rejectReasonOption)

onMounted(loadAll)
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

    .stat-hint {
      font-size: 10px;
      line-height: 1.3;
      color: var(--osr-text-secondary);
      opacity: 0.8;
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

  .top-sub-records {
    margin-left: auto;
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

.toolbar-stamp {
  font-size: 11px;
  color: var(--osr-text-secondary);
}

.stat-skeleton {
  border-radius: var(--osr-radius-base);
}

.stat-card {
  &.error .stat-icon { background: var(--osr-error-light); color: var(--osr-error); }
}

/* 淘汰原因有十几类，按 PC 端那样 220px 会把标签挤成一团 */
.reject-container {
  height: 300px;
}

.top-sub-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
