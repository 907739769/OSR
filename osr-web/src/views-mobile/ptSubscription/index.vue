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
        :items="sortOptions"
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
      <!-- 缺集体检回答的是「这一格为什么还是灰的」，与 PC 端页头那个入口对齐 -->
      <v-btn v-if="healthPath" variant="text" size="small" prepend-icon="mdi-stethoscope" @click="goHealth">
        缺集体检
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
            <!-- 被单独配过过滤规则的订阅要看得出来，否则只能逐条打开弹窗才知道 -->
            <v-chip
              v-if="hasFilterOverride(item)"
              class="sub-flag"
              size="x-small"
              color="info"
              variant="tonal"
              prepend-icon="mdi-filter-cog-outline"
            >过滤覆盖</v-chip>
          </div>
          <!-- 入库进度：列表接口已带进度计数，不必逐条点开进度弹窗才知道还缺几集 -->
          <div v-if="item.inLibraryCount !== undefined && item.inLibraryCount !== null" class="sub-progress">
            <v-progress-linear
              :model-value="progressPercent(item)"
              :color="progressColor(item)"
              height="6"
              rounded
            />
            <span class="sub-progress-text">
              {{ item.inLibraryCount }}/{{ item.totalEpisodes }}
              <span v-if="item.inFlightCount" class="sub-progress-inflight">· 在途 {{ item.inFlightCount }}</span>
            </span>
          </div>
          <div class="detail-row">
            <span class="label">上次命中</span>
            <span class="value">{{ item.lastMatchTime || '-' }}</span>
          </div>
          <div class="detail-row">
            <span class="label">上次搜索</span>
            <span class="value">{{ item.lastSearchTime || '-' }}</span>
          </div>
          <!-- 两个开关并作一行：各占一行只为两个布尔值，能吃掉卡片近三分之一的高度 -->
          <div class="sub-switches" @click.stop>
            <div class="sub-switch">
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
            <div class="sub-switch">
              <span class="label">洗版</span>
              <v-switch
                v-model="item.upgradeEnabled"
                true-value="1"
                false-value="0"
                color="primary"
                density="compact"
                hide-details
                @update:model-value="() => toggleUpgrade(item)"
              />
            </div>
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

    <!-- 进度 -->
    <!-- 补齐跑批期间锁住弹窗：关掉它循环也不会停 -->
    <v-dialog v-model="progressOpen" width="92%" :persistent="searchAllMissingLoading">
      <v-card title="订阅进度">
        <v-card-text>
          <v-progress-linear v-if="progressLoading" indeterminate color="primary" class="mb-3" />
          <template v-if="progress">
            <p class="progress-title">
              {{ progress.title }}
              <!-- 不带季号的话，同一部剧的两条订阅弹出来的进度长得一模一样 -->
              <span v-if="seasonLabel(currentSubscription)" class="progress-season">
                {{ seasonLabel(currentSubscription) }}
              </span>
            </p>
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
              <span class="missing-lead">仍缺 {{ progress.missingEpisodes.length }} 集：</span>
              <!-- 集号本身就是搜索入口。原先每个集号后面挂一个「搜」按钮，
                   一季上百集时等于在弹窗里铺上百个按钮组件 -->
              <button
                v-for="ep in visibleMissingEpisodes"
                :key="ep"
                type="button"
                class="missing-item"
                :class="{ 'missing-item--clickable': currentSubscription && currentSubscription.mediaType !== 'MOVIE' }"
                :disabled="!currentSubscription || currentSubscription.mediaType === 'MOVIE'"
                @click="openEpisodeSearch(currentSubscription, ep)"
              >{{ ep }}</button>
              <button v-if="missingHiddenCount > 0" type="button" class="missing-more" @click="expandMissing">
                还有 {{ missingHiddenCount }} 集，全部展开
              </button>
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
                <!-- 未播出的集恒为「缺失」，标出来能省掉一整轮「为什么搜不到」的排查 -->
                <span v-if="episodeAirDate(ep)" class="ep-date">
                  {{ episodeAirDate(ep) }}
                  <span v-if="episodeUnaired(ep)" class="ep-unaired">未播出</span>
                </span>
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
          <template v-if="searchAllMissingLoading">
            <!-- 跑批期间只留进度与中止：每集要等一次几十秒的检索，一个光转圈的按钮说不清还要多久 -->
            <span class="batch-search-progress">
              补齐中 {{ searchAllMissingDone }}/{{ searchAllMissingTotal }}
            </span>
            <v-btn variant="text" color="error" size="small" :disabled="searchAllMissingAborted" @click="abortSearchAllMissing">
              {{ searchAllMissingAborted ? '正在停止…' : '停止' }}
            </v-btn>
          </template>
          <template v-else>
            <v-btn v-if="currentSubscription" color="primary" size="small" @click="openSeasonSearch(currentSubscription)">
              搜索补齐
            </v-btn>
            <v-btn
              v-if="currentSubscription && progress && progress.missingEpisodes && progress.missingEpisodes.length > 1"
              color="success"
              size="small"
              @click="handleSearchAllMissing"
            >
              一键补齐（{{ progress.missingEpisodes.length }}集）
            </v-btn>
          </template>
          <v-spacer />
          <v-btn variant="outlined" size="small" :disabled="searchAllMissingLoading" @click="progressOpen = false">关闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 搜索补集确认 -->
    <v-dialog v-model="searchDialogOpen" width="92%">
      <v-card title="搜索补集">
        <v-card-text>
          <v-text-field v-model="searchDialogKeyword" label="关键词" placeholder="搜索关键词，可编辑后再搜" class="mb-2" />
          <v-checkbox-btn v-model="searchManualSelect" label="手动选择结果" />
          <p class="field-hint">
            {{ searchManualSelect
              ? '搜完列出全部候选种子，由你挑一个推送下载。'
              : '搜完按过滤规则与优先级自动挑一个推送下载，不再询问。' }}
          </p>
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
          <div class="log-toolbar">
            <!-- 翻这张表基本只为了找「这一轮为什么没抓到」，通过的记录会把淘汰原因冲散 -->
            <v-checkbox-btn v-model="searchLogRejectedOnly" label="只看淘汰" />
            <span class="log-count">共 {{ searchLogs.length }} / 显示 {{ visibleSearchLogs.length }}</span>
          </div>
          <div class="log-list">
            <div v-for="(log, idx) in visibleSearchLogs" :key="idx" class="log-item">
              <div class="log-top">
                <span class="log-time">{{ log.createTime }}</span>
                <StatusChip :type="log.source === 'RSS' ? 'info' : 'primary'" :text="log.source === 'RSS' ? 'RSS轮询' : '搜索补集'" />
                <StatusChip v-if="log.accepted === '1'" type="success" text="通过" />
                <StatusChip v-else type="error" text="淘汰" />
              </div>
              <div class="log-title">{{ log.torrentTitle || '-' }}</div>
              <div v-if="log.reason" class="log-reason">{{ log.reason }}</div>
            </div>
            <v-empty-state
              v-if="!searchLogLoading && visibleSearchLogs.length === 0"
              icon="mdi-inbox-outline"
              :title="searchLogRejectedOnly && searchLogs.length ? '没有被淘汰的记录' : '暂无日志'"
            />
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
      <v-card :title="filterOverrideCount ? `过滤规则覆盖（已覆盖 ${filterOverrideCount} 项）` : '过滤规则覆盖'">
        <v-card-text>
          <p class="override-tip">
            只勾选需要覆盖的项，不勾选的沿用「PT 过滤规则」页的全局配置——每项下方的灰字就是当前的全局取值。
          </p>
          <v-form>
            <div class="override-field">
              <span class="override-label">最低做种数</span>
              <div class="override-row">
                <v-checkbox-btn v-model="filterOverrideForm.minSeeders.enabled" />
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
              <span v-if="globalFilterHint('minSeeders')" class="override-global">{{ globalFilterHint('minSeeders') }}</span>
            </div>
            <div class="override-field">
              <span class="override-label">体积下限（GB）</span>
              <div class="override-row">
                <v-checkbox-btn v-model="filterOverrideForm.minSize.enabled" />
                <v-text-field
                  v-model.number="filterOverrideForm.minSize.value"
                  type="number"
                  min="0"
                  step="0.01"
                  :disabled="!filterOverrideForm.minSize.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
              <span v-if="globalFilterHint('minSize')" class="override-global">{{ globalFilterHint('minSize') }}</span>
            </div>
            <div class="override-field">
              <span class="override-label">体积上限（GB）</span>
              <div class="override-row">
                <v-checkbox-btn v-model="filterOverrideForm.maxSize.enabled" />
                <v-text-field
                  v-model.number="filterOverrideForm.maxSize.value"
                  type="number"
                  min="0"
                  step="0.01"
                  :disabled="!filterOverrideForm.maxSize.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
              <span v-if="globalFilterHint('maxSize')" class="override-global">{{ globalFilterHint('maxSize') }}</span>
            </div>
            <div class="override-field">
              <span class="override-label">仅要免费种</span>
              <div class="override-row">
                <v-checkbox-btn v-model="filterOverrideForm.freeOnly.enabled" />
                <v-radio-group v-model="filterOverrideForm.freeOnly.value" inline hide-details :disabled="!filterOverrideForm.freeOnly.enabled">
                  <v-radio label="否" value="0" />
                  <v-radio label="是" value="1" />
                </v-radio-group>
              </div>
              <span v-if="globalFilterHint('freeOnly')" class="override-global">{{ globalFilterHint('freeOnly') }}</span>
            </div>
            <div class="override-field">
              <span class="override-label">外语电影需中字</span>
              <div class="override-row">
                <v-checkbox-btn v-model="filterOverrideForm.requireChineseSubtitle.enabled" />
                <v-radio-group v-model="filterOverrideForm.requireChineseSubtitle.value" inline hide-details :disabled="!filterOverrideForm.requireChineseSubtitle.enabled">
                  <v-radio label="否" value="0" />
                  <v-radio label="是" value="1" />
                </v-radio-group>
              </div>
              <span v-if="globalFilterHint('requireChineseSubtitle')" class="override-global">{{ globalFilterHint('requireChineseSubtitle') }}</span>
            </div>
            <div class="override-field">
              <span class="override-label">分辨率白名单</span>
              <div class="override-row">
                <v-checkbox-btn v-model="filterOverrideForm.resolutionWhitelist.enabled" />
                <v-text-field
                  v-model="filterOverrideForm.resolutionWhitelist.value"
                  placeholder="如 2160p,1080p"
                  :disabled="!filterOverrideForm.resolutionWhitelist.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
              <span v-if="globalFilterHint('resolutionWhitelist')" class="override-global">{{ globalFilterHint('resolutionWhitelist') }}</span>
            </div>
            <div class="override-field">
              <span class="override-label">标题包含词</span>
              <div class="override-row">
                <v-checkbox-btn v-model="filterOverrideForm.includeKeywords.enabled" />
                <v-text-field
                  v-model="filterOverrideForm.includeKeywords.value"
                  placeholder="逗号分隔，命中其一即可"
                  :disabled="!filterOverrideForm.includeKeywords.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
              <span v-if="globalFilterHint('includeKeywords')" class="override-global">{{ globalFilterHint('includeKeywords') }}</span>
            </div>
            <div class="override-field">
              <span class="override-label">标题排除词</span>
              <div class="override-row">
                <v-checkbox-btn v-model="filterOverrideForm.excludeKeywords.enabled" />
                <v-text-field
                  v-model="filterOverrideForm.excludeKeywords.value"
                  placeholder="逗号分隔，命中任一即淘汰"
                  :disabled="!filterOverrideForm.excludeKeywords.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
              <span v-if="globalFilterHint('excludeKeywords')" class="override-global">{{ globalFilterHint('excludeKeywords') }}</span>
            </div>
            <div class="override-field">
              <span class="override-label">描述排除词</span>
              <div class="override-row">
                <v-checkbox-btn v-model="filterOverrideForm.descriptionExcludeKeywords.enabled" />
                <v-text-field
                  v-model="filterOverrideForm.descriptionExcludeKeywords.value"
                  placeholder="如 原盘,BDMV；匹配描述而非标题"
                  :disabled="!filterOverrideForm.descriptionExcludeKeywords.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
              <span v-if="globalFilterHint('descriptionExcludeKeywords')" class="override-global">{{ globalFilterHint('descriptionExcludeKeywords') }}</span>
            </div>
            <div class="override-field">
              <span class="override-label">分辨率优先级</span>
              <div class="override-row">
                <v-checkbox-btn v-model="filterOverrideForm.resolutionPriority.enabled" />
                <v-text-field
                  v-model="filterOverrideForm.resolutionPriority.value"
                  placeholder="如 2160p,1080p,720p"
                  :disabled="!filterOverrideForm.resolutionPriority.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
              <span v-if="globalFilterHint('resolutionPriority')" class="override-global">{{ globalFilterHint('resolutionPriority') }}</span>
            </div>
            <div class="override-field">
              <span class="override-label">偏好体积（GB）</span>
              <div class="override-row">
                <v-checkbox-btn v-model="filterOverrideForm.preferredSize.enabled" />
                <v-text-field
                  v-model.number="filterOverrideForm.preferredSize.value"
                  type="number"
                  min="0"
                  step="0.01"
                  :disabled="!filterOverrideForm.preferredSize.enabled"
                  density="compact"
                  variant="outlined"
                  hide-details
                />
              </div>
              <span v-if="globalFilterHint('preferredSize')" class="override-global">{{ globalFilterHint('preferredSize') }}</span>
            </div>
          </v-form>
        </v-card-text>
        <v-card-actions>
          <!-- 「退回全局」是很常见的一次性意图，逐个取消 11 个勾选太啰嗦 -->
          <v-btn variant="text" size="small" :disabled="!filterOverrideCount" @click="clearFilterOverride">全部清除</v-btn>
          <v-spacer />
          <v-btn variant="outlined" @click="filterOverrideOpen = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="filterOverrideSaving" @click="saveFilterOverride">保存</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getRoutePathForComponent } from '@/router'
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
  pickedSeasonEpisodeCount, pickedSeasonCountLoading,
  progressOpen, progressLoading, progress, currentSubscription, showProgress, showProgressById,
  visibleMissingEpisodes, missingHiddenCount, expandMissing,
  episodeDetailOpen, episodeDetailLoading, episodeDetail, resettingEpisode,
  loadEpisodeDetail, handleResetEpisode, episodeStateLabel, episodeStateColor,
  qualityLabel, upgradeStateHint, seasonLabel, episodeAirDate, episodeUnaired,
  searchLogOpen, searchLogLoading, searchLogs, showSearchLogs,
  searchLogRejectedOnly, visibleSearchLogs,
  filterOverrideOpen, filterOverrideSaving, filterOverrideForm,
  openFilterOverride, saveFilterOverride,
  globalFilterHint, clearFilterOverride, filterOverrideCount,
  searchDialogOpen, searchDialogLoading, searchDialogKeyword, searchManualSelect,
  openSeasonSearch, openEpisodeSearch, confirmSearch, toggleAutoSearch, toggleUpgrade,
  candidateDialogOpen, candidates, pushingSelected, pushSelectedCandidate, formatSize,
  searchAllMissingLoading, handleSearchAllMissing,
  searchAllMissingDone, searchAllMissingTotal, searchAllMissingAborted, abortSearchAllMissing,
  handleRefresh, handlePause, handleResume, handleRemove,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed,
  selectionMode, toggleSelectionMode, isAllPageSelected, toggleSelectAllPage,
  selectedIds, toggleSubSelect, isSubSelected, handleBatchPause, handleBatchResume, handleDelete
} = usePtSubscription()

