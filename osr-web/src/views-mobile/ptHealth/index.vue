<template>
  <div class="mobile-page">
    <div class="health-bar">
      <div class="summary">
        <strong>{{ report.subscriptionCount }}</strong> 部 ·
        <strong>{{ report.episodeCount }}</strong> 集缺着
      </div>
      <v-btn icon="mdi-refresh" variant="text" density="comfortable" :loading="loading" @click="load" />
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
        @click="setBucket(tab.key)"
      >
        {{ tab.label }} {{ tab.count }}
      </v-chip>
    </div>

    <v-btn
      v-if="autoSearchOffIds.length > 0"
      block
      color="primary"
      variant="flat"
      class="enable-all-btn"
      prepend-icon="mdi-magnify-scan"
      :loading="acting"
      @click="handleEnableAutoSearch()"
    >
      为 {{ autoSearchOffIds.length }} 条订阅开启自动补搜
    </v-btn>

    <v-progress-linear v-if="loading" indeterminate color="primary" />

    <div class="task-list">
      <v-card v-for="sub in subscriptions" :key="sub.subId" class="task-card">
        <div class="card-content">
          <div class="card-top">
            <div class="card-title-row">
              <v-img
                v-if="sub.posterPath"
                :src="posterUrl(sub.posterPath)"
                width="32"
                height="46"
                cover
                class="poster"
              />
              <v-icon v-else class="card-title-icon" icon="mdi-television-classic" size="18" />
              <span class="card-title card-title--link" @click="openSubscription(sub.subId)">
                {{ sub.title }}
              </span>
            </div>
            <v-chip v-if="sub.maxOverdueDays !== null" size="x-small" color="error" variant="tonal">
              {{ sub.maxOverdueDays }} 天
            </v-chip>
          </div>

          <div class="chip-row">
            <v-chip
              v-for="code in sub.diagnoses"
              :key="code"
              size="x-small"
              variant="tonal"
              :color="diagnosisMeta(code).color === 'default' ? undefined : diagnosisMeta(code).color"
            >
              {{ diagnosisMeta(code).label }}
            </v-chip>
          </div>

          <div class="card-detail">
            <div class="detail-row">
              <span class="label">缺集</span>
              <span class="value">{{ episodeLabel(sub) }}</span>
            </div>
            <div v-if="sub.rejectDetail" class="detail-row">
              <span class="label">淘汰原因</span>
              <span class="value">{{ sub.rejectDetail }}</span>
            </div>
            <div class="detail-row">
              <span class="label">上次补搜</span>
              <span class="value">{{ sub.lastSearchTime || '从未补搜过' }}</span>
            </div>
          </div>

          <div class="card-actions">
            <v-btn
              v-if="!sub.autoSearch"
              size="small"
              color="primary"
              variant="flat"
              :loading="acting"
              @click="handleEnableAutoSearch([sub.subId])"
            >
              开启补搜
            </v-btn>
            <v-btn size="small" variant="outlined" :loading="acting" @click="handleSearchNow(sub.subId)">
              立即补搜
            </v-btn>
          </div>
        </div>
      </v-card>

      <v-empty-state
        v-if="!loading && subscriptions.length === 0"
        icon="mdi-check-decagram-outline"
        title="没有发现缺集"
        :text="`播出超过 ${report.overdueDays} 天的集都已入库或正在处理中`"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { usePtHealth, diagnosisMeta, posterUrl } from '@/composables/usePtHealth'
import type { SubscriptionHealthItem } from '@/api/openlist/ptHealth'

const {
  loading, acting, report, activeBucket, subscriptions, bucketTabs, autoSearchOffIds,
  load, handleEnableAutoSearch, handleSearchNow, openSubscription, setBucket
} = usePtHealth()

const pad = (n: number) => String(n ?? 0).padStart(2, '0')

/**
 * 手机上一行放不下上百个集号徽章，压成一行文本；超过 8 集折叠成「等 N 集」。
 * PC 端逐集渲染徽章是因为那里有横向空间，两端展示形态不同、数据同源。
 */
const episodeLabel = (sub: SubscriptionHealthItem) => {
  if (sub.mediaType === 'MOVIE') return '正片'
  const eps = sub.episodes.map((e) => `E${pad(e.episode)}`)
  return eps.length > 8 ? `${eps.slice(0, 8).join(' ')} 等 ${eps.length} 集` : eps.join(' ')
}
</script>

<style scoped>
.health-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px 0;
}

.summary {
  font-size: 14px;
  color: var(--osr-text-secondary);
}

.summary strong {
  font-size: 18px;
  color: rgb(var(--v-theme-primary));
}

.bucket-tabs {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  overflow-x: auto;
}

.bucket-tabs > * {
  flex: none;
}

.enable-all-btn {
  width: calc(100% - 24px);
  margin: 0 12px 8px;
}

.poster {
  flex: none;
  border-radius: 4px;
}

.chip-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
</style>
