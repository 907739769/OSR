<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-bookmark-multiple-outline"
      title="PT 订阅"
      desc="按 TMDb 作品订阅，自动匹配 RSS 与补搜缺集"
    />

    <!-- 搜索 -->
    <v-card v-if="showSearch" class="search-card">
      <v-form @submit.prevent="handleQuery">
        <div class="search-fields">
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
            placeholder="类型"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            class="field-sm"
          />
          <v-select
            v-model="queryParams.status"
            :items="[{ title: '订阅中', value: 'ACTIVE' }, { title: '已完成', value: 'COMPLETED' }, { title: '已暂停', value: 'PAUSED' }]"
            label="状态"
            placeholder="状态"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            class="field-sm"
          />
          <div class="search-actions">
            <v-btn color="primary" prepend-icon="mdi-magnify" @click="handleQuery">搜索</v-btn>
            <v-btn variant="outlined" prepend-icon="mdi-refresh" @click="resetQuery">重置</v-btn>
          </div>
        </div>
      </v-form>
    </v-card>

    <!-- 列表 -->
    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="primary" prepend-icon="mdi-plus" @click="openSubscribeDialog">
            新增订阅
          </v-btn>
          <v-btn variant="text" @click="selectionMode = !selectionMode">
            {{ selectionMode ? '退出批量操作' : '批量操作' }}
          </v-btn>
        </div>
        <div class="action-right">
          <span class="sort-label">排序：</span>
          <v-select
            v-model="queryParams.sortBy"
            :items="[{ title: '默认（最新创建）', value: '' }, { title: '上次命中时间', value: 'lastMatchTime' }]"
            class="sort-select"
            placeholder="排序"
            density="compact"
            variant="outlined"
            hide-details
            style="width: 170px"
            @update:model-value="handleQuery"
          />
          <v-btn variant="text" prepend-icon="mdi-filter-outline" @click="showSearch = !showSearch">
            {{ showSearch ? '隐藏搜索' : '显示搜索' }}
          </v-btn>
        </div>
      </div>

      <div v-if="selectionMode" class="batch-toolbar">
        已选 {{ selectedIds.length }} 项
        <v-checkbox
          :model-value="isAllPageSelected"
          :indeterminate="isIndeterminate"
          density="compact"
          hide-details
          class="select-all-checkbox"
          label="全选本页"
          @update:model-value="(v: boolean | null) => toggleSelectAllPage(!!v)"
        />
        <v-btn variant="text" color="warning" size="small" class="batch-pause-btn" :disabled="!selectedIds.length" @click="handleBatchPause">批量暂停</v-btn>
        <v-btn variant="text" color="success" size="small" class="batch-resume-btn" :disabled="!selectedIds.length" @click="handleBatchResume">批量恢复</v-btn>
        <v-btn variant="text" color="error" size="small" class="batch-delete-btn" :disabled="!selectedIds.length" @click="handleDelete()">批量删除</v-btn>
        <v-btn variant="text" size="small" class="batch-cancel-btn" @click="selectionMode = false">取消</v-btn>
      </div>

      <div v-if="loading && taskList.length === 0" class="card-grid card-grid--wide">
        <div v-for="n in skeletonCount" :key="n" class="sub-card-skeleton">
          <v-skeleton-loader type="image" class="sub-card-skeleton__poster" width="72" height="108" />
          <div class="sub-card-skeleton__info">
            <v-skeleton-loader type="text" width="70%" class="mb-2" />
            <v-skeleton-loader type="text" width="50%" class="mb-2" />
            <v-skeleton-loader type="text" class="mb-2" />
            <v-skeleton-loader type="text" />
          </div>
        </div>
      </div>
      <div v-else class="card-grid card-grid--wide">
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <div v-for="item in taskList" :key="item.id" class="sub-card" :class="{ selectable: selectionMode }" @click="selectionMode && toggleSubSelect(item)">
          <v-checkbox-btn
            v-if="selectionMode"
            class="sub-card-checkbox"
            :model-value="isSubSelected(item.id)"
            @click.stop="toggleSubSelect(item)"
          />
          <div class="sub-poster">
            <img
              v-if="item.posterPath && !posterErrorIds.has(item.id)"
              :src="posterUrl(item.posterPath)"
              :alt="item.title"
              loading="lazy"
              @error="onPosterError(item.id)"
            />
            <div v-else class="sub-poster-placeholder" :class="item.mediaType === 'MOVIE' ? 'placeholder-movie' : 'placeholder-tv'">
              <v-icon :icon="item.mediaType === 'MOVIE' ? 'mdi-filmstrip' : 'mdi-television-play'" size="28" />
              <span class="placeholder-text">{{ item.mediaType === 'MOVIE' ? '电影' : '剧集' }}</span>
            </div>
          </div>
          <div class="sub-info">
            <div class="sub-header">
              <span class="sub-title" :title="item.title">
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
            <div class="sub-row">
              <span class="label">上次命中</span>
              <span class="value">{{ item.lastMatchTime || '-' }}</span>
            </div>
            <div class="sub-row">
              <span class="label">上次搜索</span>
              <span class="value">{{ item.lastSearchTime || '-' }}</span>
            </div>
            <div class="sub-row">
              <span class="label">自动补搜</span>
              <v-switch
                v-model="item.autoSearch"
                true-value="1"
                false-value="0"
                color="primary"
                density="compact"
                hide-details
                @update:model-value="() => toggleAutoSearch(item)"
              />
            </div>
            <div class="sub-actions">
              <v-btn variant="text" color="primary" size="small" @click="showProgress(item)">进度</v-btn>
              <v-btn variant="text" color="primary" size="small" @click="goDownloadRecords(item)">下载记录</v-btn>
              <v-btn v-if="item.status !== 'PAUSED'" variant="text" color="warning" size="small" @click="handlePause(item)">暂停</v-btn>
              <v-btn v-else variant="text" color="success" size="small" @click="handleResume(item)">恢复</v-btn>
              <v-btn variant="text" color="error" size="small" @click="handleRemove(item)">删除</v-btn>
              <v-menu eager>
                <template #activator="{ props: menuProps }">
                  <v-btn v-bind="menuProps" class="more-actions-trigger" variant="text" color="info" size="small" append-icon="mdi-chevron-down">更多</v-btn>
                </template>
                <v-list density="compact">
                  <v-list-item @click="handleMoreCommand('refresh', item)">对账</v-list-item>
                  <v-list-item @click="handleMoreCommand('logs', item)">匹配日志</v-list-item>
                  <v-list-item @click="handleMoreCommand('filter', item)">过滤规则</v-list-item>
                  <v-list-item @click="handleMoreCommand('search', item)">搜索补齐</v-list-item>
                </v-list>
              </v-menu>
            </div>
          </div>
        </div>
        <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无订阅" />
      </div>

      <div class="pagination-wrapper">
        <span class="total-text">共 {{ total }} 条</span>
        <v-select
          :model-value="queryParams.pageSize"
          :items="[12, 24, 48]"
          density="compact"
          variant="outlined"
          hide-details
          class="page-size-select"
          @update:model-value="(v: number) => { queryParams.pageSize = v; getList() }"
        />
        <v-pagination
          v-model="queryParams.pageNum"
          :length="Math.ceil(total / queryParams.pageSize) || 1"
          density="comfortable"
          @update:model-value="getList"
        />
      </div>
    </v-card>

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
              <v-icon v-else icon="mdi-image-outline" class="search-poster-placeholder" />
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
    <v-dialog v-model="progressOpen" max-width="600">
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
                <v-chip
                  size="small"
                  :color="episodeStateColor(ep.state)"
                  variant="tonal"
                >
                  {{ episodeStateLabel(ep.state) }}
                </v-chip>
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
        <v-card-actions>
          <v-btn v-if="currentSubscription" color="primary" @click="openSeasonSearch(currentSubscription)">
            搜索补齐
          </v-btn>
          <v-btn
            v-if="currentSubscription && progress && progress.missingEpisodes && progress.missingEpisodes.length > 1"
            color="success"
            :loading="searchAllMissingLoading"
            @click="handleSearchAllMissing"
          >
            一键补齐全部（{{ progress.missingEpisodes.length }}集）
          </v-btn>
          <v-spacer />
          <v-btn variant="outlined" @click="progressOpen = false">关闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 搜索补集确认 -->
    <v-dialog v-model="searchDialogOpen" max-width="480">
      <v-card title="搜索补集">
        <v-card-text>
          <v-text-field
            v-model="searchDialogKeyword"
            label="关键词"
            placeholder="搜索关键词，可编辑后再搜"
            class="mb-2"
          />
          <v-checkbox v-model="searchManualSelect" label="手动选择结果" hide-details />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="searchDialogOpen = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="searchDialogLoading" @click="confirmSearch">搜索</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 候选种子手动选择 -->
    <v-dialog v-model="candidateDialogOpen" max-width="900">
      <v-card title="选择候选种子">
        <v-card-text>
          <div v-if="candidates.length === 0" class="empty-tip">
            未搜索到匹配资源
          </div>
          <v-data-table
            v-else
            :items="candidates"
            :headers="candidateHeaders"
            height="420"
            fixed-header
            items-per-page="-1"
            hide-default-footer
            density="compact"
            class="modern-table"
            :row-props="(row: any) => ({ class: selectedCandidate === row.item ? 'row-selected' : '', style: 'cursor:pointer', onClick: () => (selectedCandidate = row.item) })"
          >
            <template #item.index="{ index }">{{ index + 1 }}</template>
            <template #item.target="{ item }">
              <v-chip v-if="item.parsedEpisode && item.parsedEpisodeEnd > item.parsedEpisode" size="small" color="warning" variant="tonal">
                第{{ item.parsedEpisode }}-{{ item.parsedEpisodeEnd }}集
              </v-chip>
              <v-chip v-else-if="item.parsedEpisode" size="small" color="warning" variant="tonal">第{{ item.parsedEpisode }}集</v-chip>
              <v-chip v-else size="small" color="success" variant="tonal">整季</v-chip>
            </template>
            <template #item.indexerName="{ item }">
              <v-chip size="small" color="info" variant="tonal">{{ item.indexerName }}</v-chip>
            </template>
            <template #item.resolution="{ item }">{{ item.resolution || '-' }}</template>
            <template #item.source="{ item }">{{ item.source || '-' }}</template>
            <template #item.size="{ item }">{{ formatSize(item.size) }}</template>
            <template #item.seeders="{ item }">
              <v-chip :color="item.seeders > 0 ? 'success' : 'error'" size="small" variant="tonal">{{ item.seeders }}</v-chip>
            </template>
            <template #item.free="{ item }">
              <v-chip v-if="item.free" color="warning" size="small" variant="tonal">免费</v-chip>
              <span v-else>-</span>
            </template>
          </v-data-table>
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
    <v-dialog v-model="searchLogOpen" max-width="600">
      <v-card title="匹配日志">
        <v-card-text>
          <v-data-table
            :loading="searchLogLoading"
            :items="searchLogs"
            :headers="searchLogHeaders"
            height="420"
            fixed-header
            items-per-page="-1"
            hide-default-footer
            density="compact"
            class="modern-table"
          >
            <template #item.source="{ item }">
              <StatusChip :type="item.source === 'RSS' ? 'info' : 'primary'" :text="item.source === 'RSS' ? 'RSS轮询' : '搜索补集'" />
            </template>
            <template #item.torrentTitle="{ item }">{{ item.torrentTitle || '-' }}</template>
            <template #item.accepted="{ item }">
              <StatusChip v-if="item.accepted === '1'" type="success" text="通过" />
              <StatusChip v-else type="error" text="淘汰" />
            </template>
            <template #item.reason="{ item }">{{ item.reason || '-' }}</template>
          </v-data-table>
          <v-empty-state v-if="!searchLogLoading && searchLogs.length === 0" icon="mdi-inbox-outline" title="暂无日志" text="还没轮询/搜索过，或该订阅日志已被清理" />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="searchLogOpen = false">关闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 过滤规则覆盖 -->
    <v-dialog v-model="filterOverrideOpen" max-width="600">
      <v-card title="过滤规则覆盖">
        <v-card-text>
          <p class="override-tip">只勾选需要覆盖的项，不勾选的沿用全局过滤规则（PT过滤规则页配置的）。</p>
          <v-form>
            <div class="override-row">
              <span class="override-label">最低做种数</span>
              <v-checkbox v-model="filterOverrideForm.minSeeders.enabled" hide-details density="compact" class="override-checkbox" />
              <v-text-field
                v-model.number="filterOverrideForm.minSeeders.value"
                type="number"
                min="0"
                :disabled="!filterOverrideForm.minSeeders.enabled"
                density="compact"
                variant="outlined"
                hide-details
                style="width: 200px"
              />
            </div>
            <div class="override-row">
              <span class="override-label">体积下限</span>
              <v-checkbox v-model="filterOverrideForm.minSize.enabled" hide-details density="compact" class="override-checkbox" />
              <v-text-field
                v-model.number="filterOverrideForm.minSize.value"
                type="number"
                min="0"
                max="999"
                :disabled="!filterOverrideForm.minSize.enabled"
                density="compact"
                variant="outlined"
                hide-details
                style="width: 160px"
              />
              <span class="form-tip">GB</span>
            </div>
            <div class="override-row">
              <span class="override-label">体积上限</span>
              <v-checkbox v-model="filterOverrideForm.maxSize.enabled" hide-details density="compact" class="override-checkbox" />
              <v-text-field
                v-model.number="filterOverrideForm.maxSize.value"
                type="number"
                min="0"
                max="999"
                :disabled="!filterOverrideForm.maxSize.enabled"
                density="compact"
                variant="outlined"
                hide-details
                style="width: 160px"
              />
              <span class="form-tip">GB</span>
            </div>
            <div class="override-row">
              <span class="override-label">仅要免费种</span>
              <v-checkbox v-model="filterOverrideForm.freeOnly.enabled" hide-details density="compact" class="override-checkbox" />
              <v-radio-group v-model="filterOverrideForm.freeOnly.value" inline hide-details :disabled="!filterOverrideForm.freeOnly.enabled">
                <v-radio label="否" value="0" />
                <v-radio label="是" value="1" />
              </v-radio-group>
            </div>
            <div class="override-row">
              <span class="override-label">外语电影需中字</span>
              <v-checkbox v-model="filterOverrideForm.requireChineseSubtitle.enabled" hide-details density="compact" class="override-checkbox" />
              <v-radio-group v-model="filterOverrideForm.requireChineseSubtitle.value" inline hide-details :disabled="!filterOverrideForm.requireChineseSubtitle.enabled">
                <v-radio label="否" value="0" />
                <v-radio label="是" value="1" />
              </v-radio-group>
            </div>
            <div class="override-row">
              <span class="override-label">分辨率白名单</span>
              <v-checkbox v-model="filterOverrideForm.resolutionWhitelist.enabled" hide-details density="compact" class="override-checkbox" />
              <v-text-field
                v-model="filterOverrideForm.resolutionWhitelist.value"
                placeholder="如 2160p,1080p"
                :disabled="!filterOverrideForm.resolutionWhitelist.enabled"
                density="compact"
                variant="outlined"
                hide-details
                class="override-input"
              />
            </div>
            <div class="override-row">
              <span class="override-label">标题包含词</span>
              <v-checkbox v-model="filterOverrideForm.includeKeywords.enabled" hide-details density="compact" class="override-checkbox" />
              <v-text-field
                v-model="filterOverrideForm.includeKeywords.value"
                placeholder="逗号分隔，命中其一即可"
                :disabled="!filterOverrideForm.includeKeywords.enabled"
                density="compact"
                variant="outlined"
                hide-details
                class="override-input"
              />
            </div>
            <div class="override-row">
              <span class="override-label">标题排除词</span>
              <v-checkbox v-model="filterOverrideForm.excludeKeywords.enabled" hide-details density="compact" class="override-checkbox" />
              <v-text-field
                v-model="filterOverrideForm.excludeKeywords.value"
                placeholder="逗号分隔，命中任一即淘汰"
                :disabled="!filterOverrideForm.excludeKeywords.enabled"
                density="compact"
                variant="outlined"
                hide-details
                class="override-input"
              />
            </div>
            <div class="override-row">
              <span class="override-label">分辨率优先级</span>
              <v-checkbox v-model="filterOverrideForm.resolutionPriority.enabled" hide-details density="compact" class="override-checkbox" />
              <v-text-field
                v-model="filterOverrideForm.resolutionPriority.value"
                placeholder="如 2160p,1080p,720p"
                :disabled="!filterOverrideForm.resolutionPriority.enabled"
                density="compact"
                variant="outlined"
                hide-details
                class="override-input"
              />
            </div>
            <div class="override-row">
              <span class="override-label">偏好体积</span>
              <v-checkbox v-model="filterOverrideForm.preferredSize.enabled" hide-details density="compact" class="override-checkbox" />
              <v-text-field
                v-model.number="filterOverrideForm.preferredSize.value"
                type="number"
                min="0"
                max="999"
                :disabled="!filterOverrideForm.preferredSize.enabled"
                density="compact"
                variant="outlined"
                hide-details
                style="width: 160px"
              />
              <span class="form-tip">GB</span>
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
import StatusChip from '@/components/StatusChip.vue'
import PageHeader from '@/components/PageHeader.vue'
import { ref, reactive, watch, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { usePtSubscription } from '@/composables/usePtSubscription'

const router = useRouter()
const route = useRoute()
const showSearch = ref(window.innerWidth >= 768)
/** 海报加载失败的订阅 id 集合，命中则展示占位图标而非裂图；数据刷新后清除以便重试 */
const posterErrorIds = reactive(new Set<number>())

const onPosterError = (id: number) => { posterErrorIds.add(id) }

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery,
  subscribeOpen, searchLoading, subscribeLoading, searchResults, searchForm,
  picked, pickedSeason, openSubscribeDialog, doSearch, pick, confirmSubscribe,
  progressOpen, progressLoading, progress, currentSubscription, showProgress, showProgressById,
  episodeDetailOpen, episodeDetailLoading, episodeDetail, resettingEpisode,
  loadEpisodeDetail, handleResetEpisode, episodeStateLabel, episodeStateColor,
  searchLogOpen, searchLogLoading, searchLogs, showSearchLogs,
  filterOverrideOpen, filterOverrideSaving, filterOverrideForm,
  openFilterOverride, saveFilterOverride,
  searchDialogOpen, searchDialogLoading, searchDialogKeyword, searchManualSelect,
  openSeasonSearch, openEpisodeSearch, confirmSearch, toggleAutoSearch,
  handleRefresh, handlePause, handleResume, handleRemove, handleDelete,
  selectedIds, selectionMode, toggleSubSelect, isSubSelected,
  handleBatchPause, handleBatchResume,
  isAllPageSelected, isIndeterminate, toggleSelectAllPage,
  searchAllMissingLoading, handleSearchAllMissing,
  candidateDialogOpen, candidates, pushingSelected, pushSelectedCandidate, formatSize
} = usePtSubscription()

