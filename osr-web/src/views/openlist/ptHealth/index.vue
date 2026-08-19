<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-stethoscope"
      title="缺集体检"
      :desc="`列出播出超过 ${report.overdueDays} 天仍未入库的集，并说明每一集「为什么还缺」`"
    >
      <template #actions>
        <span v-if="lastLoadedText" class="last-loaded">{{ lastLoadedText }}</span>
        <v-btn
          v-if="autoSearchOffIds.length > 0"
          color="primary"
          variant="flat"
          prepend-icon="mdi-magnify-scan"
          :loading="batchActing"
          :disabled="anyActing"
          :title="filtering
            ? '只对当前筛选出来的订阅生效'
            : '对列表里全部未开启自动补搜的订阅生效'"
          @click="handleEnableAutoSearch()"
        >
          为{{ filtering ? '筛选出的' : '' }} {{ autoSearchOffIds.length }} 条订阅开启自动补搜
        </v-btn>
        <v-btn variant="outlined" prepend-icon="mdi-refresh" :loading="loading" :disabled="anyActing" @click="load">刷新</v-btn>
      </template>
    </PageHeader>

    <v-card class="table-card">
      <div class="health-toolbar">
        <div class="summary">
          <span class="summary-main">
            <strong>{{ filteredCount.subscriptionCount }}</strong> 部作品 ·
            <strong>{{ filteredCount.episodeCount }}</strong> 集
            <!-- 筛选时汇总要跟着走，否则左边写「12 部 47 集」右边选中「已熔断 2」，两个数打架 -->
            <span v-if="filtering" class="summary-of-total">
              （共 {{ report.subscriptionCount }} 部 {{ report.episodeCount }} 集）
            </span>
          </span>
          <span class="summary-hint">仅统计「订阅中」的订阅；未播出的集不参与</span>
        </div>

        <div class="filter-groups">
          <div class="filter-row">
            <span class="filter-label">按处置方向</span>
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

          <!-- 诊断比分档更贴近「我现在该做什么」，后端一直在返回 diagnosisCounts，此前没人用 -->
          <div v-if="diagnosisTabs.length" class="filter-row">
            <span class="filter-label">按成因</span>
            <v-chip
              v-for="tab in diagnosisTabs"
              :key="tab.key"
              :variant="activeDiagnosis === tab.key ? 'flat' : 'outlined'"
              :color="tab.color === 'default' ? undefined : tab.color"
              size="small"
              :title="tab.advice"
              @click="setDiagnosis(tab.key)"
            >
              {{ tab.label }} {{ tab.count }}
            </v-chip>
          </div>
        </div>
      </div>

      <!-- 忽略必须配一个能找回来的入口，否则它就是个不可撤销的操作 -->
      <div v-if="report.ignoredCount > 0 || includeIgnored" class="ignored-bar">
        <v-btn
          variant="text"
          size="small"
          :prepend-icon="includeIgnored ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
          :disabled="loading || anyActing"
          @click="toggleIncludeIgnored"
        >
          {{ includeIgnored ? '隐藏已忽略' : `显示已忽略（${report.ignoredCount}）` }}
        </v-btn>
        <span class="ignored-hint">忽略只影响这个页面与逾期提醒，不影响 RSS 匹配与补搜</span>
      </div>

      <v-progress-linear v-if="loading" indeterminate color="primary" />

      <!-- 加载失败要单独说。塞回空报告的话，渲染出来是绿色对勾「没有发现缺集」——
           接口挂了和一切正常长得一模一样，对体检页来说这是最不该给的错误答案 -->
      <v-empty-state
        v-if="!loading && loadFailed"
        icon="mdi-alert-circle-outline"
        color="error"
        title="体检报告加载失败"
        text="没能拿到数据，所以下面是空的——这不代表没有缺集。请检查后端是否可用后重试。"
      >
        <template #actions>
          <v-btn color="primary" variant="flat" prepend-icon="mdi-refresh" @click="load">重试</v-btn>
        </template>
      </v-empty-state>

      <div v-else class="health-list">
        <v-card
          v-for="sub in subscriptions"
          :key="sub.subId"
          class="health-item"
          :class="{ 'health-item--ignored': sub.ignored }"
          variant="outlined"
        >
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
                <v-chip v-if="sub.ignored" size="x-small" variant="tonal" prepend-icon="mdi-bell-off-outline">
                  已忽略
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

              <!-- 「该怎么办」原先只挂在 chip 的 title 上：PC 要 hover 一个 x-small 的小块才看得到，
                   移动端压根没有 hover。而这页的立身之本就是说清「为什么还缺」，铺出来才算数 -->
              <ul class="advice-list">
                <li v-for="code in sub.diagnoses" :key="code" class="advice-item">
                  {{ diagnosisMeta(code).advice }}
                </li>
              </ul>

              <div class="episode-row">
                <v-chip
                  v-for="ep in visibleEpisodes(sub)"
                  :key="ep.episode"
                  size="x-small"
                  variant="outlined"
                  :color="bucketMeta(ep.bucket).color === 'default' ? undefined : bucketMeta(ep.bucket).color"
                  :title="episodeTip(ep)"
                >
                  {{ sub.mediaType === 'MOVIE' ? '正片' : `E${pad(ep.episode)}` }}
                </v-chip>
                <!-- 原先是个 84px 高的内嵌滚动框：滚轮容易误触，而且看不出下面还有内容 -->
                <button
                  v-if="hiddenEpisodeCount(sub) > 0"
                  type="button"
                  class="episode-more"
                  @click="expandEpisodes(sub.subId)"
                >
                  还有 {{ hiddenEpisodeCount(sub) }} 集，全部展开
                </button>
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
              :loading="isActing(sub.subId)"
              :disabled="anyActing"
              title="打开这条订阅的自动补搜开关，之后每轮心跳都会自动搜一次缺集"
              @click="handleEnableAutoSearch([sub.subId])"
            >
              开启自动补搜
            </v-btn>
            <v-btn
              size="small"
              variant="outlined"
              :loading="isActing(sub.subId)"
              :disabled="anyActing"
              title="立刻搜一次它当前的全部缺集，没开自动补搜也能用；要跑几十秒"
              @click="handleSearchNow(sub.subId)"
            >
              立即补搜
            </v-btn>
            <v-btn size="small" variant="text" @click="openSubscription(sub.subId)">查看订阅</v-btn>
            <v-btn
              v-if="sub.ignored"
              size="small"
              variant="text"
              :loading="isActing(sub.subId)"
              :disabled="anyActing"
              @click="handleSetIgnored(sub.subId, false)"
            >取消忽略</v-btn>
            <v-btn
              v-else
              size="small"
              variant="text"
              :loading="isActing(sub.subId)"
              :disabled="anyActing"
              title="不再在体检与逾期提醒里出现；RSS 匹配与补搜照常，随时可以撤销"
              @click="handleSetIgnored(sub.subId, true)"
            >忽略</v-btn>
          </div>
        </v-card>

        <v-empty-state
          v-if="!loading && subscriptions.length === 0"
          icon="mdi-check-decagram-outline"
          title="没有发现缺集"
          :text="filtering
            ? '当前筛选条件下没有条目，换一个筛选看看'
            : `所有「订阅中」的作品，播出超过 ${report.overdueDays} 天的集都已入库或正在处理中`"
        />
      </div>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import { usePtHealth, bucketMeta, diagnosisMeta, posterUrl } from '@/composables/usePtHealth'
