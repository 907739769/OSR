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
        hide-details
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
        hide-details
      />
      <v-select
        v-model="queryParams.status"
        :items="[{ title: '订阅中', value: 'ACTIVE' }, { title: '已完成', value: 'COMPLETED' }, { title: '已暂停', value: 'PAUSED' }]"
        label="状态"
        placeholder="全部状态"
        clearable
        density="compact"
        variant="outlined"
        hide-details
      />
      <v-select
        v-model="queryParams.sortBy"
        :items="[{ title: '默认（最新创建）', value: '' }, { title: '上次命中时间', value: 'lastMatchTime' }]"
        label="排序"
        placeholder="排序"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        @update:model-value="handleQuery"
      />
    </MobileSearchPanel>

    <!-- 新增 FAB -->
    <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="mdi-plus" @click="openSubscribeDialog">
      新增
    </v-btn>

    <!-- 批量选择开关：与 PC 一致，不开启时点卡片不会误选 -->
    <div class="list-toolbar">
      <v-btn variant="text" size="small" @click="toggleSelectionMode">
        {{ selectionMode ? '退出批量操作' : '批量操作' }}
      </v-btn>
    </div>

    <!-- 批量操作 -->
    <div v-if="selectionMode" class="batch-bar">
      <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
      <v-btn variant="text" color="warning" size="small" :disabled="!selectedIds.length" @click="handleBatchPause">批量暂停</v-btn>
      <v-btn variant="text" color="success" size="small" :disabled="!selectedIds.length" @click="handleBatchResume">批量恢复</v-btn>
      <v-btn variant="text" color="error" size="small" :disabled="!selectedIds.length" @click="handleDelete()">批量删除</v-btn>
      <v-btn variant="text" size="small" class="batch-select-all-btn" @click="toggleSelectAllPage(!isAllPageSelected)">
        {{ isAllPageSelected ? '取消全选' : '全选' }}
      </v-btn>
      <v-btn variant="text" size="small" @click="toggleSelectionMode">取消</v-btn>
    </div>

    <!-- 列表 -->
    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <v-card
        v-for="item in taskList"
        :key="item.id"
        class="task-card"
        :class="{ selected: selectionMode && isSubSelected(item.id) }"
        @click="selectionMode && toggleSubSelect(item)"
      >
        <div class="card-checkbox" v-if="selectionMode">
          <v-checkbox
            :model-value="isSubSelected(item.id)"
            density="compact"
            hide-details
            @click.stop="toggleSubSelect(item)"
          />
        </div>
        <div class="sub-poster">
          <img
            v-if="item.posterPath && !posterErrorIds.has(item.id)"
            :src="posterUrl(item.posterPath)"
            :alt="item.title"
            loading="lazy"
            @error="posterErrorIds.add(item.id)"
          />
          <div
            v-else
            class="sub-poster-placeholder"
            :class="item.mediaType === 'MOVIE' ? 'placeholder-movie' : 'placeholder-tv'"
          >
            <v-icon :icon="item.mediaType === 'MOVIE' ? 'mdi-filmstrip' : 'mdi-television-play'" size="22" />
            <span class="placeholder-text">{{ item.mediaType === 'MOVIE' ? '电影' : '剧集' }}</span>
          </div>
        </div>
        <div class="card-content">
          <div class="card-top">
            <span class="card-title">
              {{ item.title }}
              <span v-if="item.year" class="sub-year">({{ item.year }})</span>
            </span>
            <StatusChip v-if="item.status === 'ACTIVE'" type="success" text="订阅中" />
            <StatusChip v-else-if="item.status === 'COMPLETED'" type="info" text="已完成" />
            <StatusChip v-else type="warning" text="已暂停" />
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
            <span class="label">上次搜索</span>
            <span class="value">{{ item.lastSearchTime || '-' }}</span>
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
          <div class="detail-row">
            <span class="label">洗版</span>
            <v-switch
              v-model="item.upgradeEnabled"
              true-value="1"
              false-value="0"
              color="primary"
              density="compact"
              hide-details
              @click.stop
              @update:model-value="() => toggleUpgrade(item)"
            />
          </div>
          <div class="card-actions" @click.stop>
            <v-btn variant="text" color="primary" size="small" @click="showProgress(item)">进度</v-btn>
            <v-btn variant="text" color="primary" size="small" @click="goDownloadRecords(item)">下载记录</v-btn>
            <v-btn class="action-more" variant="text" color="default" size="small" icon="mdi-dots-horizontal" @click="openActionDrawer(item)" />
          </div>
        </div>
      </v-card>

      <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无订阅" />
    </div>

    <!-- 操作抽屉 -->
    <v-bottom-sheet v-model="actionDrawerOpen">
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

    <!-- 分页 -->
    <MobilePager
      v-model:page-size="queryParams.pageSize"
      :page-sizes="[12, 24, 48]"
      :page-num="queryParams.pageNum"
      :total="total"
      :total-pages="totalPages"
      @prev="prevPage"
      @next="nextPage"
      @size-change="handleSizeChange"
    />

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

    <!-- 进度 -->
    <v-dialog v-model="progressOpen" width="92%">
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

            <div class="episode-detail-toggle" @click="loadEpisodeDetail">
              {{ episodeDetailOpen ? '收起全部集' : '查看全部集' }}
              <v-icon icon="mdi-chevron-down" :class="{ 'is-open': episodeDetailOpen }" size="16" />
            </div>
            <div v-if="episodeDetailOpen" class="episode-detail-list">
              <v-progress-linear v-if="episodeDetailLoading" indeterminate color="primary" />
              <div v-for="ep in episodeDetail" :key="ep.episode" class="episode-detail-row">
                <span class="ep-num">第{{ ep.episode }}集</span>
                <StatusChip :type="episodeStateColor(ep.state)" :text="episodeStateLabel(ep.state)" />
                <span v-if="qualityLabel(ep)" class="ep-quality" :title="upgradeStateHint(ep)">
                  {{ qualityLabel(ep) }}
                </span>
                <v-btn
                  v-if="ep.state === 'IN_LIBRARY' || ep.state === 'BLOCKED'"
                  variant="text"
                  color="warning"
                  size="small"
                  :loading="resettingEpisode === ep.episode"
                  @click="handleResetEpisode(ep)"
                >重置</v-btn>
              </div>
              <v-empty-state v-if="!episodeDetailLoading && episodeDetail.length === 0" icon="mdi-inbox-outline" title="暂无数据" />
            </div>
          </template>
        </v-card-text>
        <v-card-actions class="progress-actions">
          <v-btn v-if="currentSubscription" color="primary" size="small" @click="openSeasonSearch(currentSubscription)">
            搜索补齐
          </v-btn>
          <v-btn
            v-if="currentSubscription && progress && progress.missingEpisodes && progress.missingEpisodes.length > 1"
            color="success"
            size="small"
            :loading="searchAllMissingLoading"
            @click="handleSearchAllMissing"
          >
            一键补齐（{{ progress.missingEpisodes.length }}集）
          </v-btn>
          <v-spacer />
          <v-btn variant="outlined" size="small" @click="progressOpen = false">关闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 搜索补集确认 -->
    <v-dialog v-model="searchDialogOpen" width="92%">
      <v-card title="搜索补集">
        <v-card-text>
          <v-text-field v-model="searchDialogKeyword" label="关键词" placeholder="搜索关键词，可编辑后再搜" class="mb-2" />
          <v-checkbox v-model="searchManualSelect" label="手动选择结果" density="compact" hide-details />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="searchDialogOpen = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="searchDialogLoading" @click="confirmSearch">搜索</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 候选种子手动选择：PC 用表格，移动端改成可点选的卡片列表 -->
    <v-dialog v-model="candidateDialogOpen" width="92%">
      <v-card title="选择候选种子">
        <v-card-text>
          <v-empty-state v-if="candidates.length === 0" icon="mdi-inbox-outline" title="未搜索到匹配资源" />
          <div v-else class="candidate-list">
            <div
              v-for="(cand, idx) in candidates"
              :key="idx"
              class="candidate-card"
              :class="{ selected: selectedCandidate === cand }"
              @click="selectedCandidate = cand"
            >
              <div class="candidate-title">{{ cand.title }}</div>
              <div class="candidate-tags">
                <v-chip v-if="cand.parsedEpisode && cand.parsedEpisodeEnd > cand.parsedEpisode" size="x-small" color="warning" variant="tonal">
                  第{{ cand.parsedEpisode }}-{{ cand.parsedEpisodeEnd }}集
                </v-chip>
                <v-chip v-else-if="cand.parsedEpisode" size="x-small" color="warning" variant="tonal">第{{ cand.parsedEpisode }}集</v-chip>
                <v-chip v-else size="x-small" color="success" variant="tonal">整季</v-chip>
                <v-chip size="x-small" color="info" variant="tonal">{{ cand.indexerName }}</v-chip>
                <v-chip v-if="cand.free" size="x-small" color="warning" variant="tonal">免费</v-chip>
                <v-chip size="x-small" :color="cand.seeders > 0 ? 'success' : 'error'" variant="tonal">
                  {{ cand.seeders }} 做种
                </v-chip>
              </div>
              <div class="candidate-meta">
                {{ cand.resolution || '-' }} · {{ cand.source || '-' }} · {{ formatSize(cand.size) }}
              </div>
            </div>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="candidateDialogOpen = false">取消</v-btn>
          <v-btn
            color="primary"
            variant="flat"
            :loading="pushingSelected"
            :disabled="!selectedCandidate"
            @click="pushSelectedCandidate(selectedCandidate)"
          >
            下载选中版本
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 匹配日志 -->
    <v-dialog v-model="searchLogOpen" width="92%">
      <v-card title="匹配日志">
        <v-card-text>
          <v-progress-linear v-if="searchLogLoading" indeterminate color="primary" class="mb-2" />
          <div class="log-list">
            <div v-for="(log, idx) in searchLogs" :key="idx" class="log-item">
              <div class="log-top">
                <span class="log-time">{{ log.createTime }}</span>
                <StatusChip :type="log.source === 'RSS' ? 'info' : 'primary'" :text="log.source === 'RSS' ? 'RSS轮询' : '搜索补集'" />
                <StatusChip v-if="log.accepted === '1'" type="success" text="通过" />
                <StatusChip v-else type="error" text="淘汰" />
              </div>
              <div class="log-title">{{ log.torrentTitle || '-' }}</div>
              <div v-if="log.reason" class="log-reason">{{ log.reason }}</div>
            </div>
            <v-empty-state v-if="!searchLogLoading && searchLogs.length === 0" icon="mdi-inbox-outline" title="暂无日志" />
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="searchLogOpen = false">关闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 过滤规则覆盖 -->
    <v-dialog v-model="filterOverrideOpen" width="92%">
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
              <span class="override-label">外语电影需中字</span>
              <div class="override-row">
                <v-checkbox v-model="filterOverrideForm.requireChineseSubtitle.enabled" hide-details density="compact" />
                <v-radio-group v-model="filterOverrideForm.requireChineseSubtitle.value" inline hide-details :disabled="!filterOverrideForm.requireChineseSubtitle.enabled">
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
          <v-btn variant="outlined" @click="filterOverrideOpen = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="filterOverrideSaving" @click="saveFilterOverride">保存</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import StatusChip from '@/components/StatusChip.vue'
