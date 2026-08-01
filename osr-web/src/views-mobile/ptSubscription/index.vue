<template>
  <div class="mobile-page">
    <!-- 搜索 -->
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <v-text-field
        v-model="queryParams.title"
        label="标题"
        placeholder="请输入标题"
        clearable
        density="compact"
        variant="outlined"
        @keyup.enter="handleQuery"
      />
      <v-select
        v-model="queryParams.mediaType"
        :items="[{ title: '剧集', value: 'TV' }, { title: '电影', value: 'MOVIE' }]"
        label="类型"
        placeholder="全部类型"
        clearable
        density="compact"
        variant="outlined"
      />
      <v-select
        v-model="queryParams.status"
        :items="[{ title: '订阅中', value: 'ACTIVE' }, { title: '已完成', value: 'COMPLETED' }, { title: '已暂停', value: 'PAUSED' }]"
        label="状态"
        placeholder="全部状态"
        clearable
        density="compact"
        variant="outlined"
      />
      <v-select
        v-model="queryParams.sortBy"
        :items="[{ title: '默认（最新创建）', value: '' }, { title: '上次命中时间', value: 'lastMatchTime' }]"
        label="排序"
        placeholder="排序"
        clearable
        density="compact"
        variant="outlined"
        @update:model-value="handleQuery"
      />
    </MobileSearchPanel>

    <!-- 新增 FAB -->
    <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="mdi-plus" @click="openSubscribeDialog">
      新增
    </v-btn>

    <!-- 列表 -->
    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <div v-for="item in taskList" :key="item.id" class="sub-card" :class="{ selected: selectedIds.includes(item.id) }" @click="toggleSubSelect(item)">
        <div class="sub-poster">
          <img
            v-if="item.posterPath && !posterErrorIds.has(item.id)"
            :src="posterUrl(item.posterPath)"
            :alt="item.title"
            loading="lazy"
            @error="posterErrorIds.add(item.id)"
          />
          <div v-else class="sub-poster-placeholder">
            <v-icon icon="mdi-image-outline" />
          </div>
        </div>
        <div class="sub-content">
          <div class="sub-top">
            <span class="sub-name">
              {{ item.title }}
              <span v-if="item.year" class="sub-year">({{ item.year }})</span>
            </span>
            <v-chip v-if="item.status === 'ACTIVE'" color="success" size="small" variant="tonal">订阅中</v-chip>
            <v-chip v-else-if="item.status === 'COMPLETED'" color="info" size="small" variant="tonal">已完成</v-chip>
            <v-chip v-else color="warning" size="small" variant="tonal">已暂停</v-chip>
          </div>
          <div class="sub-meta">
            <span>{{ item.mediaType === 'MOVIE' ? '电影' : '剧集' }}</span>
            <span v-if="item.mediaType !== 'MOVIE'">S{{ item.season }}</span>
            <span>共 {{ item.totalEpisodes }} 集</span>
          </div>
          <div class="detail-row">
            <span class="label">上次命中</span>
            <span class="value">{{ item.lastMatchTime || '-' }}</span>
          </div>
          <div class="detail-row">
            <span class="label">自动补搜</span>
            <v-switch
              v-model="item.autoSearch"
              true-value="1"
              false-value="0"
              color="primary"
              density="compact"
              hide-details
              @click.stop
              @update:model-value="() => toggleAutoSearch(item)"
            />
          </div>
          <div class="sub-actions" @click.stop>
            <v-btn variant="text" color="primary" size="small" @click="showProgress(item)">进度</v-btn>
            <v-btn variant="text" color="primary" size="small" @click="goDownloadRecords(item)">下载记录</v-btn>
            <v-btn variant="text" color="info" size="small" @click="openActionDrawer(item)">···</v-btn>
          </div>
        </div>
      </div>

      <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无订阅" />
    </div>

    <!-- 操作抽屉 -->
    <v-bottom-sheet v-model="actionDrawerOpen" class="modern-drawer">
      <v-card v-if="actionDrawerTarget" title="更多操作">
        <v-card-text>
          <div class="drawer-actions">
            <v-btn v-if="actionDrawerTarget.status !== 'PAUSED'" color="warning" block @click="handlePause(actionDrawerTarget); actionDrawerOpen = false">暂停</v-btn>
            <v-btn v-else color="success" block @click="handleResume(actionDrawerTarget); actionDrawerOpen = false">恢复</v-btn>
            <v-btn color="primary" block @click="openSeasonSearch(actionDrawerTarget); actionDrawerOpen = false">搜索补齐</v-btn>
            <v-btn block @click="handleRefresh(actionDrawerTarget); actionDrawerOpen = false">对账</v-btn>
            <v-btn block @click="showSearchLogs(actionDrawerTarget); actionDrawerOpen = false">匹配日志</v-btn>
            <v-btn block @click="openFilterOverride(actionDrawerTarget); actionDrawerOpen = false">过滤规则</v-btn>
            <v-btn color="error" block @click="handleRemove(actionDrawerTarget); actionDrawerOpen = false">删除</v-btn>
          </div>
        </v-card-text>
      </v-card>
    </v-bottom-sheet>

    <!-- 批量操作 -->
    <div v-if="selectedIds.length > 0" class="batch-bar">
      <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
      <v-btn variant="text" color="warning" size="small" @click="handleBatchPause">批量暂停</v-btn>
      <v-btn variant="text" color="success" size="small" @click="handleBatchResume">批量恢复</v-btn>
      <v-btn variant="text" color="error" size="small" @click="handleDelete()">批量删除</v-btn>
      <v-btn variant="text" size="small" @click="selectedIds.length = 0">取消</v-btn>
    </div>

    <!-- 分页 -->
    <MobilePager
      v-model:page-size="queryParams.pageSize"
      :page-num="queryParams.pageNum"
      :total="total"
      :total-pages="totalPages"
      @prev="prevPage"
      @next="nextPage"
      @size-change="handleSizeChange"
    />

    <!-- 新增订阅：TMDb 选片 -->
    <v-dialog v-model="subscribeOpen" width="92%" class="modern-dialog">
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
              <span class="sub-year">（第 0 季是特别篇）</span>
            </template>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="subscribeOpen = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="subscribeLoading" :disabled="!picked" @click="confirmSubscribe">
            订阅
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 进度 -->
    <v-dialog v-model="progressOpen" width="90%" class="modern-dialog">
      <v-card title="订阅进度">
        <v-card-text>
          <v-progress-linear v-if="progressLoading" indeterminate color="primary" class="mb-3" />
          <template v-if="progress">
            <p class="progress-title">{{ progress.title }}</p>
            <v-progress-linear
              :model-value="progress.totalEpisodes ? Math.round((progress.inLibraryCount / progress.totalEpisodes) * 100) : 0"
              color="primary"
              height="8"
              rounded
              class="mb-2"
            />
            <p>已入库 <strong>{{ progress.inLibraryCount }}</strong> / {{ progress.totalEpisodes }} 集</p>
            <p v-if="progress.inFlightCount">在途 {{ progress.inFlightCount }} 集（已推送下载器，尚未入库）</p>
            <div v-if="progress.missingEpisodes && progress.missingEpisodes.length" class="missing-list">
              仍缺第
              <span v-for="(ep, idx) in progress.missingEpisodes" :key="ep" class="missing-item">
                <span v-if="idx > 0">、</span>{{ ep }}
                <v-btn
                  v-if="currentSubscription && currentSubscription.mediaType !== 'MOVIE'"
                  variant="text"
                  color="primary"
                  size="small"
                  @click="openEpisodeSearch(currentSubscription, ep)"
                >搜</v-btn>
              </span>
              集
            </div>
            <p v-else class="all-done">全部集已入库</p>
          </template>
        </v-card-text>
        <v-card-actions>
          <v-btn v-if="currentSubscription" color="primary" @click="openSeasonSearch(currentSubscription)">
            搜索补齐
          </v-btn>
          <v-spacer />
          <v-btn @click="progressOpen = false">关闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 搜索补集确认 -->
    <v-dialog v-model="searchDialogOpen" width="90%" class="modern-dialog">
      <v-card title="搜索补集">
        <v-card-text>
          <v-text-field v-model="searchDialogKeyword" label="关键词" placeholder="搜索关键词，可编辑后再搜" />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="searchDialogOpen = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="searchDialogLoading" @click="confirmSearch">搜索</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 匹配日志 -->
    <v-dialog v-model="searchLogOpen" width="92%" class="modern-dialog">
      <v-card title="匹配日志">
        <v-card-text>
          <v-progress-linear v-if="searchLogLoading" indeterminate color="primary" class="mb-2" />
          <div class="log-list">
            <div v-for="(log, idx) in searchLogs" :key="idx" class="log-item">
              <div class="log-top">
                <span class="log-time">{{ log.createTime }}</span>
                <v-chip size="small" :color="log.source === 'RSS' ? 'info' : 'primary'" variant="tonal">
                  {{ log.source === 'RSS' ? 'RSS轮询' : '搜索补集' }}
                </v-chip>
                <v-chip v-if="log.accepted === '1'" color="success" size="small" variant="tonal">通过</v-chip>
                <v-chip v-else color="error" size="small" variant="tonal">淘汰</v-chip>
              </div>
              <div class="log-title">{{ log.torrentTitle || '-' }}</div>
              <div v-if="log.reason" class="log-reason">{{ log.reason }}</div>
            </div>
            <v-empty-state v-if="!searchLogLoading && searchLogs.length === 0" icon="mdi-inbox-outline" title="暂无日志" />
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="searchLogOpen = false">关闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 过滤规则覆盖 -->
    <v-dialog v-model="filterOverrideOpen" width="94%" class="modern-dialog">
      <v-card title="过滤规则覆盖">
        <v-card-text>
          <p class="override-tip">只勾选需要覆盖的项，不勾选的沿用全局过滤规则。</p>
          <v-form>
            <div class="override-field">
              <span class="override-label">最低做种数</span>
              <div class="override-row">
                <v-checkbox v-model="filterOverrideForm.minSeeders.enabled" hide-details density="compact" />
                <v-text-field
                  v-model.number="filterOverrideForm.minSeeders.value"
                  type="number"
                  min="0"
                  :disabled="!filterOverrideForm.minSeeders.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
            </div>
            <div class="override-field">
              <span class="override-label">体积下限（GB）</span>
              <div class="override-row">
                <v-checkbox v-model="filterOverrideForm.minSize.enabled" hide-details density="compact" />
                <v-text-field
                  v-model.number="filterOverrideForm.minSize.value"
                  type="number"
                  min="0"
                  :disabled="!filterOverrideForm.minSize.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
            </div>
            <div class="override-field">
              <span class="override-label">体积上限（GB）</span>
              <div class="override-row">
                <v-checkbox v-model="filterOverrideForm.maxSize.enabled" hide-details density="compact" />
                <v-text-field
                  v-model.number="filterOverrideForm.maxSize.value"
                  type="number"
                  min="0"
                  :disabled="!filterOverrideForm.maxSize.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
            </div>
            <div class="override-field">
              <span class="override-label">仅要免费种</span>
              <div class="override-row">
                <v-checkbox v-model="filterOverrideForm.freeOnly.enabled" hide-details density="compact" />
                <v-radio-group v-model="filterOverrideForm.freeOnly.value" inline hide-details :disabled="!filterOverrideForm.freeOnly.enabled">
                  <v-radio label="否" value="0" />
                  <v-radio label="是" value="1" />
                </v-radio-group>
              </div>
            </div>
            <div class="override-field">
              <span class="override-label">分辨率白名单</span>
              <div class="override-row">
                <v-checkbox v-model="filterOverrideForm.resolutionWhitelist.enabled" hide-details density="compact" />
                <v-text-field
                  v-model="filterOverrideForm.resolutionWhitelist.value"
                  placeholder="如 2160p,1080p"
                  :disabled="!filterOverrideForm.resolutionWhitelist.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
            </div>
            <div class="override-field">
              <span class="override-label">标题包含词</span>
              <div class="override-row">
                <v-checkbox v-model="filterOverrideForm.includeKeywords.enabled" hide-details density="compact" />
                <v-text-field
                  v-model="filterOverrideForm.includeKeywords.value"
                  placeholder="逗号分隔，命中其一即可"
                  :disabled="!filterOverrideForm.includeKeywords.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
            </div>
            <div class="override-field">
              <span class="override-label">标题排除词</span>
              <div class="override-row">
                <v-checkbox v-model="filterOverrideForm.excludeKeywords.enabled" hide-details density="compact" />
                <v-text-field
                  v-model="filterOverrideForm.excludeKeywords.value"
                  placeholder="逗号分隔，命中任一即淘汰"
                  :disabled="!filterOverrideForm.excludeKeywords.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
            </div>
            <div class="override-field">
              <span class="override-label">分辨率优先级</span>
              <div class="override-row">
                <v-checkbox v-model="filterOverrideForm.resolutionPriority.enabled" hide-details density="compact" />
                <v-text-field
                  v-model="filterOverrideForm.resolutionPriority.value"
                  placeholder="如 2160p,1080p,720p"
                  :disabled="!filterOverrideForm.resolutionPriority.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
            </div>
            <div class="override-field">
              <span class="override-label">偏好体积（GB）</span>
              <div class="override-row">
                <v-checkbox v-model="filterOverrideForm.preferredSize.enabled" hide-details density="compact" />
                <v-text-field
                  v-model.number="filterOverrideForm.preferredSize.value"
                  type="number"
                  min="0"
                  :disabled="!filterOverrideForm.preferredSize.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
            </div>
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="filterOverrideOpen = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="filterOverrideSaving" @click="saveFilterOverride">保存</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtSubscription } from '@/composables/usePtSubscription'

