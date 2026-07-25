<template>
  <div class="mobile-page">
    <!-- 搜索 -->
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <el-form ref="queryRef" :model="queryParams" label-width="72px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="queryParams.title" placeholder="请输入标题" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="类型" prop="mediaType">
          <el-select v-model="queryParams.mediaType" placeholder="全部类型" clearable style="width: 100%">
            <el-option label="剧集" value="TV" />
            <el-option label="电影" value="MOVIE" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="queryParams.status" placeholder="全部状态" clearable style="width: 100%">
            <el-option label="订阅中" value="ACTIVE" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="已暂停" value="PAUSED" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序" prop="sortBy">
          <el-select v-model="queryParams.sortBy" placeholder="排序" clearable style="width: 100%" @change="handleQuery">
            <el-option label="默认（最新创建）" value="" />
            <el-option label="上次命中时间" value="lastMatchTime" />
          </el-select>
        </el-form-item>
      </el-form>
    </MobileSearchPanel>

    <!-- 列表 -->
    <div class="task-list" v-loading="loading">
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
            <el-icon><Picture /></el-icon>
          </div>
        </div>
        <div class="sub-content">
          <div class="sub-top">
            <span class="sub-name">
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
          <div class="detail-row">
            <span class="label">上次命中</span>
            <span class="value">{{ item.lastMatchTime || '-' }}</span>
          </div>
          <div class="detail-row">
            <span class="label">自动补搜</span>
            <el-switch
              v-model="item.autoSearch"
              active-value="1"
              inactive-value="0"
              size="small"
              @change="toggleAutoSearch(item)"
            />
          </div>
          <div class="sub-actions" @click.stop>
            <el-button link type="primary" size="small" @click="showProgress(item)">进度</el-button>
            <el-button link type="primary" size="small" @click="goDownloadRecords(item)">下载记录</el-button>
            <el-button link type="info" size="small" @click="openActionDrawer(item)">···</el-button>
          </div>
        </div>
      </div>

      <el-empty v-if="!loading && taskList.length === 0" description="暂无订阅" />
    </div>

    <!-- 操作抽屉 -->
    <el-drawer v-model="actionDrawerOpen" direction="btt" size="auto" :with-header="true" title="更多操作" append-to-body class="modern-drawer">
      <div class="drawer-actions" v-if="actionDrawerTarget">
        <el-button v-if="actionDrawerTarget.status !== 'PAUSED'" type="warning" @click="handlePause(actionDrawerTarget); actionDrawerOpen = false" style="margin-left: 0">暂停</el-button>
        <el-button v-else type="success" @click="handleResume(actionDrawerTarget); actionDrawerOpen = false" style="margin-left: 0">恢复</el-button>
        <el-button type="primary" @click="openSeasonSearch(actionDrawerTarget); actionDrawerOpen = false" style="margin-left: 0">搜索补齐</el-button>
        <el-button @click="handleRefresh(actionDrawerTarget); actionDrawerOpen = false" style="margin-left: 0">对账</el-button>
        <el-button @click="showSearchLogs(actionDrawerTarget); actionDrawerOpen = false" style="margin-left: 0">匹配日志</el-button>
        <el-button @click="openFilterOverride(actionDrawerTarget); actionDrawerOpen = false" style="margin-left: 0">过滤规则</el-button>
        <el-button type="danger" @click="handleRemove(actionDrawerTarget); actionDrawerOpen = false" style="margin-left: 0">删除</el-button>
      </div>
    </el-drawer>

    <!-- 批量操作 -->
    <div class="batch-bar" v-if="selectedIds.length > 0">
      <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
      <el-button link type="warning" size="small" @click="handleBatchPause">批量暂停</el-button>
      <el-button link type="success" size="small" @click="handleBatchResume">批量恢复</el-button>
      <el-button link type="danger" size="small" @click="handleDelete()">批量删除</el-button>
      <el-button link size="small" @click="selectedIds.length = 0">取消</el-button>
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

    <!-- 进度 -->
    <el-dialog v-model="progressOpen" title="订阅进度" width="90%" append-to-body class="modern-dialog">
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
        </template>
      </div>
      <template #footer>
        <el-button v-if="currentSubscription" type="primary" @click="openSeasonSearch(currentSubscription)">
          搜索补齐
        </el-button>
        <el-button @click="progressOpen = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 搜索补集确认 -->
    <el-dialog v-model="searchDialogOpen" title="搜索补集" width="90%" append-to-body class="modern-dialog">
      <el-form label-width="80px">
        <el-form-item label="关键词">
          <el-input v-model="searchDialogKeyword" placeholder="搜索关键词，可编辑后再搜" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="searchDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="searchDialogLoading" @click="confirmSearch">搜索</el-button>
      </template>
    </el-dialog>

    <!-- 匹配日志 -->
    <el-dialog v-model="searchLogOpen" title="匹配日志" width="92%" append-to-body class="modern-dialog">
      <div v-loading="searchLogLoading" class="log-list">
        <div v-for="(log, idx) in searchLogs" :key="idx" class="log-item">
          <div class="log-top">
            <span class="log-time">{{ log.createTime }}</span>
            <el-tag size="small" :type="log.source === 'RSS' ? 'info' : 'primary'">
              {{ log.source === 'RSS' ? 'RSS轮询' : '搜索补集' }}
            </el-tag>
            <el-tag v-if="log.accepted === '1'" type="success" size="small">通过</el-tag>
            <el-tag v-else type="danger" size="small">淘汰</el-tag>
          </div>
          <div class="log-title">{{ log.torrentTitle || '-' }}</div>
          <div class="log-reason" v-if="log.reason">{{ log.reason }}</div>
        </div>
        <el-empty v-if="!searchLogLoading && searchLogs.length === 0" description="暂无日志" />
      </div>
      <template #footer>
        <el-button @click="searchLogOpen = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 过滤规则覆盖 -->
    <el-dialog v-model="filterOverrideOpen" title="过滤规则覆盖" width="94%" append-to-body class="modern-dialog">
      <p class="override-tip">只勾选需要覆盖的项，不勾选的沿用全局过滤规则。</p>
      <el-form label-width="86px" size="small">
        <el-form-item label="最低做种数">
          <div class="override-row">
            <el-checkbox v-model="filterOverrideForm.minSeeders.enabled" />
            <el-input-number
              v-model="filterOverrideForm.minSeeders.value"
              :min="0"
              :disabled="!filterOverrideForm.minSeeders.enabled"
              style="width: 100%"
            />
          </div>
        </el-form-item>
        <el-form-item label="体积下限">
          <div class="override-row">
            <el-checkbox v-model="filterOverrideForm.minSize.enabled" />
            <el-input-number
              v-model="filterOverrideForm.minSize.value"
              :min="0"
              :step="1073741824"
              :disabled="!filterOverrideForm.minSize.enabled"
              style="width: 100%"
            />
          </div>
        </el-form-item>
        <el-form-item label="体积上限">
          <div class="override-row">
            <el-checkbox v-model="filterOverrideForm.maxSize.enabled" />
            <el-input-number
              v-model="filterOverrideForm.maxSize.value"
              :min="0"
              :step="1073741824"
              :disabled="!filterOverrideForm.maxSize.enabled"
              style="width: 100%"
            />
          </div>
        </el-form-item>
        <el-form-item label="仅要免费种">
          <div class="override-row">
            <el-checkbox v-model="filterOverrideForm.freeOnly.enabled" />
            <el-radio-group v-model="filterOverrideForm.freeOnly.value" :disabled="!filterOverrideForm.freeOnly.enabled">
              <el-radio value="0">否</el-radio>
              <el-radio value="1">是</el-radio>
            </el-radio-group>
          </div>
        </el-form-item>
        <el-form-item label="分辨率白名单">
          <div class="override-row">
            <el-checkbox v-model="filterOverrideForm.resolutionWhitelist.enabled" />
            <el-input
              v-model="filterOverrideForm.resolutionWhitelist.value"
              placeholder="如 2160p,1080p"
              :disabled="!filterOverrideForm.resolutionWhitelist.enabled"
            />
          </div>
        </el-form-item>
        <el-form-item label="标题包含词">
          <div class="override-row">
            <el-checkbox v-model="filterOverrideForm.includeKeywords.enabled" />
            <el-input
              v-model="filterOverrideForm.includeKeywords.value"
              placeholder="逗号分隔，命中其一即可"
              :disabled="!filterOverrideForm.includeKeywords.enabled"
            />
          </div>
        </el-form-item>
        <el-form-item label="标题排除词">
          <div class="override-row">
            <el-checkbox v-model="filterOverrideForm.excludeKeywords.enabled" />
            <el-input
              v-model="filterOverrideForm.excludeKeywords.value"
              placeholder="逗号分隔，命中任一即淘汰"
              :disabled="!filterOverrideForm.excludeKeywords.enabled"
            />
          </div>
        </el-form-item>
        <el-form-item label="分辨率优先级">
          <div class="override-row">
            <el-checkbox v-model="filterOverrideForm.resolutionPriority.enabled" />
            <el-input
              v-model="filterOverrideForm.resolutionPriority.value"
              placeholder="如 2160p,1080p,720p"
              :disabled="!filterOverrideForm.resolutionPriority.enabled"
            />
          </div>
        </el-form-item>
        <el-form-item label="偏好体积">
          <div class="override-row">
            <el-checkbox v-model="filterOverrideForm.preferredSize.enabled" />
            <el-input-number
              v-model="filterOverrideForm.preferredSize.value"
              :min="0"
              :step="1073741824"
              :disabled="!filterOverrideForm.preferredSize.enabled"
              style="width: 100%"
            />
          </div>
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
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Picture } from '@element-plus/icons-vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtSubscription } from '@/composables/usePtSubscription'