import { usePtSubscription } from '@/composables/usePtSubscription'

const route = useRoute()
const router = useRouter()

const {
  taskList, loading, total, queryParams,
  handleQuery, resetQuery,
  subscribeOpen, searchLoading, subscribeLoading, searchResults, searchForm,
  picked, pickedSeason, openSubscribeDialog, doSearch, pick, confirmSubscribe,
  progressOpen, progressLoading, progress, currentSubscription, showProgress, showProgressById,
  episodeDetailOpen, episodeDetailLoading, episodeDetail, resettingEpisode,
  loadEpisodeDetail, handleResetEpisode, episodeStateLabel, episodeStateColor,
  qualityLabel, upgradeStateHint,
  searchLogOpen, searchLogLoading, searchLogs, showSearchLogs,
  filterOverrideOpen, filterOverrideSaving, filterOverrideForm,
  openFilterOverride, saveFilterOverride,
  searchDialogOpen, searchDialogLoading, searchDialogKeyword, searchManualSelect,
  openSeasonSearch, openEpisodeSearch, confirmSearch, toggleAutoSearch, toggleUpgrade,
  candidateDialogOpen, candidates, pushingSelected, pushSelectedCandidate, formatSize,
  searchAllMissingLoading, handleSearchAllMissing,
  handleRefresh, handlePause, handleResume, handleRemove,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed,
  selectionMode, isAllPageSelected, toggleSelectAllPage,
  selectedIds, toggleSubSelect, isSubSelected, handleBatchPause, handleBatchResume, handleDelete
} = usePtSubscription()

