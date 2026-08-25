<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && taskList.length === 0"
    empty-icon="inbox"
    empty-title="暂无STRM任务"
  >
    <template #head>
      <!-- 搜索 -->
      <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
        <v-form ref="queryRef">
          <v-text-field
            v-model="queryParams.strmTaskPath"
            label="STRM目录"
            placeholder="请输入STRM目录"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-select
            v-model="queryParams.strmTaskStatus"
            label="状态"
            placeholder="全部状态"
            :items="[{ title: '启用', value: '1' }, { title: '停用', value: '0' }]"
            clearable
            density="compact"
            variant="outlined"
            hide-details
          />
        </v-form>
      </MobileSearchPanel>

      <!-- Batch Actions -->
      <MobileBatchBar
        :visible="selectedIds.length > 0"
        :count="selectedIds.length"
        :all-selected="isAllPageSelected"
        @toggle-all="toggleSelectAllPage"
        @cancel="clearSelection"
      >
        <v-btn variant="text" color="primary" size="small" prepend-icon="circle-play" @click="handleBatchExecute">
          批量执行
        </v-btn>
        <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(undefined, `是否确认删除选中的 ${selectedIds.length} 个STRM任务？`)">
          批量删除
        </v-btn>
      </MobileBatchBar>

      <!-- Add Button (FAB) -->
      <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="plus" @click="handleAdd('新增STRM任务')">
        新增
      </v-btn>

      <!-- Task List -->
    </template>

    <v-card
      v-for="task in taskList"
      :key="task.strmTaskId"
      class="task-card"
      :class="{ selected: selectedIds.includes(task.strmTaskId) }"
      @click="handleCardClick($event, task.strmTaskId)"
    >
      <div class="card-checkbox">
        <v-checkbox-btn
          :model-value="selectedIds.includes(task.strmTaskId)"
          density="compact"
          @click.stop="toggleSelect(task.strmTaskId)"
        />
      </div>
      <div class="card-content">
        <div class="card-top">
          <div class="card-title-row">
            <v-icon class="card-title-icon" icon="file-video-camera" size="18" />
            <span class="card-title card-title--link" @click.stop="showFullText(task.strmTaskPath, 'STRM目录')">{{ task.strmTaskPath }}</span>
          </div>
          <v-chip v-if="hasOverride(task)" size="x-small" color="primary" variant="tonal">已覆盖</v-chip>
          <StatusChip :value="task.strmTaskStatus" />
        </div>
        <div class="card-time">
          <v-icon icon="clock" size="14" />
          {{ task.createTime }}
        </div>
        <div class="card-actions" @click.stop>
          <v-btn variant="text" color="primary" size="small" prepend-icon="circle-play" @click="handleExecuteOne(task, `是否确认执行STRM任务“${task.strmTaskPath}”？`)">执行</v-btn>
          <v-btn variant="text" color="primary" size="small" prepend-icon="square-pen" @click="handleUpdate(task, '修改STRM任务')">修改</v-btn>
          <v-btn class="action-more" variant="text" color="default" size="small" icon="ellipsis" @click="openSheet(task)" />
        </div>
      </div>
    </v-card>

    <template #foot>
      <!-- 操作抽屉 -->
      <MobileActionSheet v-model="sheetOpen" :target="sheetTarget">
        <v-btn color="error" block prepend-icon="trash-2" @click="run(() => handleDelete(sheetTarget))">删除</v-btn>
      </MobileActionSheet>

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

      <!-- 全文查看 -->
      <FullTextDialog ref="fullTextRef" />

      <!-- 新增/编辑弹窗（两端共用） -->
      <StrmTaskFormDialog />
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import { ref, watch } from 'vue'
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileActionSheet from '@/components/mobile/MobileActionSheet.vue'
import MobileBatchBar from '@/components/mobile/MobileBatchBar.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import FullTextDialog from '@/components/mobile/FullTextDialog.vue'
import { useStrmTask } from '@/composables/useStrmTask'
import { usePageStateProvider } from '@/composables/pageStateContext'
import { useDebounce } from '@/composables/useDebounce'
import { useActionSheet } from '@/composables/useActionSheet'
import StrmTaskFormDialog from '@/components/dialogs/StrmTaskFormDialog.vue'

// 表单弹窗与 PC 端共用一份（components/dialogs/），它靠 usePageStateProvider 取同一份状态
const {
  taskList, loading, total, queryParams, queryRef,
  getList, handleQuery, resetQuery,
  selectedIds,
  handleAdd, handleUpdate,
  handleDelete, handleExecuteOne,
  hasOverride,
  toggleSelect, handleCardClick, clearSelection,
  isAllPageSelected, toggleSelectAllPage,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed, handleBatchExecute
} = usePageStateProvider(useStrmTask())

const fullTextRef = ref<InstanceType<typeof FullTextDialog>>()
const showFullText = (content: string, title: string) => fullTextRef.value?.show(content, title)

/** 卡片「更多」动作面板：开关状态与「执行完自动关闭」都在 useActionSheet 里 */
const { sheetOpen, sheetTarget, openSheet, run } = useActionSheet()

// 搜索输入防抖：输入停止 300ms 后自动触发搜索
const debouncedSearch = useDebounce(() => {
  queryParams.pageNum = 1
  getList()
}, 300)

watch(
  () => [queryParams.strmTaskPath, queryParams.strmTaskStatus],
  () => debouncedSearch()
)
</script>