/** 排序档位，与 PC 端同一份取值。缺集数排序需要 ORDER BY 子查询，暂未提供 */
const sortOptions = [
  { title: '默认（最新创建）', value: '' },
  { title: '上次命中时间', value: 'lastMatchTime' },
  { title: '上次搜索时间', value: 'lastSearchTime' },
  { title: '标题', value: 'title' }
]

/** 候选种子弹窗里当前选中的那一条 */
const selectedCandidate = ref<any>(null)
watch(candidateDialogOpen, (open) => {
  if (open) selectedCandidate.value = null
})

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
  const path = getRoutePathForComponent('openlist/ptDownloadRecord/index')
  if (path) router.push({ path, query: { subId: row.id } })
}

/** 缺集体检页入口。菜单没授权时反查不到，按钮整个不渲染 */
const healthPath = computed(() => getRoutePathForComponent('openlist/ptHealth/index'))
const goHealth = () => {
  if (healthPath.value) router.push({ path: healthPath.value })
}

/** 该订阅是否配了自己的过滤规则覆盖。空字符串同样算「没有覆盖」，与后端一致 */
const hasFilterOverride = (row: any) => {
  if (!row?.filterOverride) return false
  try {
    return Object.keys(JSON.parse(row.filterOverride) || {}).length > 0
  } catch {
    // 脏数据不该让整个列表炸掉，也不该谎报成「有覆盖」
    return false
  }
}

