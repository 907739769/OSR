<template>
  <div class="page-container">
    <PageHeader
      icon="search"
      title="资源搜索"
      desc="不建订阅，按关键词直接搜全部站点。标出全局过滤规则会挡掉哪些，可直接下载或转为订阅"
    />

    <v-card class="resource-query">
      <div class="search-bar">
        <v-text-field
          v-model="keyword"
          class="search-keyword"
          density="compact"
          variant="outlined"
          prepend-inner-icon="search"
          placeholder="片名或关键词，如 三体 S01、Dune Part Two 2024"
          hide-details
          clearable
          @keyup.enter="handleSearch"
        />
        <v-select
          v-model="indexerIds"
          :items="indexerOptions"
          item-title="name"
          item-value="id"
          class="search-indexers"
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
        <v-btn color="primary" :loading="searching" prepend-icon="search" @click="handleSearch">搜索</v-btn>
      </div>
    </v-card>

    <v-card class="table-card">
      <v-progress-linear v-if="searching" indeterminate color="primary" />
      <div v-if="searching && !results.length" class="empty-tip">正在搜索各站点，慢站点最多要等半分钟…</div>
      <v-empty-state
        v-else-if="!searched"
        icon="search"
        title="输入关键词开始搜索"
        text="结果不按过滤规则淘汰：会被全局规则挡掉的会标出原因，方便看出「为什么订了却没自动下」"
      />
      <v-empty-state v-else-if="!results.length" icon="inbox" title="没有搜到资源" text="换个关键词，或检查站点选择" />

      <template v-else>
        <!-- 筛选与订阅内候选弹窗共用 useCandidateFilter；只有一种取值的维度不给下拉 -->
        <div class="candidate-filters">
          <v-text-field
            v-model="filter.keyword"
            class="filter-keyword"
            density="compact"
            prepend-inner-icon="funnel"
            placeholder="在结果里筛选，空格分隔；- 开头排除，如 -HDR"
            hide-details
            clearable
          />
          <v-select
            v-if="facets.indexers.length > 1"
            v-model="filter.indexerIds"
            :items="facets.indexers"
            class="filter-select"
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
            class="filter-select"
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
            class="filter-select"
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
        <div class="candidate-filter-bar">
          <v-checkbox-btn v-model="filter.freeOnly" label="仅免费" density="compact" />
          <v-checkbox-btn v-model="filter.seededOnly" label="仅有做种" density="compact" />
          <v-checkbox-btn v-model="passedOnly" :label="`只看规则放行的（${results.length - rejectedCount}）`" density="compact" />
          <v-spacer />
          <span class="filter-count">显示 {{ visibleResults.length }} / {{ results.length }}</span>
          <span v-if="lookupNote" class="lookup-note">{{ lookupNote }}</span>
          <span v-else-if="lookupSummary" class="filter-count">{{ lookupSummary }}</span>
          <v-btn v-if="activeFilterCount" variant="text" size="small" @click="clearFilters">清空筛选</v-btn>
        </div>

        <v-data-table
          :items="visibleResults"
          :headers="headers"
          fixed-header
          height="calc(100vh - 380px)"
          items-per-page="-1"
          hide-default-footer
          density="compact"
          class="modern-table"
          no-data-text="没有符合筛选条件的结果"
        >
          <template #item.target="{ item }">
            <v-chip v-if="item.parsedEpisode && (item.parsedEpisodeEnd ?? 0) > item.parsedEpisode" size="small" color="warning" variant="tonal">
              S{{ item.parsedSeason ?? '?' }} 第{{ item.parsedEpisode }}-{{ item.parsedEpisodeEnd }}集
            </v-chip>
            <v-chip v-else-if="item.parsedEpisode" size="small" color="warning" variant="tonal">
              S{{ item.parsedSeason ?? '?' }}E{{ item.parsedEpisode }}
            </v-chip>
            <v-chip v-else-if="item.parsedSeason != null" size="small" color="success" variant="tonal">第{{ item.parsedSeason }}季</v-chip>
            <v-chip v-else size="small" variant="tonal">电影</v-chip>
          </template>
          <template #item.indexerName="{ item }">
            <v-chip size="small" color="info" variant="tonal">{{ item.indexerName }}</v-chip>
          </template>
          <template #item.title="{ item }">
            <div class="title-cell">
              <span class="title-text" :title="item.title">{{ item.title }}</span>
              <span v-if="item.subtitle" class="title-subtitle" :title="item.subtitle">{{ item.subtitle }}</span>
              <div v-if="item.matchedTitle || item.parsedTitle || item.parsedYear || item.detailUrl" class="title-parsed">
                <template v-if="item.matchedTitle">
                  <span class="work-name">{{ item.matchedTitle }}<template v-if="item.matchedYear"> ({{ item.matchedYear }})</template></span>
                  <TmdbLink :tmdb-id="item.matchedTmdbId" :media-type="item.mediaType" variant="text" />
                </template>
                <template v-else>
                  <span>{{ item.parsedTitle }}<template v-if="item.parsedYear"> ({{ item.parsedYear }})</template></span>
                  <span v-if="lookupComplete && item.parsedTitle" class="work-unknown">识别不出</span>
                </template>
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
                <StatusChip v-if="item.subscribed" type="success" text="已订阅" />
              </div>
            </div>
          </template>
          <template #item.resolution="{ item }">{{ item.resolution || '-' }}</template>
          <template #item.source="{ item }">{{ item.source || '-' }}</template>
          <template #item.size="{ item }">{{ formatSize(item.size) }}</template>
          <template #item.seeders="{ item }">
            <v-chip :color="item.seeders > 0 ? 'success' : 'error'" size="small" variant="tonal">{{ item.seeders }}</v-chip>
          </template>
          <template #item.tags="{ item }">
            <v-chip v-if="item.free" color="warning" size="x-small" variant="tonal" class="mr-1">免费</v-chip>
            <v-chip v-if="item.hitAndRun" color="error" size="x-small" variant="tonal">H&R</v-chip>
          </template>
          <template #item.ruleRejection="{ item }">
            <v-chip
              v-if="item.ruleRejection"
              color="warning"
              size="small"
              variant="tonal"
              :title="item.ruleRejectionDetail || item.ruleRejection"
            >
              {{ item.ruleRejection }}
            </v-chip>
            <v-icon v-else icon="circle-check" color="success" size="16" title="全局过滤规则会放行" />
          </template>
          <template #item.actions="{ item }">
            <v-btn variant="text" size="small" color="primary" @click="toSubscribe(item)">转订阅</v-btn>
            <v-btn v-if="isAdmin" variant="text" size="small" color="primary" :disabled="!item.downloadUrl" @click="openPush(item)">下载</v-btn>
          </template>
        </v-data-table>
      </template>
    </v-card>

    <ResourcePushDialog />
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import ResourcePushDialog from '@/components/dialogs/ResourcePushDialog.vue'
import StatusChip from '@/components/StatusChip.vue'
import TmdbLink from '@/components/TmdbLink.vue'
import { usePageStateProvider } from '@/composables/pageStateContext'
import { usePtResourceSearch } from '@/composables/usePtResourceSearch'