const router = useRouter()

const {
  taskList, loading, total, queryParams,
  handleQuery, resetQuery,
  subscribeOpen, searchLoading, subscribeLoading, searchResults, searchForm,
  picked, pickedSeason, openSubscribeDialog, doSearch, pick, confirmSubscribe,
  progressOpen, progressLoading, progress, currentSubscription, showProgress,
  searchLogOpen, searchLogLoading, searchLogs, showSearchLogs,
  filterOverrideOpen, filterOverrideSaving, filterOverrideForm,
  openFilterOverride, saveFilterOverride,
  searchDialogOpen, searchDialogLoading, searchDialogKeyword,
  openSeasonSearch, openEpisodeSearch, confirmSearch, toggleAutoSearch,
  handleRefresh, handlePause, handleResume, handleRemove,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed,
  selectedIds, toggleSubSelect, handleBatchPause, handleBatchResume, handleDelete
} = usePtSubscription()

/** TMDb 海报路径拼完整图片地址，w200 宽度足够列表缩略图使用 */
const posterUrl = (path: string) => `https://image.tmdb.org/t/p/w200${path}`
/** 海报加载失败的订阅 id 集合，命中则展示占位图标而非裂图 */
const posterErrorIds = reactive(new Set<number>())

/** 操作抽屉状态 */
const actionDrawerOpen = ref(false)
const actionDrawerTarget = ref<any>(null)