/** 候选种子弹窗里当前选中的那一条 */
const selectedCandidate = ref<any>(null)
watch(candidateDialogOpen, (open) => {
  if (open) selectedCandidate.value = null
})

/** 退出批量模式时清空已选，避免下次进入还残留上次的选择 */
const toggleSelectionMode = () => {
  selectionMode.value = !selectionMode.value
  if (!selectionMode.value) selectedIds.value = []
}

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

// 从下载记录页点订阅名跳过来时带 ?id=，直接弹出该订阅的进度（与 PC 端一致）
onMounted(() => {
  const subId = Number(route.query.id)
  if (subId) showProgressById(subId)
})
</script>

<style scoped lang="scss">
.list-toolbar {
  display: flex;
  justify-content: flex-end;
}

/* ---- 进度弹窗：每集明细 ---- */
.episode-detail-toggle {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px solid var(--osr-border-light);
  font-size: 13px;
  color: var(--osr-primary);
  cursor: pointer;

  .v-icon {
    transition: transform var(--osr-transition-fast);

    &.is-open {
      transform: rotate(180deg);
    }
  }
}

.episode-detail-list {
  margin-top: 8px;
  max-height: 44vh;
  overflow-y: auto;
}

.episode-detail-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px solid var(--osr-border-light);

  .ep-quality {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.ep-num {
    width: 60px;
    flex-shrink: 0;
    color: var(--osr-text-primary);
  }
}