// 列表数据变化（刷新/翻页/重新查询）后清除海报失败集合，海报恢复或 TMDb 修复后能重新加载
watch(taskList, () => posterErrorIds.clear())

/** 候选种子表格中当前高亮的行 */
const selectedCandidate = ref<any>(null)

/** TMDb 海报路径拼完整图片地址，w200 宽度足够列表缩略图使用 */
const posterUrl = (path: string) => `https://image.tmdb.org/t/p/w200${path}`

const skeletonCount = ref(6)

function updateSkeletonCount() {
  const cardMinWidth = 340 + 14
  const containerWidth = window.innerWidth - 32 - 32
  skeletonCount.value = Math.max(3, Math.min(12, Math.floor(containerWidth / cardMinWidth)))
}

onMounted(() => {
  updateSkeletonCount()
  window.addEventListener('resize', updateSkeletonCount)
  const subId = Number(route.query.id)
  if (subId) showProgressById(subId)
})
onUnmounted(() => { window.removeEventListener('resize', updateSkeletonCount) })

const goDownloadRecords = (row: any) => {
  router.push({ path: '/openlist/ptDownloadRecord', query: { subId: row.id } })
}

/** "更多"下拉菜单 command → 现有函数的分发，纯路由不新增业务逻辑 */
const handleMoreCommand = (cmd: string, row: any) => {
  switch (cmd) {
    case 'refresh': handleRefresh(row); break
    case 'logs': showSearchLogs(row); break
    case 'filter': openFilterOverride(row); break
    case 'search': openSeasonSearch(row); break
  }
}

