<template>
  <div class="page-container">
    <!-- 搜索 -->
    <el-card class="search-card" v-if="showSearch">
      <el-form :model="queryParams" ref="queryRef" :inline="true" label-width="80px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="queryParams.title" placeholder="请输入标题" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="类型" prop="mediaType">
          <el-select v-model="queryParams.mediaType" placeholder="类型" clearable :style="{ width: '120px' }">
            <el-option label="剧集" value="TV" />
            <el-option label="电影" value="MOVIE" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="queryParams.status" placeholder="状态" clearable :style="{ width: '130px' }">
            <el-option label="订阅中" value="ACTIVE" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="已暂停" value="PAUSED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleQuery">
            <el-icon><Search /></el-icon> 搜索
          </el-button>
          <el-button @click="resetQuery">
            <el-icon><Refresh /></el-icon> 重置
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 列表 -->
    <el-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <el-button type="primary" @click="openSubscribeDialog">
            <el-icon><Plus /></el-icon> 新增订阅
          </el-button>
          <el-button text @click="selectionMode = !selectionMode">
            {{ selectionMode ? '退出批量操作' : '批量操作' }}
          </el-button>
        </div>
        <div class="action-right">
          <span class="sort-label">排序：</span>
          <el-select
            v-model="queryParams.sortBy"
            class="sort-select"
            placeholder="排序"
            :style="{ width: '150px' }"
            @change="handleQuery"
          >
            <el-option label="默认（最新创建）" value="" />
            <el-option label="上次命中时间" value="lastMatchTime" />
          </el-select>
          <el-button text @click="showSearch = !showSearch">
            <el-icon><Filter /></el-icon>
            {{ showSearch ? '隐藏搜索' : '显示搜索' }}
          </el-button>
        </div>
      </div>

      <div class="batch-toolbar" v-if="selectionMode">
        已选 {{ selectedIds.length }} 项
        <el-checkbox
          :model-value="isAllPageSelected"
          :indeterminate="isIndeterminate"
          @change="toggleSelectAllPage"
          class="select-all-checkbox"
        >
          全选本页
        </el-checkbox>
        <el-button link type="warning" class="batch-pause-btn" :disabled="!selectedIds.length" @click="handleBatchPause">批量暂停</el-button>
        <el-button link type="success" class="batch-resume-btn" :disabled="!selectedIds.length" @click="handleBatchResume">批量恢复</el-button>
        <el-button link type="danger" class="batch-delete-btn" :disabled="!selectedIds.length" @click="handleDelete()">批量删除</el-button>
        <el-button link class="batch-cancel-btn" @click="selectionMode = false">取消</el-button>
      </div>

      <div class="card-grid" v-if="loading && taskList.length === 0">
        <div v-for="n in skeletonCount" :key="n" class="sub-card-skeleton">
          <el-skeleton animated class="sub-card-skeleton__body">
            <template #template>
              <el-skeleton-item variant="image" class="sub-card-skeleton__poster" />
              <div class="sub-card-skeleton__info">
                <el-skeleton-item variant="text" style="width: 70%; height: 16px; margin-bottom: 10px" />
                <el-skeleton-item variant="text" style="width: 50%; margin-bottom: 10px" />
                <el-skeleton-item variant="text" style="width: 100%; margin-bottom: 6px" />
                <el-skeleton-item variant="text" style="width: 100%" />
              </div>
            </template>
          </el-skeleton>
        </div>
      </div>
      <div class="card-grid" v-else v-loading="loading">
        <div v-for="item in taskList" :key="item.id" class="sub-card" :class="{ selectable: selectionMode }" @click="selectionMode && toggleSubSelect(item)">
          <el-checkbox
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
              <el-icon :size="24"><Picture /></el-icon>
              <span class="placeholder-text">{{ item.mediaType === 'MOVIE' ? '电影' : '剧集' }}</span>
            </div>
          </div>
          <div class="sub-info">
            <div class="sub-header">
              <span class="sub-title" :title="item.title">
                {{ item.title }}
                <span v-if="item.year" class="sub-year">({{ item.year }})</span>
              </span>
              <el-tag v-if="item.status === 'ACTIVE'" type="success" size="small">订阅中</el-tag>
              <el-tag v-else-if="item.status === 'COMPLETED'" type="info" size="small">已完成</el-tag>
              <el-tag v-else type="warning" size="small">已暂停</el-tag>
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
              <el-switch
                v-model="item.autoSearch"
                active-value="1"
                inactive-value="0"
                @change="toggleAutoSearch(item)"
              />
            </div>
            <div class="sub-actions">
              <el-button link type="primary" @click="showProgress(item)">进度</el-button>
              <el-button link type="primary" @click="goDownloadRecords(item)">下载记录</el-button>
              <el-button v-if="item.status !== 'PAUSED'" link type="warning" @click="handlePause(item)">暂停</el-button>
              <el-button v-else link type="success" @click="handleResume(item)">恢复</el-button>
              <el-button link type="danger" @click="handleRemove(item)">删除</el-button>
              <el-dropdown trigger="click" @command="(cmd: string) => handleMoreCommand(cmd, item)">
                <el-button link type="info">更多<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="refresh">对账</el-dropdown-item>
                    <el-dropdown-item command="logs">匹配日志</el-dropdown-item>
                    <el-dropdown-item command="filter">过滤规则</el-dropdown-item>
                    <el-dropdown-item command="search">搜索补齐</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>
        </div>
        <el-empty v-if="!loading && taskList.length === 0" description="暂无订阅" />
      </div>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="queryParams.pageNum"
          v-model:page-size="queryParams.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="getList"
          @size-change="getList"
        />
      </div>
    </el-card>

    <!-- 新增订阅：TMDb 选片 -->
    <el-dialog v-model="subscribeOpen" title="新增订阅" width="720px" append-to-body class="modern-dialog">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="类型">
          <el-select v-model="searchForm.mediaType" :style="{ width: '110px' }">
            <el-option label="剧集" value="TV" />
            <el-option label="电影" value="MOVIE" />
          </el-select>
        </el-form-item>
        <el-form-item label="片名">
          <el-input v-model="searchForm.keyword" placeholder="输入片名后回车" :style="{ width: '280px' }" @keyup.enter="doSearch" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="searchLoading" @click="doSearch">搜索 TMDb</el-button>
        </el-form-item>
      </el-form>

      <el-table
        v-loading="searchLoading"
        :data="searchResults"
        height="300"
        highlight-current-row
        @current-change="pick"
      >
        <el-table-column label="海报" width="64" align="center">
          <template #default="scope">
            <img
              v-if="scope.row.posterPath"
              :src="posterUrl(scope.row.posterPath)"
              class="search-poster"
              loading="lazy"
              @error="(e: Event) => ((e.target as HTMLImageElement).style.visibility = 'hidden')"
            />
            <el-icon v-else class="search-poster-placeholder"><Picture /></el-icon>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="200" show-overflow-tooltip>
          <template #default="scope">
            {{ scope.row.title }}
            <span v-if="scope.row.originalTitle && scope.row.originalTitle !== scope.row.title" class="sub-year">
              / {{ scope.row.originalTitle }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="年份" prop="year" width="80" align="center">
          <template #default="scope">{{ scope.row.year || '-' }}</template>
        </el-table-column>
        <el-table-column label="TMDb ID" prop="tmdbId" width="100" align="center" />
      </el-table>

      <div v-if="picked" class="picked-bar">
        已选：<strong>{{ picked.title }}</strong>
        <template v-if="searchForm.mediaType !== 'MOVIE'">
          &nbsp;第
          <el-input-number v-model="pickedSeason" :min="0" :max="99" size="small" :style="{ width: '110px' }" />
          季
          <span class="sub-year">（第 0 季是特别篇）</span>
        </template>
      </div>

      <template #footer>
        <el-button @click="subscribeOpen = false">取消</el-button>
        <el-button type="primary" :loading="subscribeLoading" :disabled="!picked" @click="confirmSubscribe">
          订阅
        </el-button>
      </template>
    </el-dialog>

    <!-- 进度 -->
    <el-dialog v-model="progressOpen" title="订阅进度" width="520px" append-to-body class="modern-dialog">
      <div v-loading="progressLoading">
        <template v-if="progress">
          <p class="progress-title">{{ progress.title }}</p>
          <el-progress
            :percentage="progress.totalEpisodes ? Math.round((progress.inLibraryCount / progress.totalEpisodes) * 100) : 0"
          />
          <p>已入库 <strong>{{ progress.inLibraryCount }}</strong> / {{ progress.totalEpisodes }} 集</p>
          <p v-if="progress.inFlightCount">在途 {{ progress.inFlightCount }} 集（已推送下载器，尚未入库）</p>
          <div v-if="progress.missingEpisodes && progress.missingEpisodes.length" class="missing-list">
            仍缺第
            <span v-for="(ep, idx) in progress.missingEpisodes" :key="ep" class="missing-item">
              <span v-if="idx > 0">、</span>{{ ep }}
              <el-button
                v-if="currentSubscription && currentSubscription.mediaType !== 'MOVIE'"
                link
                type="primary"
                size="small"
                @click="openEpisodeSearch(currentSubscription, ep)"
              >搜</el-button>
            </span>
            集
          </div>
          <p v-else class="all-done">全部集已入库</p>

          <div class="episode-detail-toggle" @click="loadEpisodeDetail">
            {{ episodeDetailOpen ? '收起全部集' : '查看全部集' }}
            <el-icon :class="{ 'is-open': episodeDetailOpen }"><ArrowDown /></el-icon>
          </div>
          <div v-if="episodeDetailOpen" class="episode-detail-list" v-loading="episodeDetailLoading">
            <div v-for="ep in episodeDetail" :key="ep.episode" class="episode-detail-row">
              <span class="ep-num">第{{ ep.episode }}集</span>
              <el-tag
                size="small"
                :type="ep.state === 'IN_LIBRARY' ? 'success' : ep.state === 'IN_FLIGHT' ? 'primary' : ep.state === 'BLOCKED' ? 'danger' : 'info'"
              >
                {{ episodeStateLabel(ep.state) }}
              </el-tag>
              <el-button
                v-if="ep.state === 'IN_LIBRARY' || ep.state === 'BLOCKED'"
                link
                type="warning"
                size="small"
                :loading="resettingEpisode === ep.episode"
                @click="handleResetEpisode(ep)"
              >重置</el-button>
            </div>
            <el-empty v-if="!episodeDetailLoading && episodeDetail.length === 0" description="暂无数据" :image-size="40" />
          </div>
        </template>
      </div>
      <template #footer>
        <el-button v-if="currentSubscription" type="primary" @click="openSeasonSearch(currentSubscription)">
          搜索补齐
        </el-button>
        <el-button
          v-if="currentSubscription && progress && progress.missingEpisodes && progress.missingEpisodes.length > 1"
          type="success"
          :loading="searchAllMissingLoading"
          @click="handleSearchAllMissing"
        >
          一键补齐全部（{{ progress.missingEpisodes.length }}集）
        </el-button>
        <el-button @click="progressOpen = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 搜索补集确认 -->
    <el-dialog v-model="searchDialogOpen" title="搜索补集" width="480px" append-to-body class="modern-dialog">
      <el-form label-width="80px">
        <el-form-item label="关键词">
          <el-input v-model="searchDialogKeyword" placeholder="搜索关键词，可编辑后再搜" />
        </el-form-item>
        <el-form-item label=" ">
          <el-checkbox v-model="searchManualSelect">
            手动选择结果
          </el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="searchDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="searchDialogLoading" @click="confirmSearch">搜索</el-button>
      </template>
    </el-dialog>

    <!-- 候选种子手动选择 -->
    <el-dialog v-model="candidateDialogOpen" title="选择候选种子" width="800px" append-to-body class="modern-dialog">
      <div v-if="candidates.length === 0" style="text-align:center;padding:40px;color:var(--osr-text-secondary);">
        未搜索到匹配资源
      </div>
      <el-table v-else :data="candidates" highlight-current-row height="420" size="small" @current-change="(row: any) => selectedCandidate = row">
        <el-table-column type="index" label="#" width="48" align="center" />
        <el-table-column label="目标" width="70" align="center">
          <template #default="scope">
            <el-tag v-if="scope.row.parsedEpisode && scope.row.parsedEpisodeEnd > scope.row.parsedEpisode" size="small" type="warning">
              第{{ scope.row.parsedEpisode }}-{{ scope.row.parsedEpisodeEnd }}集
            </el-tag>
            <el-tag v-else-if="scope.row.parsedEpisode" size="small" type="warning">第{{ scope.row.parsedEpisode }}集</el-tag>
            <el-tag v-else size="small" type="success">整季</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="100">
          <template #default="scope">
            <el-tag size="small" type="info">{{ scope.row.indexerName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="280" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.title }}</template>
        </el-table-column>
        <el-table-column label="分辨率" width="80" align="center">
          <template #default="scope">{{ scope.row.resolution || '-' }}</template>
        </el-table-column>
        <el-table-column label="来源" width="80" align="center">
          <template #default="scope">{{ scope.row.source || '-' }}</template>
        </el-table-column>
        <el-table-column label="体积" width="100" align="right">
          <template #default="scope">{{ formatSize(scope.row.size) }}</template>
        </el-table-column>
        <el-table-column label="做种" width="70" align="center">
          <template #default="scope">
            <el-tag :type="scope.row.seeders > 0 ? 'success' : 'danger'" size="small">{{ scope.row.seeders }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="免费" width="60" align="center">
          <template #default="scope">
            <el-tag v-if="scope.row.free" type="warning" size="small">免费</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="candidateDialogOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="pushingSelected"
          :disabled="!selectedCandidate"
          @click="pushSelectedCandidate(selectedCandidate)"
        >
          下载选中版本
        </el-button>
      </template>
    </el-dialog>

    <!-- 匹配日志 -->
    <el-dialog v-model="searchLogOpen" title="匹配日志" width="720px" append-to-body class="modern-dialog">
      <el-table v-loading="searchLogLoading" :data="searchLogs" height="420" size="small">
        <el-table-column label="时间" prop="createTime" width="160" />
        <el-table-column label="来源" width="90">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.source === 'RSS' ? 'info' : 'primary'">
              {{ scope.row.source === 'RSS' ? 'RSS轮询' : '搜索补集' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="种子标题" prop="torrentTitle" min-width="200" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.torrentTitle || '-' }}</template>
        </el-table-column>
        <el-table-column label="结果" width="80">
          <template #default="scope">
            <el-tag v-if="scope.row.accepted === '1'" type="success" size="small">通过</el-tag>
            <el-tag v-else type="danger" size="small">淘汰</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="原因" prop="reason" min-width="180" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.reason || '-' }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!searchLogLoading && searchLogs.length === 0" description="暂无日志（还没轮询/搜索过，或该订阅日志已被清理）" />
      <template #footer>
        <el-button @click="searchLogOpen = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 过滤规则覆盖 -->
    <el-dialog v-model="filterOverrideOpen" title="过滤规则覆盖" width="640px" append-to-body class="modern-dialog">
      <p class="override-tip">只勾选需要覆盖的项，不勾选的沿用全局过滤规则（PT过滤规则页配置的）。</p>
      <el-form label-width="120px">
        <el-form-item label="最低做种数">
          <el-checkbox v-model="filterOverrideForm.minSeeders.enabled" class="override-checkbox" />
          <el-input-number
            v-model="filterOverrideForm.minSeeders.value"
            :min="0"
            :disabled="!filterOverrideForm.minSeeders.enabled"
            :style="{ width: '200px' }"
          />
        </el-form-item>
        <el-form-item label="体积下限">
          <el-checkbox v-model="filterOverrideForm.minSize.enabled" class="override-checkbox" />
          <el-input-number
            v-model="filterOverrideForm.minSize.value"
            :min="0"
            :max="999"
            :disabled="!filterOverrideForm.minSize.enabled"
            :style="{ width: '160px' }"
          />
          <span class="form-tip">GB</span>
        </el-form-item>
        <el-form-item label="体积上限">
          <el-checkbox v-model="filterOverrideForm.maxSize.enabled" class="override-checkbox" />
          <el-input-number
            v-model="filterOverrideForm.maxSize.value"
            :min="0"
            :max="999"
            :disabled="!filterOverrideForm.maxSize.enabled"
            :style="{ width: '160px' }"
          />
          <span class="form-tip">GB</span>
        </el-form-item>
        <el-form-item label="仅要免费种">
          <el-checkbox v-model="filterOverrideForm.freeOnly.enabled" class="override-checkbox" />
          <el-radio-group v-model="filterOverrideForm.freeOnly.value" :disabled="!filterOverrideForm.freeOnly.enabled">
            <el-radio value="0">否</el-radio>
            <el-radio value="1">是</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="外语电影需中字">
          <el-checkbox v-model="filterOverrideForm.requireChineseSubtitle.enabled" class="override-checkbox" />
          <el-radio-group v-model="filterOverrideForm.requireChineseSubtitle.value" :disabled="!filterOverrideForm.requireChineseSubtitle.enabled">
            <el-radio value="0">否</el-radio>
            <el-radio value="1">是</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="分辨率白名单">
          <el-checkbox v-model="filterOverrideForm.resolutionWhitelist.enabled" class="override-checkbox" />
          <el-input
            v-model="filterOverrideForm.resolutionWhitelist.value"
            placeholder="如 2160p,1080p"
            :disabled="!filterOverrideForm.resolutionWhitelist.enabled"
          />
        </el-form-item>
        <el-form-item label="标题包含词">
          <el-checkbox v-model="filterOverrideForm.includeKeywords.enabled" class="override-checkbox" />
          <el-input
            v-model="filterOverrideForm.includeKeywords.value"
            placeholder="逗号分隔，命中其一即可"
            :disabled="!filterOverrideForm.includeKeywords.enabled"
          />
        </el-form-item>
        <el-form-item label="标题排除词">
          <el-checkbox v-model="filterOverrideForm.excludeKeywords.enabled" class="override-checkbox" />
          <el-input
            v-model="filterOverrideForm.excludeKeywords.value"
            placeholder="逗号分隔，命中任一即淘汰"
            :disabled="!filterOverrideForm.excludeKeywords.enabled"
          />
        </el-form-item>
        <el-form-item label="分辨率优先级">
          <el-checkbox v-model="filterOverrideForm.resolutionPriority.enabled" class="override-checkbox" />
          <el-input
            v-model="filterOverrideForm.resolutionPriority.value"
            placeholder="如 2160p,1080p,720p"
            :disabled="!filterOverrideForm.resolutionPriority.enabled"
          />
        </el-form-item>
        <el-form-item label="偏好体积">
          <el-checkbox v-model="filterOverrideForm.preferredSize.enabled" class="override-checkbox" />
          <el-input-number
            v-model="filterOverrideForm.preferredSize.value"
            :min="0"
            :max="999"
            :disabled="!filterOverrideForm.preferredSize.enabled"
            :style="{ width: '160px' }"
          />
          <span class="form-tip">GB</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="filterOverrideOpen = false">取消</el-button>
        <el-button type="primary" :loading="filterOverrideSaving" @click="saveFilterOverride">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Picture, ArrowDown } from '@element-plus/icons-vue'
