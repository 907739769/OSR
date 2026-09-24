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
          v-model="queryParams.autoSearch"
          :items="[{ title: '已开启', value: '1' }, { title: '未开启', value: '0' }]"
          label="自动补搜"
          placeholder="不限"
          clearable
          density="compact"
          variant="outlined"
          hide-details
        />
        <v-select
          v-model="queryParams.hasMissing"
          :items="[{ title: '有已播缺集', value: '1' }]"
          label="缺集"
          placeholder="不限"
          clearable
          density="compact"
          variant="outlined"
          hide-details
        />
        <v-select
          v-if="ownerFilterVisible"
          v-model="queryParams.ownerFilter"
          :items="ownerItems"
          label="归属"
          placeholder="不限"
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
        <v-btn variant="text" color="warning" size="small" :disabled="!selectedIds.length || batchSearchRunning" @click="handleBatchPause">批量暂停</v-btn>
        <v-btn variant="text" color="success" size="small" :disabled="!selectedIds.length || batchSearchRunning" @click="handleBatchResume">批量恢复</v-btn>
        <v-btn variant="text" color="primary" size="small" :disabled="!selectedIds.length || batchSearchRunning" @click="handleBatchAutoSearch(true)">开启补搜</v-btn>
        <v-btn variant="text" size="small" :disabled="!selectedIds.length || batchSearchRunning" @click="handleBatchAutoSearch(false)">关闭补搜</v-btn>
        <v-btn variant="text" color="primary" size="small" :disabled="!selectedIds.length || batchSearchRunning" @click="handleBatchSearchMissing">立即补搜</v-btn>
        <v-btn variant="text" color="error" size="small" :disabled="!selectedIds.length || batchSearchRunning" @click="handleDelete()">批量删除</v-btn>
      </MobileBatchBar>

      <!-- 批量立即补搜逐条串行，每条一两分钟，理由同 PC 端 -->
      <div v-if="batchSearchRunning" class="batch-search-progress">
        <v-progress-linear
          :model-value="batchSearchTotal ? (batchSearchDone / batchSearchTotal) * 100 : 0"
          color="primary"
          height="6"
          rounded
        />
        <span class="batch-search-text">{{ batchSearchDone }}/{{ batchSearchTotal }}</span>
        <v-btn variant="text" size="small" color="warning" @click="abortBatchSearch">中止</v-btn>
      </div>

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
        <!-- 只给电影：对账只升不降，把影片从媒体库删掉后再点上面那条「对账」不会有任何变化，
             重下得从这里把它退回缺失。剧集逐集重置在进度弹窗里。
             在途也给——种子下完但上传网盘/STRM/刮削卡住时状态永远停在在途，
             对账碰不到它、卡死清扫对「文件已确认」的集只告警不退回，没有这条就没有出口 -->
        <v-btn
          v-if="sheetTarget.mediaType === 'MOVIE' && (sheetTarget.inLibraryCount || sheetTarget.inFlightCount)"
          color="warning"
          block
          @click="run(() => handleResetMovie(sheetTarget))"
        >{{ sheetTarget.inLibraryCount ? '重置为未入库' : '重置为缺失' }}</v-btn>
        <!-- 缺集体检只收订阅中的剧集（电影整体不参与），理由同 PC 端 -->
        <v-btn
          v-if="healthPath && sheetTarget.mediaType !== 'MOVIE' && sheetTarget.status === 'ACTIVE'"
          block
          @click="run(() => goHealthFor(sheetTarget))"
        >缺集诊断</v-btn>
        <v-btn block @click="run(() => showDiagnosis(sheetTarget))">一键诊断</v-btn>
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

      <SubscriptionDiagnosisDialog />

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
import SubscriptionDiagnosisDialog from '@/components/dialogs/SubscriptionDiagnosisDialog.vue'
import FilterOverrideDialog from './dialogs/FilterOverrideDialog.vue'
import { useActionSheet } from '@/composables/useActionSheet'
import { useMobilePageAction } from '@/composables/useMobilePageAction'

const route = useRoute()
const router = useRouter()

// 弹窗子组件共享这同一个实例（见 ptSubscriptionContext）
const {
  taskList, loading, total, queryParams,
  handleQuery, resetQuery, openSubscribeDialog, showProgressById, showSearchLogs, showDiagnosis,
  openFilterOverride,
  openSeasonSearch,
  handleRefresh, handlePause, handleResume, handleRemove, handleResetMovie,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed,
  selectionMode, toggleSelectionMode, isAllPageSelected, toggleSelectAllPage,
  selectedIds, handleBatchPause, handleBatchResume, handleDelete,
  handleBatchAutoSearch, handleBatchSearchMissing, abortBatchSearch,
  batchSearchRunning, batchSearchDone, batchSearchTotal,
  ownerItems, ownerFilterVisible
} = usePtSubscriptionProvider()

/** 排序档位，与 PC 端同一份取值 */
const sortOptions = [
  { title: '默认（最新创建）', value: '' },
  { title: '已播缺集数', value: 'missing' },
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
/** 只看这一条订阅的缺集诊断 */
const goHealthFor = (row: any) => {
  if (healthPath.value) router.push({ path: healthPath.value, query: { subId: row.id } })
}

// 从下载记录页点订阅名跳过来时带 ?id=，直接弹出该订阅的进度（与 PC 端一致）
onMounted(() => {
  const subId = Number(route.query.id)
  if (subId) showProgressById(subId, Number(route.query.episode) || undefined)
})

// 新增按钮并在悬浮底栏右侧（原先是压在内容上的右下角悬浮按钮），见 useMobilePageAction
useMobilePageAction(() => ({ icon: 'plus', label: '新增订阅', onClick: () => openSubscribeDialog() }))
</script>

<style scoped lang="scss">
.list-toolbar {
  display: flex;
  justify-content: flex-end;
}

.batch-search-progress {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 4px 8px;

  .v-progress-linear {
    flex: 1;
  }
}

.batch-search-text {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-secondary);
}
</style>
