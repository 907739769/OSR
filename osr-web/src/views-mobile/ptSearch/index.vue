<template>
  <MobileListPage
    :loading="false"
    :empty="searched && !searching && visibleResults.length === 0"
    empty-icon="inbox"
    :empty-title="results.length ? '没有符合筛选条件的结果' : '没有搜到资源'"
  >
    <template #head>
      <div class="search-head">
        <v-text-field
          v-model="keyword"
          density="compact"
          variant="outlined"
          prepend-inner-icon="search"
          placeholder="片名或关键词，如 三体 S01"
          hide-details
          clearable
          @keyup.enter="handleSearch"
        />
        <div class="search-row">
          <v-select
            v-model="indexerIds"
            :items="indexerOptions"
            item-title="name"
            item-value="id"
            density="compact"
            variant="outlined"
            label="站点"
            placeholder="全部启用站点"
            multiple
            hide-details
            clearable
          >
            <template #selection="{ index }">
              <span v-if="index === 0" class="selection-summary">已选 {{ indexerIds.length }}</span>
            </template>
          </v-select>
          <v-btn color="primary" :loading="searching" @click="handleSearch">搜索</v-btn>
        </div>

        <!-- 筛选与 PC 端、订阅内候选弹窗共用 useCandidateFilter -->
        <template v-if="results.length">
          <v-text-field
            v-model="filter.keyword"
            density="compact"
            prepend-inner-icon="funnel"
            placeholder="在结果里筛选，- 开头排除"
            hide-details
            clearable
            class="mt-2"
          />
          <div class="search-row mt-2">
            <v-select
              v-if="facets.indexers.length > 1"
              v-model="filter.indexerIds"
              :items="facets.indexers"
              label="站点"
              density="compact"
              multiple
              hide-details
              clearable
            >
              <template #selection="{ index }">
                <span v-if="index === 0" class="selection-summary">已选 {{ filter.indexerIds.length }}</span>
              </template>
            </v-select>
            <v-select
              v-if="facets.resolutions.length > 1"
              v-model="filter.resolutions"
              :items="facets.resolutions"
              label="分辨率"
              density="compact"
              multiple
              hide-details
              clearable
            >
              <template #selection="{ index }">
                <span v-if="index === 0" class="selection-summary">已选 {{ filter.resolutions.length }}</span>
              </template>
            </v-select>
            <v-select
              v-if="facets.sources.length > 1"
              v-model="filter.sources"
              :items="facets.sources"
              label="片源"
              density="compact"
              multiple
              hide-details
              clearable
            >
              <template #selection="{ index }">
                <span v-if="index === 0" class="selection-summary">已选 {{ filter.sources.length }}</span>
              </template>
            </v-select>
          </div>
          <div class="filter-bar">
            <v-checkbox-btn v-model="filter.freeOnly" label="仅免费" density="compact" />
            <v-checkbox-btn v-model="filter.seededOnly" label="有做种" density="compact" />
            <v-checkbox-btn v-model="passedOnly" label="规则放行" density="compact" />
          </div>
          <div class="filter-summary">
            显示 {{ visibleResults.length }} / {{ results.length }}，{{ rejectedCount }} 个会被全局规则挡掉
            <span v-if="lookupNote" class="lookup-note">{{ lookupNote }}</span>
            <span v-else-if="lookupSummary">· {{ lookupSummary }}</span>
            <v-btn v-if="activeFilterCount" variant="text" size="x-small" @click="clearFilters">清空筛选</v-btn>
          </div>
        </template>
        <v-progress-linear v-if="searching" indeterminate color="primary" class="mt-2" />
        <div v-if="!searched && !searching" class="hint">
          结果不按过滤规则淘汰：会被全局规则挡掉的会标出原因
        </div>
      </div>
    </template>

    <v-card v-for="item in visibleResults" :key="`${item.indexerId}-${item.guid}`" class="task-card">
      <div class="card-content">
        <div class="card-top">
          <span class="card-title result-title">{{ item.title }}</span>
        </div>
        <div v-if="item.subtitle" class="result-subtitle">{{ item.subtitle }}</div>
        <div class="chips">
          <v-chip v-if="item.parsedEpisode && (item.parsedEpisodeEnd ?? 0) > item.parsedEpisode" size="x-small" color="warning" variant="tonal">
            S{{ item.parsedSeason ?? '?' }} 第{{ item.parsedEpisode }}-{{ item.parsedEpisodeEnd }}集
          </v-chip>
          <v-chip v-else-if="item.parsedEpisode" size="x-small" color="warning" variant="tonal">
            S{{ item.parsedSeason ?? '?' }}E{{ item.parsedEpisode }}
          </v-chip>
          <v-chip v-else-if="item.parsedSeason != null" size="x-small" color="success" variant="tonal">第{{ item.parsedSeason }}季</v-chip>
          <v-chip v-else size="x-small" variant="tonal">电影</v-chip>
          <v-chip size="x-small" color="info" variant="tonal">{{ item.indexerName }}</v-chip>
          <v-chip v-if="item.resolution" size="x-small" variant="tonal">{{ item.resolution }}</v-chip>
          <v-chip v-if="item.source" size="x-small" variant="tonal">{{ item.source }}</v-chip>
          <v-chip v-if="item.free" size="x-small" color="warning" variant="tonal">免费</v-chip>
          <v-chip v-if="item.hitAndRun" size="x-small" color="error" variant="tonal">H&R</v-chip>
        </div>
        <div class="card-detail">
          <div class="detail-row">
            <span class="label">体积 / 做种</span>
            <span class="value">{{ formatSize(item.size) }} · {{ item.seeders }} 做种</span>
          </div>
          <div class="detail-row">
            <span class="label">作品</span>
            <span class="value">
              <template v-if="item.matchedTitle">
                {{ item.matchedTitle }}<template v-if="item.matchedYear"> ({{ item.matchedYear }})</template>
                <TmdbLink :tmdb-id="item.matchedTmdbId" :media-type="item.mediaType" variant="text" />
              </template>
              <template v-else>
                {{ item.parsedTitle || '-' }}
                <span v-if="lookupComplete && item.parsedTitle" class="work-unknown">识别不出</span>
              </template>
              <StatusChip v-if="item.subscribed" type="success" text="已订阅" />
            </span>
          </div>
          <div class="detail-row">
            <span class="label">全局规则</span>
            <span v-if="item.ruleRejection" class="value text-warning">{{ item.ruleRejectionDetail || item.ruleRejection }}</span>
            <span v-else class="value text-success">放行</span>
          </div>
        </div>
        <div class="card-actions">
          <v-btn variant="text" color="primary" size="small" @click="toSubscribe(item)">转为订阅</v-btn>
          <v-btn v-if="isAdmin" variant="text" color="primary" size="small" :disabled="!item.downloadUrl" @click="openPush(item)">直接下载</v-btn>
          <a
            v-if="item.detailUrl"
            class="detail-link"
            :href="item.detailUrl"
            target="_blank"
            rel="noopener noreferrer"
            title="站点详情页"
            aria-label="站点详情页"
          >
            <v-icon size="14" icon="external-link" />
          </a>
        </div>
      </div>
    </v-card>

    <template #foot>
      <ResourcePushDialog />
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import ResourcePushDialog from '@/components/dialogs/ResourcePushDialog.vue'
import StatusChip from '@/components/StatusChip.vue'
import TmdbLink from '@/components/TmdbLink.vue'
import { usePageStateProvider } from '@/composables/pageStateContext'
import { usePtResourceSearch } from '@/composables/usePtResourceSearch'

