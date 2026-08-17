<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-stethoscope"
      title="缺集体检"
      :desc="`列出播出超过 ${report.overdueDays} 天仍未入库的集，并说明每一集「为什么还缺」`"
    >
      <template #actions>
        <v-btn
          v-if="autoSearchOffIds.length > 0"
          color="primary"
          variant="flat"
          prepend-icon="mdi-magnify-scan"
          :loading="acting"
          @click="handleEnableAutoSearch()"
        >
          为 {{ autoSearchOffIds.length }} 条订阅开启自动补搜
        </v-btn>
        <v-btn variant="outlined" prepend-icon="mdi-refresh" :loading="loading" @click="load">刷新</v-btn>
      </template>
    </PageHeader>

    <v-card class="table-card">
      <div class="health-toolbar">
        <div class="summary">
          <span class="summary-main">
            <strong>{{ report.subscriptionCount }}</strong> 部作品 ·
            <strong>{{ report.episodeCount }}</strong> 集
          </span>
          <span class="summary-hint">仅统计「订阅中」的订阅；未播出的集不参与</span>
        </div>

        <div class="bucket-tabs">
          <v-chip
            :variant="activeBucket === '' ? 'flat' : 'outlined'"
            :color="activeBucket === '' ? 'primary' : undefined"
            size="small"
            @click="setBucket('')"
          >
            全部 {{ report.episodeCount }}
          </v-chip>
          <v-chip
            v-for="tab in bucketTabs"
            :key="tab.key"
            :variant="activeBucket === tab.key ? 'flat' : 'outlined'"
            :color="tab.color === 'default' ? undefined : tab.color"
            size="small"
            :title="tab.hint"
            @click="setBucket(tab.key)"
          >
            <v-icon :icon="tab.icon" size="14" start />{{ tab.label }} {{ tab.count }}
          </v-chip>
        </div>
      </div>

      <v-progress-linear v-if="loading" indeterminate color="primary" />

      <div class="health-list">
        <v-card v-for="sub in subscriptions" :key="sub.subId" class="health-item" variant="outlined">
          <div class="item-main">
            <v-img
              v-if="sub.posterPath"
              :src="posterUrl(sub.posterPath)"
              width="46"
              height="66"
              cover
              class="poster"
            />
            <v-icon v-else class="poster-fallback" icon="mdi-television-classic" size="24" />

            <div class="item-body">
              <div class="item-title-row">
                <button type="button" class="item-title" @click="openSubscription(sub.subId)">
                  {{ sub.title }}
                </button>
                <span v-if="sub.mediaType !== 'MOVIE'" class="item-season">S{{ pad(sub.season) }}</span>
                <span v-if="sub.maxOverdueDays !== null" class="item-overdue">
                  已播出 {{ sub.maxOverdueDays }} 天
                </span>
              </div>

              <div class="chip-row">
                <v-chip
                  v-for="code in sub.diagnoses"
                  :key="code"
                  size="x-small"
                  variant="tonal"
                  :color="diagnosisMeta(code).color === 'default' ? undefined : diagnosisMeta(code).color"
                  :title="diagnosisMeta(code).advice"
                >
                  {{ diagnosisMeta(code).label }}
                </v-chip>
              </div>

              <div class="episode-row">
                <v-chip
                  v-for="ep in sub.episodes"
                  :key="ep.episode"
                  size="x-small"
                  variant="outlined"
                  :color="bucketMeta(ep.bucket).color === 'default' ? undefined : bucketMeta(ep.bucket).color"
                  :title="episodeTip(ep)"
                >
                  {{ sub.mediaType === 'MOVIE' ? '正片' : `E${pad(ep.episode)}` }}
                </v-chip>
              </div>

              <div class="item-meta">
                <span v-if="sub.rejectDetail">淘汰原因：{{ sub.rejectDetail }}</span>
                <span v-if="sub.missStreak > 0">连续落空 {{ sub.missStreak }} 轮</span>
                <span v-if="sub.lastSearchTime">上次补搜 {{ sub.lastSearchTime }}</span>
                <span v-else>从未补搜过</span>
              </div>
            </div>
          </div>

          <div class="item-actions">
            <v-btn
              v-if="!sub.autoSearch"
              size="small"
              color="primary"
              variant="flat"
              :loading="acting"
              @click="handleEnableAutoSearch([sub.subId])"
            >
              开启自动补搜
            </v-btn>
            <v-btn size="small" variant="outlined" :loading="acting" @click="handleSearchNow(sub.subId)">
              立即补搜
            </v-btn>
            <v-btn size="small" variant="text" @click="openSubscription(sub.subId)">查看订阅</v-btn>
          </div>
        </v-card>
      </div>

      <v-empty-state
        v-if="!loading && subscriptions.length === 0"
        icon="mdi-check-decagram-outline"
        title="没有发现缺集"
        :text="`所有「订阅中」的作品，播出超过 ${report.overdueDays} 天的集都已入库或正在处理中`"
      />
    </v-card>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import { usePtHealth, bucketMeta, diagnosisMeta, posterUrl } from '@/composables/usePtHealth'
import type { EpisodeHealthItem } from '@/api/openlist/ptHealth'

const {
  loading, acting, report, activeBucket, subscriptions, bucketTabs, autoSearchOffIds,
  load, handleEnableAutoSearch, handleSearchNow, openSubscription, setBucket
} = usePtHealth()

const pad = (n: number) => String(n ?? 0).padStart(2, '0')

/** 悬停在集号上时把「哪一档 + 播出日期 + 逾期天数」一次说清，不必点开订阅 */
const episodeTip = (ep: EpisodeHealthItem) => {
  const parts = [bucketMeta(ep.bucket).label]
  if (ep.airDate) parts.push(`播出 ${ep.airDate}`)
  if (ep.overdueDays !== null) parts.push(`已 ${ep.overdueDays} 天`)
  parts.push(diagnosisMeta(ep.diagnosis).advice)
  return parts.filter(Boolean).join(' · ')
}
</script>

<style scoped>
.health-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  flex-wrap: wrap;
}

.summary {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.summary-main {
  font-size: 14px;
  color: var(--osr-text-primary);
}

.summary-main strong {
  font-size: 18px;
  color: rgb(var(--v-theme-primary));
}

.summary-hint {
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.bucket-tabs {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.health-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 4px 16px 16px;
}

.health-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 14px;
  flex-wrap: wrap;
}

.item-main {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  flex: 1;
  min-width: 0;
}

.poster {
  flex: none;
  border-radius: 4px;
}

.poster-fallback {
  flex: none;
  width: 46px;
  height: 66px;
  color: var(--osr-text-secondary);
}

.item-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  flex: 1;
}

.item-title-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.item-title {
  padding: 0;
  border: none;
  background: none;
  font-size: 15px;
  font-weight: 600;
  color: var(--osr-text-primary);
  cursor: pointer;
}

.item-title:hover {
  color: var(--osr-primary-hover);
}

.item-season {
  font-size: 12px;
  color: var(--osr-text-secondary);
  font-variant-numeric: tabular-nums;
}

.item-overdue {
  font-size: 12px;
  color: rgb(var(--v-theme-error));
}

.chip-row,
.episode-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.episode-row {
  /* 一季上百集时不把整张卡撑到几屏高，超出的部分内部滚动 */
  max-height: 84px;
  overflow-y: auto;
}

.item-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.item-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}

@media (max-width: 768px) {
  .page-container {
    padding: 0;
  }
}
</style>