const searchHeaders = [
  { title: '海报', key: 'poster', sortable: false, width: 70, align: 'center' as const },
  { title: '标题', key: 'title', sortable: false, minWidth: '200' },
  { title: '年份', key: 'year', sortable: false, width: 80, align: 'center' as const },
  { title: 'TMDb ID', key: 'tmdbId', sortable: false, width: 100, align: 'center' as const }
]

const candidateHeaders = [
  { title: '#', key: 'index', sortable: false, width: 48, align: 'center' as const },
  { title: '目标', key: 'target', sortable: false, width: 70, align: 'center' as const },
  { title: '来源', key: 'indexerName', sortable: false, width: 100 },
  { title: '标题', key: 'title', sortable: false, minWidth: '280' },
  { title: '分辨率', key: 'resolution', sortable: false, width: 80, align: 'center' as const },
  { title: '来源', key: 'source', sortable: false, width: 80, align: 'center' as const },
  { title: '体积', key: 'size', sortable: false, width: 100, align: 'end' as const },
  { title: '做种', key: 'seeders', sortable: false, width: 70, align: 'center' as const },
  { title: '免费', key: 'free', sortable: false, width: 60, align: 'center' as const }
]

const searchLogHeaders = [
  { title: '时间', key: 'createTime', sortable: false, width: 160 },
  { title: '来源', key: 'source', sortable: false, width: 90 },
  { title: '种子标题', key: 'torrentTitle', sortable: false, minWidth: '200' },
  { title: '结果', key: 'accepted', sortable: false, width: 80 },
  { title: '原因', key: 'reason', sortable: false, minWidth: '180' }
]
</script>