import type { EpisodeHealthItem, SubscriptionHealthItem } from '@/api/openlist/ptHealth'

const {
  loading, loadFailed, lastLoadedAt, report,
  activeBucket, activeDiagnosis, subscriptions, filteredCount, filtering,
  bucketTabs, diagnosisTabs, autoSearchOffIds,
  batchActing, isActing, anyActing,
  includeIgnored, handleSetIgnored, toggleIncludeIgnored,
  load, handleEnableAutoSearch, handleSearchNow, openSubscription, setBucket, setDiagnosis
} = usePtHealth()

const pad = (n: number) => String(n ?? 0).padStart(2, '0')

/** 数据什么时候拉的。页面开着放一整天时，这行是唯一能看出数据有多旧的地方 */
const lastLoadedText = computed(() => {
  const at = lastLoadedAt.value
  if (!at) return ''
  return `数据更新于 ${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`
})

/**
 * 集号默认只铺前 24 个。一季上百集的剧（长篇动画很常见）全铺开会把单张卡片顶到几屏高，
 * 而前几个集号已经足够判断「缺的是开头还是结尾」。
 */
const EPISODE_PREVIEW_LIMIT = 24
const expandedSubs = ref(new Set<number>())

const visibleEpisodes = (sub: SubscriptionHealthItem) =>
  expandedSubs.value.has(sub.subId) ? sub.episodes : sub.episodes.slice(0, EPISODE_PREVIEW_LIMIT)

const hiddenEpisodeCount = (sub: SubscriptionHealthItem) =>
  sub.episodes.length - visibleEpisodes(sub).length

const expandEpisodes = (subId: number) => {
  // Set 是 ref 里的普通对象，就地 add 不会触发更新，换一个新 Set
  expandedSubs.value = new Set(expandedSubs.value).add(subId)
}

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

.filter-groups {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.filter-label {
  font-size: 12px;
  color: var(--osr-text-secondary);
  white-space: nowrap;
}

.summary-of-total {
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.last-loaded {
  margin-right: 4px;
  font-size: 12px;
  color: var(--osr-text-secondary);
  white-space: nowrap;
}

/* 处置建议：原先只在 chip 的 title 里，移动端根本拿不到 */
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

.item-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.ignored-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px 8px;
  flex-wrap: wrap;
}

.ignored-hint {
  font-size: 12px;
  color: var(--osr-text-secondary);
}

/* 已忽略的行压低视觉权重：它们是用户主动收起来的，不该和真正要处理的条目抢注意力 */
.health-item--ignored {
  opacity: 0.62;
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