import { usePtSubscription } from '@/composables/usePtSubscription'

const router = useRouter()
const route = useRoute()
const showSearch = ref(window.innerWidth >= 768)
/** 海报加载失败的订阅 id 集合，命中则展示占位图标而非裂图 */
const posterErrorIds = reactive(new Set<number>())

const onPosterError = (id: number) => { posterErrorIds.add(id) }

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  subscribeOpen, searchLoading, subscribeLoading, searchResults, searchForm,
  picked, pickedSeason, openSubscribeDialog, doSearch, pick, confirmSubscribe,
  progressOpen, progressLoading, progress, currentSubscription, showProgress, showProgressById,
  episodeDetailOpen, episodeDetailLoading, episodeDetail, resettingEpisode,
  loadEpisodeDetail, handleResetEpisode,
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

const EPISODE_STATE_LABELS: Record<string, string> = {
  MISSING: '缺失', IN_FLIGHT: '在途', IN_LIBRARY: '已入库', BLOCKED: '已熔断'
}
const episodeStateLabel = (state: string) => EPISODE_STATE_LABELS[state] || state

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
</script>

<style scoped lang="scss">
.page-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.search-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);

  :deep(.el-card__body) {
    padding: 14px 16px;
  }
}

.table-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);

  :deep(.el-card__body) {
    padding: 16px;
    display: flex;
    flex-direction: column;
  }
}

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
  gap: 10px;

  .action-left {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }

  .action-right {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
  }
}