const progressPercent = (row: any) => {
  const total = Number(row?.totalEpisodes) || 0
  if (!total) return 0
  return Math.round(((Number(row.inLibraryCount) || 0) / total) * 100)
}

/** 满了用成功色，其余用主色——进度条本身不是告警，缺集是常态 */
const progressColor = (row: any) => (progressPercent(row) >= 100 ? 'success' : 'primary')

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
  /* 移动端宽度有限，多出播出日期后允许换行，否则日期会把质量摘要挤没 */
  flex-wrap: wrap;
  gap: 6px 10px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px solid var(--osr-border-light);

  .ep-date {
    flex-shrink: 0;
    font-size: 12px;
    font-variant-numeric: tabular-nums;
    color: var(--osr-text-secondary);
  }

  .ep-unaired {
    margin-left: 4px;
    color: var(--osr-text-disabled);
  }

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
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 11px;
  color: var(--osr-text-secondary);
}

.sub-flag {
  /* chip 自带的高度在这一行里偏大，压到与旁边的文字同高 */
  height: 18px;
}

.sub-progress {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sub-progress-text {
  flex-shrink: 0;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-secondary);
}

.sub-progress-inflight {
  color: var(--osr-primary);
}

/* 两个开关并作一行 */
.sub-switches {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
}

