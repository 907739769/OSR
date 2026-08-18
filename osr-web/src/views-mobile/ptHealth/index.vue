<template>
  <div class="mobile-page">
    <div class="health-bar">
      <div class="summary">
        <strong>{{ filteredCount.subscriptionCount }}</strong> 部 ·
        <strong>{{ filteredCount.episodeCount }}</strong> 集缺着
        <!-- 筛选时汇总要跟着走，否则会和选中的档位数字打架 -->
        <span v-if="filtering" class="summary-of-total">（共 {{ report.episodeCount }} 集）</span>
      </div>
      <v-btn icon="mdi-refresh" variant="text" density="comfortable" :loading="loading" :disabled="anyActing" @click="load" />
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

    <!-- 按成因筛选。后端一直在返回 diagnosisCounts，此前两端都没用过 -->
    <div v-if="diagnosisTabs.length" class="bucket-tabs">
      <v-chip
        v-for="tab in diagnosisTabs"
        :key="tab.key"
        :variant="activeDiagnosis === tab.key ? 'flat' : 'outlined'"
        :color="tab.color === 'default' ? undefined : tab.color"
        size="small"
        @click="setDiagnosis(tab.key)"
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
      :loading="batchActing"
      :disabled="anyActing"
      @click="handleEnableAutoSearch()"
    >
      为{{ filtering ? '筛选出的' : '' }} {{ autoSearchOffIds.length }} 条订阅开启自动补搜
    </v-btn>

    <v-progress-linear v-if="loading" indeterminate color="primary" />

    <!-- 加载失败要单独说：塞回空报告的话渲染出来是「没有发现缺集」，
         接口挂了和一切正常长得一模一样 -->
    <v-empty-state
      v-if="!loading && loadFailed"
      icon="mdi-alert-circle-outline"
      color="error"
      title="加载失败"
      text="没能拿到数据，下面的空白不代表没有缺集。"
    >
      <template #actions>
        <v-btn color="primary" variant="flat" prepend-icon="mdi-refresh" @click="load">重试</v-btn>
      </template>
    </v-empty-state>

    <div v-else class="task-list">
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
                <span v-if="sub.mediaType !== 'MOVIE'" class="card-season">S{{ pad(sub.season) }}</span>
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

          <!-- 「该怎么办」原先只挂在 chip 的 title 属性上，而手机根本没有 hover：
               这页最核心的那句话在移动端等于不存在 -->
          <ul class="advice-list">
            <li v-for="code in sub.diagnoses" :key="code" class="advice-item">
              {{ diagnosisMeta(code).advice }}
            </li>
          </ul>

          <!-- 集号改成带分档颜色的徽章：原先压成一行纯文本，分档这个信号在移动端整个丢了 -->
          <div class="episode-row">
            <v-chip
              v-for="ep in visibleEpisodes(sub)"
              :key="ep.episode"
              size="x-small"
              variant="outlined"
              :color="bucketMeta(ep.bucket).color === 'default' ? undefined : bucketMeta(ep.bucket).color"
            >
              {{ sub.mediaType === 'MOVIE' ? '正片' : `E${pad(ep.episode)}` }}
            </v-chip>
            <button
              v-if="hiddenEpisodeCount(sub) > 0"
              type="button"
              class="episode-more"
              @click="expandEpisodes(sub.subId)"
            >
              还有 {{ hiddenEpisodeCount(sub) }} 集
            </button>
          </div>

          <div class="card-detail">
            <div v-if="sub.rejectDetail" class="detail-row">
              <span class="label">淘汰原因</span>
              <span class="value">{{ sub.rejectDetail }}</span>
            </div>
            <div v-if="sub.missStreak > 0" class="detail-row">
              <span class="label">连续落空</span>
              <span class="value">{{ sub.missStreak }} 轮</span>
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
              :loading="isActing(sub.subId)"
              :disabled="anyActing"
              @click="handleEnableAutoSearch([sub.subId])"
            >
              开启补搜
            </v-btn>
            <v-btn
              size="small"
              variant="outlined"
              :loading="isActing(sub.subId)"
              :disabled="anyActing"
              @click="handleSearchNow(sub.subId)"
            >
              立即补搜
            </v-btn>
          </div>
          <!-- 手机上没有 hover，两个按钮的区别只能写出来 -->
          <p class="action-hint">开启补搜=打开自动开关；立即补搜=现在就搜一次，要跑几十秒</p>
        </div>
      </v-card>

      <v-empty-state
        v-if="!loading && subscriptions.length === 0"
        icon="mdi-check-decagram-outline"
        title="没有发现缺集"
        :text="filtering
          ? '当前筛选条件下没有条目'
          : `播出超过 ${report.overdueDays} 天的集都已入库或正在处理中`"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { usePtHealth, bucketMeta, diagnosisMeta, posterUrl } from '@/composables/usePtHealth'
import type { SubscriptionHealthItem } from '@/api/openlist/ptHealth'

const {
  loading, loadFailed, report,
  activeBucket, activeDiagnosis, subscriptions, filteredCount, filtering,
  bucketTabs, diagnosisTabs, autoSearchOffIds,
  batchActing, isActing, anyActing,
  load, handleEnableAutoSearch, handleSearchNow, openSubscription, setBucket, setDiagnosis
} = usePtHealth()

const pad = (n: number) => String(n ?? 0).padStart(2, '0')

/** 手机一行放得下六七个 x-small 徽章，12 个正好两行；再多就折叠 */
const EPISODE_PREVIEW_LIMIT = 12
const expandedSubs = ref(new Set<number>())

const visibleEpisodes = (sub: SubscriptionHealthItem) =>
  expandedSubs.value.has(sub.subId) ? sub.episodes : sub.episodes.slice(0, EPISODE_PREVIEW_LIMIT)

const hiddenEpisodeCount = (sub: SubscriptionHealthItem) =>
  sub.episodes.length - visibleEpisodes(sub).length

const expandEpisodes = (subId: number) => {
  // Set 是 ref 里的普通对象，就地 add 不触发更新，换一个新 Set
  expandedSubs.value = new Set(expandedSubs.value).add(subId)
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

.chip-row,
.episode-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.card-season {
  margin-left: 4px;
  font-size: 11px;
  font-weight: 400;
  color: var(--osr-text-secondary);
}

.summary-of-total {
  font-size: 11px;
  color: var(--osr-text-secondary);
}

/* 处置建议：原先只在 chip 的 title 里，手机没有 hover 等于看不见 */
.advice-list {
  margin: 0;
  padding-left: 16px;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.advice-item {
  line-height: 1.6;
}

.episode-more {
  padding: 2px 6px;
  border: none;
  background: transparent;
  font-size: 12px;
  color: var(--osr-primary);
  cursor: pointer;
}

.action-hint {
  margin: 2px 0 0;
  font-size: 11px;
  color: var(--osr-text-disabled);
}
</style>
