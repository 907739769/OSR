<template>
  <!-- 新增订阅：TMDb 选片 -->
  <v-dialog v-model="subscribeOpen" width="92%">
    <v-card title="新增订阅">
      <v-card-text>
        <div class="subscribe-search-row">
          <v-select
            v-model="searchForm.mediaType"
            :items="[{ title: '剧集', value: 'TV' }, { title: '电影', value: 'MOVIE' }]"
            label="类型"
            density="compact"
            variant="outlined"
            hide-details
            class="type-select"
          />
          <v-text-field
            v-model="searchForm.keyword"
            label="片名"
            placeholder="输入片名后回车"
            density="compact"
            variant="outlined"
            hide-details
            class="keyword-field"
            @keyup.enter="doSearch"
          />
        </div>
        <v-btn color="primary" block :loading="searchLoading" class="mt-2" @click="doSearch">搜索 TMDb</v-btn>

        <div class="search-result-list">
          <v-progress-linear v-if="searchLoading" indeterminate color="primary" class="mt-2" />
          <div
            v-for="item in searchResults"
            :key="item.tmdbId"
            class="search-result-card"
            :class="{ selected: picked && picked.tmdbId === item.tmdbId }"
            @click="pick(item)"
          >
            <div class="result-poster">
              <img
                v-if="item.posterPath"
                :src="posterUrl(item.posterPath)"
                loading="lazy"
                @error="(e: Event) => ((e.target as HTMLImageElement).style.visibility = 'hidden')"
              />
              <v-icon v-else icon="mdi-image-outline" />
            </div>
            <div class="result-info">
              <span class="result-title">
                {{ item.title }}
                <span v-if="item.year" class="sub-year">({{ item.year }})</span>
              </span>
              <span v-if="item.originalTitle && item.originalTitle !== item.title" class="result-original">
                {{ item.originalTitle }}
              </span>
            </div>
            <v-icon v-if="picked && picked.tmdbId === item.tmdbId" icon="mdi-check-circle" color="primary" />
          </div>
          <v-empty-state v-if="!searchLoading && searchResults.length === 0" icon="mdi-magnify" title="暂无搜索结果" />
        </div>

        <div v-if="picked" class="picked-bar">
          已选：<strong>{{ picked.title }}</strong>
          <template v-if="searchForm.mediaType !== 'MOVIE'">
            &nbsp;第
            <v-text-field
              v-model.number="pickedSeason"
              type="number"
              min="0"
              max="99"
              density="compact"
              variant="outlined"
              hide-details
              class="season-field"
            />
            季
            <!-- 季号填错了原本要等订阅建完、回列表看「共 N 集」才发现 -->
            <span class="picked-season-hint">
              <template v-if="pickedSeasonCountLoading">查询集数中…</template>
              <template v-else-if="pickedSeasonEpisodeCount">该季共 {{ pickedSeasonEpisodeCount }} 集</template>
              <template v-else>TMDb 上查不到这一季</template>
            </span>
            <span class="sub-year">（第 0 季是特别篇）</span>
          </template>
        </div>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="outlined" @click="subscribeOpen = false">取消</v-btn>
        <v-btn color="primary" variant="flat" :loading="subscribeLoading" :disabled="!picked" @click="confirmSubscribe">
          订阅
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { usePtSubscriptionContext } from '@/composables/ptSubscriptionContext'

const {
  confirmSubscribe,
  doSearch,
  pick,
  picked,
  posterUrl,
  pickedSeason,
  pickedSeasonCountLoading,
  pickedSeasonEpisodeCount,
  searchForm,
  searchLoading,
  searchResults,
  subscribeLoading,
  subscribeOpen
} = usePtSubscriptionContext()
</script>

<style scoped lang="scss">
/* 搜索结果里的年份 / 季号提示。拆分前它写的是 .card-title .sub-year，选择器落在卡片上，
   对弹窗内部从来就没生效过——这次把守护扩到子组件才暴露出来 */
.sub-year {
  font-weight: 400;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.subscribe-search-row {
  display: flex;
  gap: 8px;

  .type-select {
    width: 110px;
    flex: none;
  }

  .keyword-field {
    flex: 1;
    min-width: 0;
  }
}
.search-result-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 40vh;
  overflow-y: auto;
  margin-top: 10px;
}
.search-result-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  border-radius: var(--osr-radius-sm);
  border: 2px solid transparent;
  background: var(--osr-bg-page);

  &.selected {
    border-color: var(--osr-primary-accent);
    background: var(--osr-primary-subtle);
  }
}
.result-poster {
  flex-shrink: 0;
  width: 40px;
  height: 60px;
  border-radius: var(--osr-radius-sm);
  overflow: hidden;
  background: var(--osr-surface);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--osr-text-disabled);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
}
.result-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.result-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--osr-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.result-original {
  font-size: 11px;
  color: var(--osr-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.picked-bar {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: var(--osr-radius-sm);
  background: var(--osr-bg-page);
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  font-size: 13px;

  .season-field {
    width: 90px;
    display: inline-flex;
  }
}
.picked-season-hint {
  margin-left: 4px;
  font-size: 12px;
  color: var(--osr-primary);
}
</style>