.sub-switch {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;

  .label {
    color: var(--osr-text-secondary);
    white-space: nowrap;
  }
}

.progress-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}

.progress-season {
  margin-left: 6px;
  font-size: 12px;
  font-weight: 400;
  color: var(--osr-text-secondary);
}

.all-done {
  color: var(--osr-success);
}

/* 缺集串：集号本身就是搜索入口，不再每个集号后面挂一个按钮组件 */
.missing-list {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin: 8px 0;
  font-size: 13px;
}

.missing-lead {
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.missing-item {
  min-width: 30px;
  padding: 2px 6px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-sm);
  background: transparent;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-primary);
  cursor: default;

  &.missing-item--clickable {
    cursor: pointer;
  }
}

.missing-more {
  padding: 2px 6px;
  border: none;
  background: transparent;
  font-size: 12px;
  color: var(--osr-primary);
  cursor: pointer;
}

/* 补齐跑批时的「3/26」 */
.batch-search-progress {
  padding: 0 8px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-secondary);
}

.log-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.log-count {
  font-size: 11px;
  color: var(--osr-text-secondary);
}

.field-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--osr-text-secondary);
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

/* 全局取值参照：勾上覆盖那一刻用户得知道自己在把多少改成多少 */
.override-global {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--osr-text-disabled);
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

.picked-season-hint {
  margin-left: 4px;
  font-size: 12px;
  color: var(--osr-primary);
}
</style>
