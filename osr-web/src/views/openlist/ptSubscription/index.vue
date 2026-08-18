<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-bookmark-multiple-outline"
      title="PT 订阅"
      desc="按 TMDb 作品订阅，自动匹配 RSS 与补搜缺集"
    >
      <template #actions>
        <!-- 缺集体检回答的是「这一格为什么还是灰的」，是这一页的天然去处。
             放页头而不是每张卡片的「更多」里：体检页按分档列全部订阅，不支持按单条订阅过滤，
             挂成行操作会点进去看到一整页别的订阅 -->
        <v-btn v-if="healthPath" variant="text" prepend-icon="mdi-stethoscope" @click="goHealth">
          缺集体检
        </v-btn>
      </template>
    </PageHeader>

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
          <v-btn variant="text" @click="toggleSelectionMode">
            {{ selectionMode ? '退出批量操作' : '批量操作' }}
          </v-btn>
        </div>
        <div class="action-right">
          <span class="sort-label">排序：</span>
          <v-select
            v-model="queryParams.sortBy"
            :items="sortOptions"
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
        <v-btn variant="text" color="warning" size="small" class="batch-pause-btn" :disabled="!selectedIds.length" @click="handleBatchPause">批量暂停</v-btn>
        <v-btn variant="text" color="success" size="small" class="batch-resume-btn" :disabled="!selectedIds.length" @click="handleBatchResume">批量恢复</v-btn>
        <v-btn variant="text" color="error" size="small" class="batch-delete-btn" :disabled="!selectedIds.length" @click="handleDelete()">批量删除</v-btn>
        <v-btn variant="text" size="small" class="batch-select-all-btn" @click="toggleSelectAllPage(!isAllPageSelected)">
          {{ isAllPageSelected ? '取消全选' : '全选' }}
        </v-btn>
        <v-btn variant="text" size="small" class="batch-cancel-btn" @click="toggleSelectionMode">取消</v-btn>
      </div>

      <div v-if="loading && taskList.length === 0" class="card-grid card-grid--wide" ref="gridRef">
        <div v-for="n in skeletonCount" :key="n" class="item-card-skeleton">
          <v-skeleton-loader type="image" class="item-card-skeleton__poster" width="72" height="108" />
          <div class="item-card-skeleton__info">
            <v-skeleton-loader type="text" width="70%" class="mb-2" />
            <v-skeleton-loader type="text" width="50%" class="mb-2" />
            <v-skeleton-loader type="text" class="mb-2" />
            <v-skeleton-loader type="text" />
          </div>
        </div>
      </div>
      <div v-else class="card-grid card-grid--wide" ref="gridRef">
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <div
          v-for="item in taskList"
          :key="item.id"
          class="item-card item-card--compact"
          :class="{ 'item-card--selectable': selectionMode }"
          @click="selectionMode && toggleSubSelect(item)"
        >
          <v-checkbox-btn
            v-if="selectionMode"
            class="item-card-checkbox card-checkbox"
            :model-value="isSubSelected(item.id)"
            @click.stop="toggleSubSelect(item)"
          />
          <!-- 海报 + 信息横排。item-card 本身是纵向的，横排是订阅卡特有的，包一层私有容器 -->
          <div class="sub-main">
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
              <div class="card-header card-header--top">
                <span class="card-title card-title--clamp2" :title="item.title">
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
                <!-- 被单独配过过滤规则/下载器的订阅要看得出来，否则只能逐条打开弹窗才知道 -->
                <v-chip
                  v-if="hasFilterOverride(item)"
                  class="sub-flag"
                  size="x-small"
                  color="info"
                  variant="tonal"
                  prepend-icon="mdi-filter-cog-outline"
                  title="该订阅有自己的过滤规则覆盖，未覆盖的项仍沿用全局配置"
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
              <div class="card-row">
                <span class="label">上次命中</span>
                <span class="value">{{ item.lastMatchTime || '-' }}</span>
              </div>
              <div class="card-row">
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
            </div>
          </div>
          <!-- @click.stop：批量模式下卡片本身是可点选的，点操作按钮不该顺手把卡片也选上 -->
          <div class="card-footer" @click.stop>
            <v-btn variant="text" color="primary" size="small" @click="showProgress(item)">进度</v-btn>
            <v-btn variant="text" color="primary" size="small" @click="goDownloadRecords(item)">下载记录</v-btn>
            <v-menu>
              <template #activator="{ props: menuProps }">
                <v-btn v-bind="menuProps" class="more-actions-trigger" variant="text" color="info" size="small" append-icon="mdi-chevron-down">更多</v-btn>
              </template>
              <v-list density="compact">
                <v-list-item v-if="item.status !== 'PAUSED'" @click="handleMoreCommand('pause', item)">暂停</v-list-item>
                <v-list-item v-else @click="handleMoreCommand('resume', item)">恢复</v-list-item>
                <v-list-item @click="handleMoreCommand('search', item)">搜索补齐</v-list-item>
                <v-list-item @click="handleMoreCommand('refresh', item)">对账</v-list-item>
                <v-list-item @click="handleMoreCommand('logs', item)">匹配日志</v-list-item>
                <v-list-item @click="handleMoreCommand('filter', item)">过滤规则</v-list-item>
                <v-divider class="my-1" />
                <!-- 删除置底并单独分隔：它会连带删掉集数追踪记录，与「进度」这类高频动作并排太容易误点 -->
                <v-list-item class="more-actions-danger" @click="handleMoreCommand('remove', item)">删除</v-list-item>
              </v-list>
            </v-menu>
          </div>
        </div>
        <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无订阅" />
      </div>

      <div class="pagination-wrapper">
        <span class="total-text">共 {{ total }} 条</span>
        <v-select
          :model-value="queryParams.pageSize"
          :items="pageSizeOptions"
          density="compact"
          variant="outlined"
          hide-details
          class="page-size-select"
          @update:model-value="setPageSize"
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
    <!-- 补齐跑批期间锁住弹窗：关掉它循环也不会停，而 currentSubscription 一换，
         界面上就再也看不到这轮跑到哪了 -->
    <v-dialog v-model="progressOpen" max-width="600" :persistent="searchAllMissingLoading">
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
              <!-- 集号本身就是搜索入口。原先每个集号后面挂一个「搜」按钮，一季上百集时
                   等于在弹窗里铺上百个按钮组件 -->
              <button
                v-for="ep in visibleMissingEpisodes"
                :key="ep"
                type="button"
                class="missing-item"
                :class="{ 'missing-item--clickable': currentSubscription && currentSubscription.mediaType !== 'MOVIE' }"
                :title="currentSubscription && currentSubscription.mediaType !== 'MOVIE' ? `搜索第 ${ep} 集` : ''"
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
                <v-chip
                  size="small"
                  :color="episodeStateColor(ep.state)"
                  variant="tonal"
                >
                  {{ episodeStateLabel(ep.state) }}
                </v-chip>
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
        <v-card-actions>
          <template v-if="searchAllMissingLoading">
            <!-- 跑批期间只留进度与中止：每集要等一次几十秒的检索，几十集就是十几分钟，
                 一个光转圈的按钮说不清还要多久 -->
            <span class="batch-search-progress">
              补齐中 {{ searchAllMissingDone }}/{{ searchAllMissingTotal }}
            </span>
            <v-btn
              variant="text"
              color="error"
              :disabled="searchAllMissingAborted"
              title="当前这一集搜完后停下，不会打断已发出的请求"
              @click="abortSearchAllMissing"
            >
              {{ searchAllMissingAborted ? '正在停止…' : '停止' }}
            </v-btn>
          </template>
          <template v-else>
            <v-btn v-if="currentSubscription" color="primary" @click="openSeasonSearch(currentSubscription)">
              搜索补齐
            </v-btn>
            <v-btn
              v-if="currentSubscription && progress && progress.missingEpisodes && progress.missingEpisodes.length > 1"
              color="success"
              @click="handleSearchAllMissing"
            >
              一键补齐全部（{{ progress.missingEpisodes.length }}集）
            </v-btn>
          </template>
          <v-spacer />
          <v-btn variant="outlined" :disabled="searchAllMissingLoading" @click="progressOpen = false">关闭</v-btn>
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
          <div class="log-toolbar">
            <!-- 翻这张表基本只为了找「这一轮为什么没抓到」，通过的记录会把淘汰原因冲散 -->
            <v-checkbox-btn v-model="searchLogRejectedOnly" label="只看淘汰" />
            <span class="log-count">共 {{ searchLogs.length }} 条，显示 {{ visibleSearchLogs.length }} 条</span>
          </div>
          <v-data-table
            :loading="searchLogLoading"
            :items="visibleSearchLogs"
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
          <v-empty-state
            v-if="!searchLogLoading && visibleSearchLogs.length === 0"
            icon="mdi-inbox-outline"
            :title="searchLogRejectedOnly && searchLogs.length ? '没有被淘汰的记录' : '暂无日志'"
            :text="searchLogRejectedOnly && searchLogs.length ? '最近 100 条里每一条都通过了过滤' : '还没轮询/搜索过，或该订阅日志已被清理'"
          />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="searchLogOpen = false">关闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 过滤规则覆盖 -->
    <v-dialog v-model="filterOverrideOpen" max-width="600">
      <v-card :title="filterOverrideCount ? `过滤规则覆盖（已覆盖 ${filterOverrideCount} 项）` : '过滤规则覆盖'">
        <v-card-text>
          <p class="override-tip">
            勾选左侧方框才会覆盖该项，未勾选的沿用「PT 过滤规则」页的全局配置——每行末尾的灰字就是当前的全局取值。
          </p>
          <v-form>
            <div class="override-row">
              <v-checkbox-btn v-model="filterOverrideForm.minSeeders.enabled" class="override-checkbox" />
              <span class="override-label">最低做种数</span>
              <v-text-field
                v-model.number="filterOverrideForm.minSeeders.value"
                type="number"
                min="0"
                :disabled="!filterOverrideForm.minSeeders.enabled"
                density="compact"
                variant="outlined"
                hide-details
                class="override-input"
              />
              <span v-if="globalFilterHint('minSeeders')" class="override-global">{{ globalFilterHint('minSeeders') }}</span>
            </div>
            <div class="override-row">
              <v-checkbox-btn v-model="filterOverrideForm.minSize.enabled" class="override-checkbox" />
              <span class="override-label">体积下限</span>
              <v-text-field
                v-model.number="filterOverrideForm.minSize.value"
                type="number"
                min="0"
                step="0.01"
                max="999"
                suffix="GB"
                :disabled="!filterOverrideForm.minSize.enabled"
                density="compact"
                variant="outlined"
                hide-details
                class="override-input"
              />
              <span v-if="globalFilterHint('minSize')" class="override-global">{{ globalFilterHint('minSize') }}</span>
            </div>
            <div class="override-row">
              <v-checkbox-btn v-model="filterOverrideForm.maxSize.enabled" class="override-checkbox" />
              <span class="override-label">体积上限</span>
              <v-text-field
                v-model.number="filterOverrideForm.maxSize.value"
                type="number"
                min="0"
                step="0.01"
                max="999"
                suffix="GB"
                :disabled="!filterOverrideForm.maxSize.enabled"
                density="compact"
                variant="outlined"
                hide-details
                class="override-input"
              />
              <span v-if="globalFilterHint('maxSize')" class="override-global">{{ globalFilterHint('maxSize') }}</span>
            </div>
            <div class="override-row">
              <v-checkbox-btn v-model="filterOverrideForm.freeOnly.enabled" class="override-checkbox" />
              <span class="override-label">仅要免费种</span>
              <v-radio-group v-model="filterOverrideForm.freeOnly.value" inline hide-details :disabled="!filterOverrideForm.freeOnly.enabled">
                <v-radio label="否" value="0" />
                <v-radio label="是" value="1" />
              </v-radio-group>
              <span v-if="globalFilterHint('freeOnly')" class="override-global">{{ globalFilterHint('freeOnly') }}</span>
            </div>
            <div class="override-row">
              <v-checkbox-btn v-model="filterOverrideForm.requireChineseSubtitle.enabled" class="override-checkbox" />
              <span class="override-label">外语电影需中字</span>
              <v-radio-group v-model="filterOverrideForm.requireChineseSubtitle.value" inline hide-details :disabled="!filterOverrideForm.requireChineseSubtitle.enabled">
                <v-radio label="否" value="0" />
                <v-radio label="是" value="1" />
              </v-radio-group>
              <span v-if="globalFilterHint('requireChineseSubtitle')" class="override-global">{{ globalFilterHint('requireChineseSubtitle') }}</span>
            </div>
            <div class="override-row">
              <v-checkbox-btn v-model="filterOverrideForm.resolutionWhitelist.enabled" class="override-checkbox" />
              <span class="override-label">分辨率白名单</span>
              <v-text-field
                v-model="filterOverrideForm.resolutionWhitelist.value"
                placeholder="如 2160p,1080p"
                :disabled="!filterOverrideForm.resolutionWhitelist.enabled"
                density="compact"
                variant="outlined"
                hide-details
                class="override-input"
              />
              <span v-if="globalFilterHint('resolutionWhitelist')" class="override-global">{{ globalFilterHint('resolutionWhitelist') }}</span>
            </div>
            <div class="override-row">
              <v-checkbox-btn v-model="filterOverrideForm.includeKeywords.enabled" class="override-checkbox" />
              <span class="override-label">标题包含词</span>
              <v-text-field
                v-model="filterOverrideForm.includeKeywords.value"
                placeholder="逗号分隔，命中其一即可"
                :disabled="!filterOverrideForm.includeKeywords.enabled"
                density="compact"
                variant="outlined"
                hide-details
                class="override-input"
              />
              <span v-if="globalFilterHint('includeKeywords')" class="override-global">{{ globalFilterHint('includeKeywords') }}</span>
            </div>
            <div class="override-row">
              <v-checkbox-btn v-model="filterOverrideForm.excludeKeywords.enabled" class="override-checkbox" />
              <span class="override-label">标题排除词</span>
              <v-text-field
                v-model="filterOverrideForm.excludeKeywords.value"
                placeholder="逗号分隔，命中任一即淘汰"
                :disabled="!filterOverrideForm.excludeKeywords.enabled"
                density="compact"
                variant="outlined"
                hide-details
                class="override-input"
              />
              <span v-if="globalFilterHint('excludeKeywords')" class="override-global">{{ globalFilterHint('excludeKeywords') }}</span>
            </div>
            <div class="override-row">
              <v-checkbox-btn v-model="filterOverrideForm.descriptionExcludeKeywords.enabled" class="override-checkbox" />
              <span class="override-label">描述排除词</span>
              <v-text-field
                v-model="filterOverrideForm.descriptionExcludeKeywords.value"
                placeholder="如 原盘,BDMV；匹配描述而非标题"
                :disabled="!filterOverrideForm.descriptionExcludeKeywords.enabled"
                density="compact"
                variant="outlined"
                hide-details
                class="override-input"
              />
              <span v-if="globalFilterHint('descriptionExcludeKeywords')" class="override-global">{{ globalFilterHint('descriptionExcludeKeywords') }}</span>
            </div>
            <div class="override-row">
              <v-checkbox-btn v-model="filterOverrideForm.resolutionPriority.enabled" class="override-checkbox" />
              <span class="override-label">分辨率优先级</span>
              <v-text-field
                v-model="filterOverrideForm.resolutionPriority.value"
                placeholder="如 2160p,1080p,720p"
                :disabled="!filterOverrideForm.resolutionPriority.enabled"
                density="compact"
                variant="outlined"
                hide-details
                class="override-input"
              />
              <span v-if="globalFilterHint('resolutionPriority')" class="override-global">{{ globalFilterHint('resolutionPriority') }}</span>
            </div>
            <div class="override-row">
              <v-checkbox-btn v-model="filterOverrideForm.preferredSize.enabled" class="override-checkbox" />
              <span class="override-label">偏好体积</span>
              <v-text-field
                v-model.number="filterOverrideForm.preferredSize.value"
                type="number"
                min="0"
                step="0.01"
                max="999"
                suffix="GB"
                :disabled="!filterOverrideForm.preferredSize.enabled"
                density="compact"
                variant="outlined"
                hide-details
                class="override-input"
              />
              <span v-if="globalFilterHint('preferredSize')" class="override-global">{{ globalFilterHint('preferredSize') }}</span>
            </div>
          </v-form>
        </v-card-text>
        <v-card-actions>
          <!-- 「退回全局」是很常见的一次性意图，逐个取消 11 个勾选太啰嗦 -->
          <v-btn variant="text" :disabled="!filterOverrideCount" @click="clearFilterOverride">全部清除</v-btn>
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
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getRoutePathForComponent } from '@/router'
import { usePtSubscription } from '@/composables/usePtSubscription'
import { useGridPageSize } from '@/composables/useGridPageSize'

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
  handleRefresh, handlePause, handleResume, handleRemove, handleDelete,
  selectedIds, selectionMode, toggleSelectionMode, toggleSubSelect, isSubSelected,
  handleBatchPause, handleBatchResume,
  isAllPageSelected, toggleSelectAllPage,
  searchAllMissingLoading, handleSearchAllMissing,
  searchAllMissingDone, searchAllMissingTotal, searchAllMissingAborted, abortSearchAllMissing,
  candidateDialogOpen, candidates, pushingSelected, pushSelectedCandidate, formatSize
} = usePtSubscription({ autoLoad: false })

