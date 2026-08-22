<template>
  <!-- 新增订阅：TMDb 选片 -->
  <v-dialog v-model="subscribeOpen" max-width="600">
    <v-card title="新增订阅">
      <v-card-text>
        <div class="inline-fields">
          <v-select
            v-model="searchForm.mediaType"
            :items="[{ title: '剧集', value: 'TV' }, { title: '电影', value: 'MOVIE' }]"
            label="类型"
            density="compact"
            variant="outlined"
            hide-details
            style="width: 120px"
          />
          <v-text-field
            v-model="searchForm.keyword"
            label="片名"
            placeholder="输入片名后回车"
            density="compact"
            variant="outlined"
            hide-details
            style="width: 280px"
            @keyup.enter="doSearch"
          />
          <v-btn color="primary" :loading="searchLoading" @click="doSearch">搜索 TMDb</v-btn>
        </div>

        <v-data-table
          :loading="searchLoading"
          :items="searchResults"
          :headers="searchHeaders"
          height="300"
          fixed-header
          items-per-page="-1"
          hide-default-footer
          density="compact"
          class="mt-3 modern-table"
          :row-props="(row: any) => ({ class: picked && picked.tmdbId === row.item.tmdbId ? 'row-selected' : '', style: 'cursor:pointer', onClick: () => pick(row.item) })"
        >
          <template #item.poster="{ item }">
            <img
              v-if="item.posterPath"
              :src="posterUrl(item.posterPath)"
              class="search-poster"
              loading="lazy"
              @error="(e: Event) => ((e.target as HTMLImageElement).style.visibility = 'hidden')"
            />
            <v-icon v-else icon="image" class="search-poster-placeholder" />
          </template>
          <template #item.title="{ item }">
            {{ item.title }}
            <span v-if="item.originalTitle && item.originalTitle !== item.title" class="sub-year">
              / {{ item.originalTitle }}
            </span>
          </template>
          <template #item.year="{ item }">{{ item.year || '-' }}</template>
        </v-data-table>

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
              style="width: 110px; display: inline-flex"
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

const searchHeaders = [
  { title: '海报', key: 'poster', sortable: false, width: 70, align: 'center' as const },
  { title: '标题', key: 'title', sortable: false, minWidth: '200' },
  { title: '年份', key: 'year', sortable: false, width: 80, align: 'center' as const },
  { title: 'TMDb ID', key: 'tmdbId', sortable: false, width: 100, align: 'center' as const }
]

</script>

<style scoped lang="scss">
.sub-year {
  color: var(--osr-text-secondary);
  font-size: 12px;
}
.picked-bar {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: var(--osr-radius-sm);
  background: var(--osr-bg-page);
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}
.picked-season-hint {
  font-size: 12px;
  color: var(--osr-primary);
}
.search-poster {
  width: 40px;
  height: 60px;
  object-fit: cover;
  border-radius: var(--osr-radius-sm);
  display: block;
  margin: 0 auto;
}
.search-poster-placeholder {
  width: 40px;
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto;
  color: var(--osr-text-disabled);
  font-size: 18px;
}
</style>