.progress-actions {
  flex-wrap: wrap;
  gap: 6px;
}

/* ---- 候选种子选择 ---- */
.candidate-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 50vh;
  overflow-y: auto;
}

.candidate-card {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 10px;
  border-radius: var(--osr-radius-sm);
  border: 2px solid transparent;
  background: var(--osr-bg-page);
  cursor: pointer;

  &.selected {
    border-color: var(--osr-primary-accent);
    background: var(--osr-primary-subtle);
  }

  .candidate-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--osr-text-primary);
    line-height: 1.4;
    word-break: break-all;
  }

  .candidate-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  .candidate-meta {
    font-size: 11px;
    color: var(--osr-text-secondary);
  }
}

.card-title .sub-year {
  font-weight: 400;
  color: var(--osr-text-secondary);
  font-size: 12px;
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
    border-color: var(--osr-primary-accent);
    background: var(--osr-primary-subtle);
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
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    color: var(--osr-text-disabled);
    font-size: 20px;

    /* 与 PC 端一致的装饰渐变：明暗主题下都是深底白字，刻意不走 --osr-* 令牌 */
    &.placeholder-movie {
      background:
        radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.14), transparent 45%),
        linear-gradient(135deg, #1e3a5f 0%, #2d5a87 50%, #1e3a5f 100%);
      color: rgba(255, 255, 255, 0.7);
    }

    &.placeholder-tv {
      background:
        radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.14), transparent 45%),
        linear-gradient(135deg, #3b1f47 0%, #6b3a7a 50%, #3b1f47 100%);
      color: rgba(255, 255, 255, 0.7);
    }

    .placeholder-text {
      font-size: 10px;
      font-weight: 500;
      letter-spacing: 1px;
    }
  }
}

.sub-meta {
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: var(--osr-text-secondary);
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
  color: rgb(var(--v-theme-error));
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
</style>