// 每页条数按网格实际列数取整到整行，窗口宽度变了跟着重算
const { gridRef, columns, pageSizeOptions, setPageSize } = useGridPageSize((size) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
})

// 列表数据变化（刷新/翻页/重新查询）后清除海报失败集合，海报恢复或 TMDb 修复后能重新加载
watch(taskList, () => posterErrorIds.clear())

/** 候选种子表格中当前高亮的行 */
const selectedCandidate = ref<any>(null)

/** TMDb 海报路径拼完整图片地址，w200 宽度足够列表缩略图使用 */
const posterUrl = (path: string) => `https://image.tmdb.org/t/p/w200${path}`

/**
 * 骨架屏铺几张。列数直接用 useGridPageSize 量出来的真实值，不再自己按窗口宽度估——
 * 旧算法用 `window.innerWidth - 32 - 32`，把 220px 的侧边栏整个漏掉了，总是多算约一列，
 * 加载完会重排一次。
 */
const skeletonCount = computed(() => Math.max(3, Math.min(12, columns.value * 2)))

/** 排序档位。缺集数排序需要 ORDER BY 子查询，暂未提供，见后端 applySort 的注释 */
const sortOptions = [
  { title: '默认（最新创建）', value: '' },
  { title: '上次命中时间', value: 'lastMatchTime' },
  { title: '上次搜索时间', value: 'lastSearchTime' },
  { title: '标题', value: 'title' }
]

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