// 与 PC 端共用同一个 composable；直接下载弹窗两端共用（components/dialogs/）
const {
  isAdmin,
  keyword, indexerIds, indexerOptions, searching, searched, results, rejectedCount, handleSearch,
  lookupNote, lookupSummary, lookupComplete,
  filter, facets, activeFilterCount, clearFilters, passedOnly, visibleResults,
  openPush, toSubscribe, formatSize
} = usePageStateProvider(usePtResourceSearch())
</script>

<style scoped lang="scss">
.search-head {
  padding: 4px 0 8px;
}

.search-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 8px;

  > * {
    flex: 1 1 0;
    min-width: 0;
  }

  > .v-btn {
    flex: 0 0 auto;
  }
}

.selection-summary {
  font-size: 13px;
  white-space: nowrap;
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  margin-top: 4px;
}

.filter-summary,
.hint {
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.lookup-note {
  color: var(--osr-warning);
}

.work-unknown {
  color: var(--osr-text-secondary);
}

.detail-link {
  display: inline-flex;
  align-items: center;
  color: var(--osr-text-secondary);

  &:hover {
    color: var(--osr-primary);
  }
}

.hint {
  margin-top: 8px;
}

.result-title {
  white-space: normal;
  word-break: break-all;
}

.result-subtitle {
  font-size: 12px;
  color: var(--osr-text-secondary);
  word-break: break-all;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin: 6px 0;
}
</style>
