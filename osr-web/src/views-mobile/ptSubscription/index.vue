<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && taskList.length === 0"
    empty-icon="inbox"
    empty-title="暂无订阅"
  >
    <template #head>
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
      <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="plus" @click="openSubscribeDialog">
        新增
      </v-btn>

      <!-- 批量选择开关：与 PC 一致，不开启时点卡片不会误选 -->
      <div class="list-toolbar">
        <v-btn variant="text" size="small" @click="toggleSelectionMode">
          {{ selectionMode ? '退出批量操作' : '批量操作' }}
        </v-btn>
        <!-- 缺集体检回答的是「这一格为什么还是灰的」，与 PC 端页头那个入口对齐 -->
        <v-btn v-if="healthPath" variant="text" size="small" prepend-icon="stethoscope" @click="goHealth">
          缺集体检
        </v-btn>
      </div>

      <!-- 批量操作 -->
      <MobileBatchBar
        :visible="selectionMode"
        :count="selectedIds.length"
        :all-selected="isAllPageSelected"
        @toggle-all="toggleSelectAllPage"
        @cancel="toggleSelectionMode"
      >
        <v-btn variant="text" color="warning" size="small" :disabled="!selectedIds.length" @click="handleBatchPause">批量暂停</v-btn>
        <v-btn variant="text" color="success" size="small" :disabled="!selectedIds.length" @click="handleBatchResume">批量恢复</v-btn>
        <v-btn variant="text" color="error" size="small" :disabled="!selectedIds.length" @click="handleDelete()">批量删除</v-btn>
      </MobileBatchBar>

      <!-- 列表 -->
    </template>

    <SubscriptionCard v-for="item in taskList" :key="item.id" :item="item" @more="openSheet" />

    <template #foot>
      <!-- 操作抽屉 -->
      <MobileActionSheet v-model="sheetOpen" :target="sheetTarget">
        <v-btn v-if="sheetTarget.status !== 'PAUSED'" color="warning" block @click="run(() => handlePause(sheetTarget))">暂停</v-btn>
        <v-btn v-else color="success" block @click="run(() => handleResume(sheetTarget))">恢复</v-btn>
        <v-btn color="primary" block @click="run(() => openSeasonSearch(sheetTarget))">搜索补齐</v-btn>
        <v-btn block @click="run(() => handleRefresh(sheetTarget))">对账</v-btn>
        <!-- 只给已入库的电影：对账只升不降，把影片从媒体库删掉后再点上面那条「对账」
             不会有任何变化，重下得从这里把它退回缺失。剧集逐集重置在进度弹窗里 -->
        <v-btn
          v-if="sheetTarget.mediaType === 'MOVIE' && sheetTarget.inLibraryCount"
          color="warning"
          block
          @click="run(() => handleResetMovie(sheetTarget))"
        >重置为未入库</v-btn>
        <v-btn block @click="run(() => showSearchLogs(sheetTarget))">匹配日志</v-btn>
        <v-btn block @click="run(() => openFilterOverride(sheetTarget))">过滤规则</v-btn>
        <v-btn color="error" block @click="run(() => handleRemove(sheetTarget))">删除</v-btn>
      </MobileActionSheet>

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

      <SubscribeDialog />

      <ProgressDialog />

      <SearchConfirmDialog />

      <CandidateDialog />

      <SearchLogDialog />

      <FilterOverrideDialog />
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getRoutePathForComponent } from '@/router'
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileActionSheet from '@/components/mobile/MobileActionSheet.vue'
import MobileBatchBar from '@/components/mobile/MobileBatchBar.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtSubscriptionProvider } from '@/composables/ptSubscriptionContext'
import SubscriptionCard from './SubscriptionCard.vue'
import SubscribeDialog from './dialogs/SubscribeDialog.vue'
import ProgressDialog from './dialogs/ProgressDialog.vue'
import SearchConfirmDialog from './dialogs/SearchConfirmDialog.vue'
import CandidateDialog from './dialogs/CandidateDialog.vue'
import SearchLogDialog from './dialogs/SearchLogDialog.vue'
import FilterOverrideDialog from './dialogs/FilterOverrideDialog.vue'
import { useActionSheet } from '@/composables/useActionSheet'

const route = useRoute()
const router = useRouter()

// 弹窗子组件共享这同一个实例（见 ptSubscriptionContext）
const {
  taskList, loading, total, queryParams,
  handleQuery, resetQuery, openSubscribeDialog, showProgressById, showSearchLogs,
  openFilterOverride,
  openSeasonSearch,
  handleRefresh, handlePause, handleResume, handleRemove, handleResetMovie,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed,
  selectionMode, toggleSelectionMode, isAllPageSelected, toggleSelectAllPage,
  selectedIds, handleBatchPause, handleBatchResume, handleDelete
} = usePtSubscriptionProvider()

/** 排序档位，与 PC 端同一份取值。缺集数排序需要 ORDER BY 子查询，暂未提供 */
const sortOptions = [
  { title: '默认（最新创建）', value: '' },
  { title: '上次命中时间', value: 'lastMatchTime' },
  { title: '上次搜索时间', value: 'lastSearchTime' },
  { title: '标题', value: 'title' }
]


/** 操作抽屉状态 */
/** 卡片「更多」动作面板：开关状态与「执行完自动关闭」都在 useActionSheet 里 */
const { sheetOpen, sheetTarget, openSheet, run } = useActionSheet()



/** 缺集体检页入口。菜单没授权时反查不到，按钮整个不渲染 */
const healthPath = computed(() => getRoutePathForComponent('openlist/ptHealth/index'))
const goHealth = () => {
  if (healthPath.value) router.push({ path: healthPath.value })
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
}</style>

.list-toolbar {
  display: flex;
  justify-content: flex-end;
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