const openActionDrawer = (row: any) => {
  actionDrawerTarget.value = row
  actionDrawerOpen.value = true
}

const goDownloadRecords = (row: any) => {
  router.push({ path: '/openlist/ptDownloadRecord', query: { subId: row.id } })
}
</script>

<style scoped lang="scss">
.mobile-page {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: calc(100vh - 120px);
  padding-bottom: 8px;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 200px;
  flex: 1;
}


.sub-card {
  display: flex;
  gap: 10px;
  background: var(--osr-surface);
  border-radius: var(--osr-radius-lg);
  padding: 12px;
  box-shadow: var(--osr-shadow-base);
  border: 2px solid transparent;
  transition: all var(--osr-transition-fast);

  &.selected {
    border-color: var(--osr-primary-light-5);
    background: var(--osr-primary-light-9);
  }

  &:active {
    transform: scale(0.99);
  }
}

.sub-poster {
  flex-shrink: 0;
  width: 60px;
  height: 90px;
  border-radius: var(--osr-radius-sm);
  overflow: hidden;
  background: var(--osr-bg-page);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }

  .sub-poster-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--osr-text-disabled);
    font-size: 20px;
  }
}

.sub-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.sub-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;

  .sub-name {
    flex: 1;
    min-width: 0;
    font-size: 14px;
    font-weight: 600;
    color: var(--osr-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    line-height: 1.4;
  }

  .sub-year {
    font-weight: 400;
    color: var(--osr-text-secondary);
    font-size: 12px;
  }
}

