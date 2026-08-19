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
    <SearchPanel :visible="showSearch" @search="handleQuery" @reset="resetQuery">
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
    </SearchPanel>

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
        <SubscriptionCard v-for="item in taskList" :key="item.id" :item="item" />
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

    <SubscribeDialog />

    <ProgressDialog />

    <SearchConfirmDialog />

    <CandidateDialog />

    <SearchLogDialog />

    <FilterOverrideDialog />
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import { computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getRoutePathForComponent } from '@/router'
import { usePtSubscriptionProvider } from '@/composables/ptSubscriptionContext'
import SubscriptionCard from './SubscriptionCard.vue'
import SubscribeDialog from './dialogs/SubscribeDialog.vue'
import ProgressDialog from './dialogs/ProgressDialog.vue'
import SearchConfirmDialog from './dialogs/SearchConfirmDialog.vue'
import CandidateDialog from './dialogs/CandidateDialog.vue'
import SearchLogDialog from './dialogs/SearchLogDialog.vue'
import FilterOverrideDialog from './dialogs/FilterOverrideDialog.vue'
import { useGridPageSize } from '@/composables/useGridPageSize'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'

const router = useRouter()
const route = useRoute()
// 弹窗子组件共享这同一个实例（见 ptSubscriptionContext）
const { showSearch } = useSearchPanel()

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, openSubscribeDialog, showProgressById, handleDelete,
  selectedIds, selectionMode, toggleSelectionMode,
  handleBatchPause, handleBatchResume,
  isAllPageSelected, toggleSelectAllPage} = usePtSubscriptionProvider({ autoLoad: false })

// 每页条数按网格实际列数取整到整行，窗口宽度变了跟着重算
const { gridRef, columns, pageSizeOptions, setPageSize } = useGridPageSize((size) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
})




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







onMounted(() => {
  const subId = Number(route.query.id)
  if (subId) showProgressById(subId)
})



/** 缺集体检页的路径。菜单没授权时反查不到，页头那个入口就整个不渲染 */
const healthPath = computed(() => getRoutePathForComponent('openlist/ptHealth/index'))
const goHealth = () => {
  if (healthPath.value) router.push({ path: healthPath.value })
}
</script>

<style scoped lang="scss">

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

.sort-label {
  font-size: 13px;
  color: var(--osr-text-secondary);
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
}</style>

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

.sort-label {
  font-size: 13px;
  color: var(--osr-text-secondary);
  white-space: nowrap;
}

:deep(.row-selected) {
  background: var(--osr-primary-subtle);
}