onMounted(() => {
  const subId = Number(route.query.id)
  if (subId) showProgressById(subId)
})

const goDownloadRecords = (row: any) => {
  const path = getRoutePathForComponent('openlist/ptDownloadRecord/index')
  if (path) router.push({ path, query: { subId: row.id } })
}

/** 缺集体检页的路径。菜单没授权时反查不到，页头那个入口就整个不渲染 */
const healthPath = computed(() => getRoutePathForComponent('openlist/ptHealth/index'))
const goHealth = () => {
  if (healthPath.value) router.push({ path: healthPath.value })
}

/** "更多"下拉菜单 command → 现有函数的分发，纯路由不新增业务逻辑 */
const handleMoreCommand = (cmd: string, row: any) => {
  switch (cmd) {
    case 'refresh': handleRefresh(row); break
    case 'logs': showSearchLogs(row); break
    case 'filter': openFilterOverride(row); break
    case 'search': openSeasonSearch(row); break
    case 'pause': handlePause(row); break
    case 'resume': handleResume(row); break
    case 'remove': handleRemove(row); break
  }
}

const searchHeaders = [
  { title: '海报', key: 'poster', sortable: false, width: 70, align: 'center' as const },
  { title: '标题', key: 'title', sortable: false, minWidth: '200' },
  { title: '年份', key: 'year', sortable: false, width: 80, align: 'center' as const },
  { title: 'TMDb ID', key: 'tmdbId', sortable: false, width: 100, align: 'center' as const }
]