const {
  isAdmin,
  keyword, indexerIds, indexerOptions, searching, searched, results, rejectedCount, handleSearch,
  lookupNote, lookupSummary, lookupComplete,
  filter, facets, activeFilterCount, clearFilters, passedOnly, visibleResults,
  openPush, toSubscribe, formatSize
} = usePageStateProvider(usePtResourceSearch())

// 「规则」列只标注不淘汰：被全局过滤规则挡掉的会写出原因（悬浮看实际值与阈值）
const headers = [
  { title: '目标', key: 'target', sortable: false, width: 110, align: 'center' as const },
  { title: '站点', key: 'indexerName', sortable: true, width: 100 },
  { title: '标题', key: 'title', sortable: false, minWidth: '300' },
  { title: '分辨率', key: 'resolution', sortable: true, width: 84, align: 'center' as const },
  { title: '片源', key: 'source', sortable: true, width: 84, align: 'center' as const },
  { title: '体积', key: 'size', sortable: true, width: 96, align: 'end' as const },
  { title: '做种', key: 'seeders', sortable: true, width: 72, align: 'center' as const },
  { title: '标签', key: 'tags', sortable: false, width: 96 },
  { title: '规则', key: 'ruleRejection', sortable: false, width: 140, align: 'center' as const },
  { title: '操作', key: 'actions', sortable: false, width: 130, align: 'center' as const }
]
</script>

<style scoped lang="scss">
.resource-query {
  padding: 12px 16px;
  margin-bottom: 16px;
}

.search-bar {
  display: flex;
  gap: 8px;
  align-items: center;

  .search-keyword {
    flex: 3 1 320px;
  }

  .search-indexers {
    flex: 1 1 160px;
    max-width: 220px;
  }
}

.empty-tip {
  text-align: center;
  padding: 40px;
  color: var(--osr-text-secondary);
}

.candidate-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 16px 0;

  .filter-keyword {
    flex: 2 1 260px;
  }

  .filter-select {
    flex: 1 1 120px;
    max-width: 160px;
  }
}

.selection-summary {
  font-size: 13px;
  white-space: nowrap;
}

.candidate-filter-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 16px 8px;

  .filter-count {
    font-size: 12px;
    color: var(--osr-text-secondary);
  }
}

.title-cell {
  display: flex;
  flex-direction: column;
  padding: 4px 0;
  min-width: 0;

  .title-text {
    word-break: break-all;
  }

  .title-subtitle {
    font-size: 12px;
    color: var(--osr-text-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .title-parsed {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: var(--osr-text-secondary);
  }

  .work-name {
    color: var(--osr-text-primary);
  }

  .work-unknown {
    color: var(--osr-text-secondary);
  }

  .detail-link {
    display: inline-flex;
    color: var(--osr-text-secondary);

    &:hover {
      color: var(--osr-primary);
    }
  }
}

.lookup-note {
  font-size: 12px;
  color: var(--osr-warning);
}
</style>