const router = useRouter()

const {
  taskList, loading, total, queryParams, queryRef,
  handleQuery, resetQuery,
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

.batch-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 14px;
  background: var(--osr-primary-light-9);
  border: 1px solid var(--osr-primary-light-7);
  border-radius: var(--osr-radius-md);
  font-size: 13px;

  .selected-count {
    font-weight: 600;
    color: var(--osr-primary);
    margin-right: 4px;
    white-space: nowrap;
  }

  .el-button {
    font-size: 12px;
    padding: 0 4px;
    height: auto;
  }
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

  .el-button {
    font-size: 12px;
    padding: 2px 4px;
    height: auto;
  }
}

.progress-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}

.all-done {
  color: var(--el-color-success);
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

:deep(.modern-dialog) {
  .el-dialog__body {
    padding: 16px;
  }
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
  color: var(--el-color-danger);
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
  width: 100%;

  .el-radio-group {
    flex: 1;
  }
}

.modern-drawer {
  :deep(.el-drawer__body) {
    padding: 16px;
  }

  :deep(.el-button + .el-button) {
    margin-left: 0;
  }

  .drawer-actions {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
    width: 100%;

    .el-button {
      width: 100% !important;
      justify-content: center;
      height: 44px;
      font-size: 15px;
      border-radius: var(--osr-radius-md);
      margin-left: 0 !important;
      margin-right: 0 !important;
    }
  }
}
</style>
