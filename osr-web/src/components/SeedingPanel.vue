<template>
  <!-- 保种与上传量（统计仪表盘里的一块，PC 与移动端共用）。只有管理员看得到，非管理员整块不渲染 -->
  <v-card v-if="visible" class="chart-card">
    <v-card-title class="seeding-header">
      <span class="seeding-title">保种与上传</span>
      <span class="seeding-subtitle">数据直接来自下载器；每日上传量按下载器的累计计数器求差</span>
    </v-card-title>
    <v-card-text>
      <v-progress-linear v-if="loading && !data" indeterminate color="primary" class="mb-3" />
      <v-alert v-if="failed" type="error" variant="tonal" density="compact" class="mb-3">
        保种数据加载失败。
        <template #append>
          <v-btn size="small" variant="text" @click="load">重试</v-btn>
        </template>
      </v-alert>

      <div v-if="data" class="seeding-summary">
        <div class="seeding-total">
          <span class="seeding-total-value">{{ totals.seedingCount }}</span>
          <span class="seeding-total-label">保种中</span>
        </div>
        <div class="seeding-total">
          <span class="seeding-total-value">{{ formatSize(totals.seedingSize) }}</span>
          <span class="seeding-total-label">保种体积</span>
        </div>
        <div class="seeding-total">
          <span class="seeding-total-value">{{ formatSize(totals.uploadedInRange) }}</span>
          <span class="seeding-total-label">区间内上传</span>
        </div>
      </div>

      <div v-if="data?.downloaders.length" class="seeding-downloaders">
        <div v-for="d in data.downloaders" :key="d.id" class="seeding-downloader">
          <span class="seeding-downloader-name">{{ d.name }}</span>
          <span v-if="d.error" class="seeding-downloader-error" :title="d.error">连不上</span>
          <span v-else class="seeding-downloader-meta">
            {{ d.seedingCount }}/{{ d.torrentCount }} 个保种 · {{ formatSize(d.seedingSize) }} · 现存种子共上传 {{ formatSize(d.uploadedSum) }}
          </span>
        </div>
      </div>

      <div ref="chartEl" class="seeding-chart" />

      <div v-if="data" class="seeding-lists">
        <div class="seeding-list">
          <div class="seeding-list-title">上传最多</div>
          <div v-for="(t, i) in data.topUploaded" :key="'up-' + i" class="seeding-row">
            <span class="seeding-row-name" :title="t.name">{{ t.name }}</span>
            <span class="seeding-row-meta">{{ formatSize(t.uploaded) }} · 分享率 {{ t.ratio.toFixed(2) }}</span>
          </div>
          <div v-if="!data.topUploaded.length" class="seeding-empty">暂无</div>
        </div>
        <div class="seeding-list">
          <div class="seeding-list-title" title="做种满 7 天、分享率仍低于 0.2，按体积从大到小">低效保种</div>
          <div v-for="(t, i) in data.lowRatio" :key="'low-' + i" class="seeding-row">
            <span class="seeding-row-name" :title="t.name">{{ t.name }}</span>
            <span class="seeding-row-meta">{{ formatSize(t.size) }} · 做种 {{ t.seedingDays }} 天 · 分享率 {{ t.ratio.toFixed(2) }}</span>
          </div>
          <div v-if="!data.lowRatio.length" class="seeding-empty">没有做种满 7 天仍几乎没上传的种子</div>
        </div>
      </div>
    </v-card-text>
  </v-card>
</template>

<script setup lang="ts">
import { ref, toRef } from 'vue'
import { usePtSeeding } from '@/composables/usePtSeeding'
import { useEchart } from '@/composables/useEchart'
import { formatSize } from '@/composables/sizeUnits'

const props = defineProps<{
  /** 统计区间（天），跟随页面顶部的挡位 */
  days: number
}>()

const { data, loading, failed, visible, totals, trendOption, load } = usePtSeeding(toRef(props, 'days'))

const chartEl = ref<HTMLElement | null>(null)
useEchart(chartEl, trendOption)
</script>

<style scoped>
/* 外壳 .chart-card 用的是所在页面的样式（根元素带着父组件的 scope），标题区两端写法不同，这里自带一份 */
.seeding-header {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 12px;
}

.seeding-title {
  font-size: var(--osr-fs-md);
  font-weight: 600;
  color: var(--osr-text-primary);
}

.seeding-subtitle {
  font-size: var(--osr-fs-xs);
  color: var(--osr-text-secondary);
  white-space: normal;
}

.seeding-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  margin-bottom: 12px;
}

.seeding-total {
  display: flex;
  flex-direction: column;
}

.seeding-total-value {
  font-size: var(--osr-fs-xl);
  font-weight: 600;
  color: var(--osr-text-primary);
}

.seeding-total-label,
.seeding-downloader-meta,
.seeding-row-meta,
.seeding-empty {
  font-size: var(--osr-fs-xs);
  color: var(--osr-text-secondary);
}

.seeding-downloaders {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 8px;
}

.seeding-downloader {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
}

.seeding-downloader-name {
  font-weight: 600;
}

.seeding-downloader-error {
  font-size: var(--osr-fs-xs);
  color: rgb(var(--v-theme-error));
}

.seeding-chart {
  height: 280px;
  width: 100%;
}

.seeding-lists {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 16px;
  margin-top: 12px;
}

.seeding-list {
  /* grid 子项默认 min-width:auto，长种子名会把列撑宽、省略号失效 */
  min-width: 0;
}

.seeding-list-title {
  font-weight: 600;
  margin-bottom: 4px;
}

.seeding-row {
  display: flex;
  flex-direction: column;
  padding: 4px 0;
  border-bottom: 1px dashed var(--osr-border-light);
}

.seeding-row-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--osr-fs-sm);
}
</style>