.sub-meta {
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: var(--osr-text-secondary);
}

.detail-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  line-height: 1.6;

  .label {
    flex-shrink: 0;
    width: 58px;
    color: var(--osr-text-secondary);
  }

  .value {
    flex: 1;
    min-width: 0;
    color: var(--osr-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.sub-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  margin-top: 2px;
  padding-top: 6px;
  border-top: 1px solid var(--osr-border-light);
}

.progress-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}

.all-done {
  color: var(--osr-success);
}

.missing-list {
  margin: 8px 0;
  line-height: 1.8;
  font-size: 13px;
}

.missing-item {
  display: inline-flex;
  align-items: center;
  margin-right: 4px;
}

.log-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 60vh;
  overflow-y: auto;
}

.log-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px;
  border-radius: var(--osr-radius-sm);
  background: var(--osr-bg-page);
}

.log-top {
  display: flex;
  align-items: center;
  gap: 6px;

  .log-time {
    flex: 1;
    min-width: 0;
    font-size: 11px;
    color: var(--osr-text-secondary);
  }
}

.log-title {
  font-size: 12px;
  color: var(--osr-text-primary);
  word-break: break-all;
}

.log-reason {
  font-size: 11px;
  color: var(--osr-error);
}

.override-tip {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.override-field {
  margin-bottom: 12px;
}

.override-label {
  display: block;
  font-size: 12px;
  color: var(--osr-text-secondary);
  margin-bottom: 4px;
}

.override-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;

  .v-selection-control {
    flex: none;
    min-height: auto;
  }

  > .v-text-field,
  > .v-radio-group {
    flex: 1;
  }
}

.drawer-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
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
    border-color: var(--osr-primary-light-5);
    background: var(--osr-primary-light-9);
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
</style>