// 「站点」与「片源」原先都叫「来源」：一列是哪个索引器、一列是 BluRay/WEB-DL，
// 而这张表就是给用户挑种子用的，两列同名等于没有表头
const candidateHeaders = [
  { title: '#', key: 'index', sortable: false, width: 48, align: 'center' as const },
  { title: '目标', key: 'target', sortable: false, width: 70, align: 'center' as const },
  { title: '站点', key: 'indexerName', sortable: false, width: 100 },
  { title: '标题', key: 'title', sortable: false, minWidth: '280' },
  { title: '分辨率', key: 'resolution', sortable: false, width: 80, align: 'center' as const },
  { title: '片源', key: 'source', sortable: false, width: 80, align: 'center' as const },
  { title: '体积', key: 'size', sortable: false, width: 100, align: 'end' as const },
  { title: '做种', key: 'seeders', sortable: false, width: 70, align: 'center' as const },
  { title: '免费', key: 'free', sortable: false, width: 60, align: 'center' as const }
]

const searchLogHeaders = [
  { title: '时间', key: 'createTime', sortable: false, width: 160 },
  { title: '触发方式', key: 'source', sortable: false, width: 100 },
  { title: '种子标题', key: 'torrentTitle', sortable: false, minWidth: '200' },
  { title: '结果', key: 'accepted', sortable: false, width: 80 },
  { title: '原因', key: 'reason', sortable: false, minWidth: '180' }
]
</script>