<style scoped lang="scss">
.select-all-checkbox {
  margin-left: 4px;
  font-size: 13px;

  :deep(.v-selection-control) {
    min-height: auto;
  }
}

/* ============================================
   订阅卡片网格（带海报）
   ============================================ */

.sub-card {
  position: relative;
  display: flex;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
  transition: box-shadow var(--osr-transition-fast), border-color var(--osr-transition-fast);

  &:hover {
    box-shadow: var(--osr-shadow-md);
    border-color: var(--osr-border-base);
  }

  &.selectable {
    cursor: pointer;
    &:hover {
      border-color: var(--osr-primary-accent);
    }
  }
}

.sub-card-checkbox {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 1;
}

.sub-card-skeleton {
  display: flex;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);

  &__poster {
    flex-shrink: 0;
    border-radius: var(--osr-radius-sm);
    overflow: hidden;
  }

  &__info {
    flex: 1;
    min-width: 0;
    padding-top: 2px;
  }
}

.sub-poster {
  flex-shrink: 0;
  width: 72px;
  height: 108px;
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
    gap: 4px;
    color: var(--osr-text-disabled);
    font-size: 22px;

    /* 下面两组渐变是刻意的装饰色：海报占位在明暗主题下都保持深底白字，
       与主题色无关，因此不走 --osr-* 令牌 */
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
      font-size: 11px;
      font-weight: 500;
      letter-spacing: 1px;
    }
  }
}

