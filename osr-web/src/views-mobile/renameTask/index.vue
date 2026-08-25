<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && taskList.length === 0"
    empty-icon="inbox"
    empty-title="暂无重命名任务"
  >
    <template #head>
      <!-- 搜索 -->
      <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
        <v-form ref="queryRef">
          <v-text-field
            v-model="queryParams.sourceFolder"
            label="源目录"
            placeholder="请输入源目录"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-text-field
            v-model="queryParams.targetRoot"
            label="目标目录"
            placeholder="请输入目标目录"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-select
            v-model="queryParams.status"
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
        <v-btn variant="text" color="primary" size="small" prepend-icon="play" @click="handleBatchExecute">
          批量执行
        </v-btn>
        <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleBatchDelete">
          批量删除
        </v-btn>
      </MobileBatchBar>

      <!-- Add Button (FAB) -->
      <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="plus" @click="handleAdd">
        新增
      </v-btn>

      <!-- Task List -->
    </template>

    <v-card
      v-for="task in taskList"
      :key="task.id"
      class="task-card"
      :class="{ selected: selectedIds.includes(task.id) }"
      @click="handleCardClick($event, task.id)"
    >
      <div class="card-checkbox">
        <v-checkbox-btn
          :model-value="selectedIds.includes(task.id)"
          density="compact"
          @click.stop="toggleSelect(task.id)"
        />
      </div>
      <div class="card-content">
        <div class="card-top">
          <div class="card-title-row">
            <v-icon class="card-title-icon" icon="map-pin" size="18" />
            <span class="card-title card-title--link" @click.stop="showFullText(task.sourceFolder, '源目录')">{{ task.sourceFolder }}</span>
          </div>
          <StatusChip :value="task.status" />
        </div>
        <div class="card-path card-path--link" @click.stop="showFullText(task.targetRoot, '目标目录')">
          <v-icon class="card-path-icon" icon="map-pin" size="14" />
          <span class="card-path-text">{{ task.targetRoot }}</span>
        </div>
        <div class="card-time">
          <v-icon icon="clock" size="12" />
          {{ task.createTime }}
        </div>
        <div class="card-actions" @click.stop>
          <v-btn variant="text" color="primary" size="small" prepend-icon="circle-play" @click="handleExecuteOne(task)">执行</v-btn>
          <v-btn variant="text" color="primary" size="small" prepend-icon="square-pen" @click="handleUpdate(task)">修改</v-btn>
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

      <!-- Add/Edit Dialog -->
      <!-- 新增/编辑弹窗（两端共用） -->
      <RenameTaskFormDialog />
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import { ref } from 'vue'
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileActionSheet from '@/components/mobile/MobileActionSheet.vue'
import MobileBatchBar from '@/components/mobile/MobileBatchBar.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import FullTextDialog from '@/components/mobile/FullTextDialog.vue'
import { useRenameTask } from '@/composables/useRenameTask'
import { usePageStateProvider } from '@/composables/pageStateContext'
import { useActionSheet } from '@/composables/useActionSheet'
import RenameTaskFormDialog from '@/components/dialogs/RenameTaskFormDialog.vue'

// 表单弹窗与 PC 端共用一份（components/dialogs/），它靠 usePageStateProvider 取同一份状态
const {
  taskList, loading, total, queryParams, totalPages,
  prevPage, nextPage, handleSizeChange,
  queryRef, handleQuery, resetQuery, searchCollapsed,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  isAllPageSelected, toggleSelectAllPage,
  handleAdd, handleUpdate, handleDelete,
  handleExecuteOne, handleBatchExecute, handleBatchDelete
} = usePageStateProvider(useRenameTask())

const fullTextRef = ref<InstanceType<typeof FullTextDialog>>()
const showFullText = (content: string, title: string) => fullTextRef.value?.show(content, title)

/** 卡片「更多」动作面板：开关状态与「执行完自动关闭」都在 useActionSheet 里 */
const { sheetOpen, sheetTarget, openSheet, run } = useActionSheet()
</script>