<style scoped lang="scss">
/* 卡片外壳（边框/圆角/hover/可点选/紧凑内距）全部来自 styles/list.scss 的 .item-card，
   这里只写订阅卡特有的：海报横排、进度条、开关行。 */

.item-card-checkbox {
  position: absolute;
  top: 4px;
  left: 4px;
  z-index: 1;
}

.item-card-skeleton {
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

/* 海报 + 信息横排 */
.sub-main {
  display: flex;
  gap: 12px;
  min-width: 0;
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

  /* 卡片窄，「上次命中」这类标签用不着 list.scss 里给表格卡准备的 72px */
  .card-row .label {
    width: 58px;
  }
}

.sub-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
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
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-secondary);
}

.sub-progress-inflight {
  color: var(--osr-primary);
}

/* 两个开关并作一行 */
.sub-switches {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.sub-switch {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;

  .label {
    color: var(--osr-text-secondary);
    white-space: nowrap;
  }
}

.more-actions-danger {
  color: rgb(var(--v-theme-error));
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
  gap: 4px;
}

.picked-season-hint {
  font-size: 12px;
  color: var(--osr-primary);
}

.field-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.progress-title {
  margin: 0 0 12px;
  font-size: 16px;
  font-weight: 600;
}

.progress-season {
  margin-left: 6px;
  font-size: 13px;
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
}

.missing-lead {
  font-size: 13px;
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

    &:hover {
      border-color: var(--osr-primary-accent);
      color: var(--osr-primary-hover);
    }
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
}

/* 补齐跑批时的「3/26」，与旁边的按钮同高 */
.batch-search-progress {
  padding: 0 12px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-secondary);
}

.log-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.log-count {
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.override-tip {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

/* 勾选框在最左：它是这一行的总开关，夹在标签和输入框中间时扫视都找不到它在哪 */
.override-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.override-checkbox {
  flex: none;
}

.override-label {
  width: 110px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--osr-text-secondary);
}

.override-input {
  flex: 1;
  min-width: 150px;
}

/* 全局取值参照：勾上覆盖那一刻用户得知道自己在把多少改成多少 */
.override-global {
  flex-shrink: 0;
  min-width: 108px;
  font-size: 12px;
  color: var(--osr-text-disabled);
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