.sub-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sub-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;

  .sub-title {
    flex: 1;
    min-width: 0;
    font-size: 15px;
    font-weight: 600;
    color: var(--osr-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    line-height: 1.4;
  }
}

.sub-meta {
  display: flex;
  gap: 10px;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.sub-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;

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
  margin-top: auto;
  padding-top: 6px;
  border-top: 1px solid var(--osr-border-light);
}

.pagination-wrapper {
  .total-text {
    font-size: 13px;
    color: var(--osr-text-secondary);
    white-space: nowrap;
  }

  .page-size-select {
    width: 90px;
    flex: none;
  }
}

.sort-label {
  font-size: 13px;
  color: var(--osr-text-secondary);
  white-space: nowrap;
}

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
}

.progress-title {
  margin: 0 0 12px;
  font-size: 16px;
  font-weight: 600;
}

.all-done {
  color: var(--osr-success);
}

.missing-list {
  margin: 8px 0;
  line-height: 1.8;
}

.missing-item {
  display: inline-flex;
  align-items: center;
  margin-right: 4px;
}

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
  max-height: 240px;
  overflow-y: auto;
}

.episode-detail-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px solid var(--osr-border-light);

  .ep-num {
    width: 60px;
    flex-shrink: 0;
    color: var(--osr-text-primary);
  }
}

.override-tip {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.override-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.override-label {
  width: 120px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--osr-text-secondary);
}

.override-checkbox {
  flex: none;

  :deep(.v-selection-control) {
    min-height: auto;
  }
}

.override-input {
  flex: 1;
  min-width: 160px;
}

.form-tip {
  font-size: 13px;
  color: var(--osr-text-secondary);
}

.empty-tip {
  text-align: center;
  padding: 40px;
  color: var(--osr-text-secondary);
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

:deep(.row-selected) {
  background: var(--osr-primary-subtle);
}
</style>