.batch-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: var(--osr-radius-sm);
  background: var(--osr-bg-page);
  font-size: 13px;
  color: var(--osr-text-secondary);
  position: sticky;
  top: 0;
  z-index: 2;
}

.select-all-checkbox {
  margin-left: 4px;
  font-size: 13px;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: auto;
  padding-top: 12px;
}

/* ============================================
   订阅卡片网格（带海报）
   ============================================ */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 14px;
  min-height: 120px;
}

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
      border-color: var(--osr-primary-light-5);
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
  --el-skeleton-color: var(--osr-border-light);
  --el-skeleton-to-color: var(--osr-bg-page);
  padding: 14px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);

  :deep(.sub-card-skeleton__body) {
    display: flex;
    gap: 12px;
  }

  :deep(.sub-card-skeleton__poster) {
    flex-shrink: 0;
    width: 72px;
    height: 108px;
    border-radius: var(--osr-radius-sm);
  }

  :deep(.sub-card-skeleton__info) {
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

    &.placeholder-movie {
      background: linear-gradient(135deg, #1e3a5f 0%, #2d5a87 50%, #1e3a5f 100%);
      color: rgba(255, 255, 255, 0.7);
    }

    &.placeholder-tv {
      background: linear-gradient(135deg, #3b1f47 0%, #6b3a7a 50%, #3b1f47 100%);
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

@media (max-width: 768px) {
  .page-container {
    gap: 10px;
  }

  .search-card :deep(.el-form) {
    .el-form-item {
      margin-right: 0;
    }

    .el-input,
    .el-select {
      width: 100% !important;
    }
  }

  .action-bar {
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 10px;

    .action-left {
      gap: 4px;
    }
  }

  .table-card :deep(.el-card__body) {
    padding: 12px;
  }

  .card-grid {
    grid-template-columns: 1fr;
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
  border-radius: 4px;
  background: var(--osr-bg-page);
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

  .el-icon {
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

.override-checkbox {
  margin-right: 10px;
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
