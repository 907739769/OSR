<template>
  <div class="page-container">
    <PageHeader
      icon="bookmark"
      title="PT 订阅"
      desc="按 TMDb 作品订阅，自动匹配 RSS 与补搜缺集"
    >
      <template #actions>
        <!-- 缺集体检回答的是「这一格为什么还是灰的」，是这一页的天然去处。
             页头这个看全部；只看某一条的入口在卡片「更多」里（体检页读 ?subId=） -->
        <v-btn v-if="healthPath" variant="text" prepend-icon="stethoscope" @click="goHealth">
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
      <v-select
        v-model="queryParams.autoSearch"
        :items="AUTO_SEARCH_OPTIONS"
        label="自动补搜"
        placeholder="自动补搜"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="field-sm"
      />
      <v-select
        v-model="queryParams.hasMissing"
        :items="HAS_MISSING_OPTIONS"
        label="缺集"
        placeholder="缺集"
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
          <v-btn color="primary" prepend-icon="plus" @click="openSubscribeDialog">
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
          <v-btn variant="text" prepend-icon="funnel" @click="showSearch = !showSearch">
            {{ showSearch ? '隐藏搜索' : '显示搜索' }}
          </v-btn>
        </div>
      </div>

      <div v-if="selectionMode" class="batch-toolbar">
        已选 {{ selectedIds.length }} 项
        <v-btn variant="text" color="warning" size="small" class="batch-pause-btn" :disabled="!selectedIds.length" @click="handleBatchPause">批量暂停</v-btn>
        <v-btn variant="text" color="success" size="small" class="batch-resume-btn" :disabled="!selectedIds.length" @click="handleBatchResume">批量恢复</v-btn>
        <v-btn variant="text" color="primary" size="small" :disabled="!selectedIds.length || batchSearchRunning" @click="handleBatchAutoSearch(true)">开启自动补搜</v-btn>
        <v-btn variant="text" size="small" :disabled="!selectedIds.length || batchSearchRunning" @click="handleBatchAutoSearch(false)">关闭自动补搜</v-btn>
        <v-btn variant="text" color="primary" size="small" :disabled="!selectedIds.length || batchSearchRunning" @click="handleBatchSearchMissing">立即补搜</v-btn>
        <v-btn variant="text" color="error" size="small" class="batch-delete-btn" :disabled="!selectedIds.length || batchSearchRunning" @click="handleDelete()">批量删除</v-btn>
        <v-btn variant="text" size="small" class="batch-select-all-btn" @click="toggleSelectAllPage(!isAllPageSelected)">
          {{ isAllPageSelected ? '取消全选' : '全选' }}
        </v-btn>
        <v-btn variant="text" size="small" class="batch-cancel-btn" :disabled="batchSearchRunning" @click="toggleSelectionMode">取消</v-btn>
      </div>
      <!-- 批量立即补搜逐条串行，每条一两分钟，必须看得到进度、停得下来 -->
      <div v-if="batchSearchRunning" class="batch-search-progress">
        <v-progress-linear
          :model-value="batchSearchTotal ? (batchSearchDone / batchSearchTotal) * 100 : 0"
          color="primary"
          height="6"
          rounded
        />
        <span class="batch-search-text">正在补搜 {{ batchSearchDone }}/{{ batchSearchTotal }}</span>
        <v-btn variant="text" size="small" color="warning" @click="abortBatchSearch">中止</v-btn>
      </div>

      <div v-if="loading && taskList.length === 0" class="card-grid card-grid--wide" ref="gridRef">
        <SkeletonCardGrid :count="skeletonCount" variant="poster" />
      </div>
      <div v-else class="card-grid card-grid--wide" ref="gridRef">
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <!-- `--osr-i` 驱动卡片上 .osr-enter 的错位延迟（motion.scss），一屏卡片依次落位 -->
        <SubscriptionCard
          v-for="(item, index) in taskList"
          :key="item.id"
          :item="item"
          :style="{ '--osr-i': index }"
        />
        <v-empty-state v-if="!loading && taskList.length === 0" icon="inbox" title="暂无订阅" />
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
    <SubscriptionDiagnosisDialog />

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
import SubscriptionDiagnosisDialog from '@/components/dialogs/SubscriptionDiagnosisDialog.vue'
import FilterOverrideDialog from './dialogs/FilterOverrideDialog.vue'
import { useGridPageSize } from '@/composables/useGridPageSize'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'
import SkeletonCardGrid from '@/components/skeleton/SkeletonCardGrid.vue'

const router = useRouter()
const route = useRoute()
// 弹窗子组件共享这同一个实例（见 ptSubscriptionContext）
const { showSearch } = useSearchPanel()

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, openSubscribeDialog, showProgressById, handleDelete,
  selectedIds, selectionMode, toggleSelectionMode,
  handleBatchPause, handleBatchResume,
  handleBatchAutoSearch, handleBatchSearchMissing, abortBatchSearch,
  batchSearchRunning, batchSearchDone, batchSearchTotal,
  isAllPageSelected, toggleSelectAllPage } = usePtSubscriptionProvider({ autoLoad: false })

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

/** 排序档位。缺集数只算已播出的缺失/阻塞集，未播出的不算（见后端 airedMissingSql） */
const sortOptions = [
  { title: '默认（最新创建）', value: '' },
  { title: '已播缺集数', value: 'missing' },
  { title: '上次命中时间', value: 'lastMatchTime' },
  { title: '上次搜索时间', value: 'lastSearchTime' },
  { title: '标题', value: 'title' }
]

const AUTO_SEARCH_OPTIONS = [{ title: '已开启', value: '1' }, { title: '未开启', value: '0' }]
const HAS_MISSING_OPTIONS = [{ title: '有已播缺集', value: '1' }]

onMounted(() => {
  const subId = Number(route.query.id)
  if (subId) showProgressById(subId, Number(route.query.episode) || undefined)
})

/** 缺集体检页的路径。菜单没授权时反查不到，页头那个入口就整个不渲染 */
const healthPath = computed(() => getRoutePathForComponent('openlist/ptHealth/index'))
const goHealth = () => {
  if (healthPath.value) router.push({ path: healthPath.value })
}
</script>

<style scoped lang="scss">
.sort-label {
  font-size: 13px;
  color: var(--osr-text-secondary);
  white-space: nowrap;
}

/* 批量立即补搜的进度条：进度 + 「3/26」+ 中止，一行排开 */
.batch-search-progress {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 16px 8px;

  .v-progress-linear {
    flex: 1;
  }
}

.batch-search-text {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-secondary);
  white-space: nowrap;
}
</style>
