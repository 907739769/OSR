<template>
  <v-card class="pt-card">
    <div class="chart-header">
      <span class="chart-title">PT 订阅概览</span>
      <v-btn variant="text" size="small" color="primary" @click="goPtStats">查看详情</v-btn>
    </div>
    <div class="pt-overview-grid">
      <div v-for="item in ptOverviewItems" :key="item.label" class="pt-overview-item" :class="item.type">
        <span class="pt-overview-dot" />
        <div class="pt-overview-value">{{ formatPtValue(item) }}</div>
        <div class="pt-overview-label">{{ item.label }}</div>
      </div>
    </div>
    <v-divider class="my-2" />
    <div class="pt-top-list">
      <div class="pt-top-title">热门订阅 Top {{ ptTopSubscriptions.length }}</div>
      <div v-if="!ptTopSubscriptions.length" class="pt-top-empty">暂无数据</div>
      <div v-for="sub in ptTopSubscriptions" :key="sub.subId" class="pt-top-item">
        <div class="pt-top-main">
          <span class="pt-top-name" :title="sub.title">{{ sub.title }}</span>
          <div class="pt-top-progress">
            <div class="pt-top-progress-bar" :style="{ width: ptProgressWidth(sub) + '%' }" />
          </div>
        </div>
        <span class="pt-top-count">{{ sub.completedCount }}/{{ sub.downloadCount }}</span>
      </div>
    </div>
  </v-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getRoutePathForComponent } from '@/router'
import { getPtStatsOverviewApi, getPtStatsTopSubscriptionsApi } from '@/api/openlist/ptStats'
import type { PtStatsOverview, PtStatsActiveSubscription } from '@/api/openlist/ptStats'

/**
 * 首页的「PT 订阅概览」卡片：四格概览 + 热门订阅 Top N。
 *
 * 自己取自己的数：它的两个接口与首页其它区块没有任何共享状态，留在 desktop.vue 里
 * 只是让那个文件多背 60 行模板加 40 行脚本。
 */
const router = useRouter()

const ptOverview = ref<Partial<PtStatsOverview>>({})
const ptTopSubscriptions = ref<PtStatsActiveSubscription[]>([])

/** PT 概览四格：key 对应 PtStatsOverview 字段，type 决定色点 */
const ptOverviewItems = [
  { key: 'totalSubscriptions', label: '订阅总数', type: 'primary', suffix: '' },
  { key: 'activeSubscriptions', label: '活跃订阅', type: 'success', suffix: '' },
  { key: 'successRate', label: '下载成功率', type: 'warning', suffix: '%' },
  { key: 'avgDurationMinutes', label: '平均耗时', type: 'info', suffix: '分' }
] as const

function formatPtValue(item: { key: string; suffix: string }): string {
  const v = (ptOverview.value as any)?.[item.key]
  if (v == null) return '--'
  return item.suffix ? `${v}${item.suffix}` : String(v)
}

function ptProgressWidth(sub: PtStatsActiveSubscription): number {
  if (!sub.downloadCount) return 0
  return Math.min(100, Math.round((sub.completedCount / sub.downloadCount) * 100))
}

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

onMounted(loadPtOverview)
</script>

<style scoped lang="scss">
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
  position: relative;

  .pt-overview-dot {
    position: absolute;
    top: 2px;
    left: 50%;
    transform: translateX(-50%);
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--osr-primary);
    opacity: 0.85;
  }

  &.success .pt-overview-dot { background: var(--osr-success); }
  &.warning .pt-overview-dot { background: var(--osr-warning); }
  &.info .pt-overview-dot { background: var(--osr-info); }

  .pt-overview-value {
    font-size: 20px;
    font-weight: 700;
    color: var(--osr-text-primary);
    margin-top: 6px;
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
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px dashed var(--osr-border-light);

  &:last-child {
    border-bottom: none;
  }

  .pt-top-main {
    flex: 1;
    min-width: 0;
  }

  .pt-top-name {
    display: block;
    color: var(--osr-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    margin-bottom: 3px;
  }

  .pt-top-progress {
    height: 4px;
    border-radius: 2px;
    background: var(--osr-primary-subtle);
    overflow: hidden;

    .pt-top-progress-bar {
      height: 100%;
      border-radius: 2px;
      background: linear-gradient(90deg, var(--osr-primary-accent), var(--osr-primary));
      transition: width var(--osr-transition-base);
    }
  }

  .pt-top-count {
    color: var(--osr-text-secondary);
    flex-shrink: 0;
    font-size: 12px;
  }
}
</style>
