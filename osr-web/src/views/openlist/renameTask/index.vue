<template>
  <div class="page-container">
    <PageHeader
      icon="text-cursor-input"
      title="重命名任务配置"
      desc="配置需要按 TMDb 刮削结果重命名的目录，支持同时生成 NFO 与图片"
    />

    <!-- Search Panel -->
    <SearchPanel ref="queryRef" :visible="showSearch" @search="handleQuery" @reset="resetQuery">
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
        :items="[{ title: '启用', value: '1' }, { title: '停用', value: '0' }]"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="status-select"
      />
    </SearchPanel>

    <!-- Table Card -->
    <v-card class="table-card">
      <!-- Action Bar -->
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="primary" prepend-icon="plus" @click="handleAdd">
            新增
          </v-btn>
        </div>
        <v-btn variant="text" prepend-icon="funnel" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <!-- 选中后才出现：给出「已选 N 项」这个此前完全缺失的反馈。
           批量按钮从 action-bar 挪到这里 —— 常驻一排灰按钮既占地方，又要靠用户
           猜「为什么点不动」；卡片型列表页（订阅/下载记录）本来就是这个形态。 -->
      <div v-if="selectedRows.length" class="batch-toolbar">
        已选 {{ selectedRows.length }} 项
        <v-btn variant="text" size="small" color="success" :disabled="notOneSelected" @click="handleUpdate()">
          修改
        </v-btn>
        <v-btn variant="text" size="small" color="error" :disabled="noneSelected" @click="handleDelete()">
          批量删除
        </v-btn>
        <v-btn variant="text" size="small" color="warning" :disabled="noneSelected" @click="handleExecute()">
          批量执行
        </v-btn>
        <v-spacer />
        <v-btn variant="text" size="small" class="batch-clear-btn" @click="clearSelection">清空选择</v-btn>
      </div>

      <!-- Desktop Table -->
      <v-data-table-server
        :loading="loading"
        :items="taskList"
        :items-length="total"
        :headers="headers"
        :items-per-page="queryParams.pageSize"
        :items-per-page-options="itemsPerPageOptions"
        :page="queryParams.pageNum"
        :sort-by="sortBy"
        show-select
        item-value="id"
        return-object
        :model-value="selectedRows"
        class="modern-table modern-table--fixed"
        @update:model-value="onSelectionChange"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
        @update:sort-by="onSortChange"
      >
        <template #item.config="{ item }">
          <div class="path-box">
            <div class="path-row"><span class="path-label path-label--src">源</span> <span class="path-text">{{ item.sourceFolder }}</span></div>
            <div class="path-row"><span class="path-label path-label--dst">目</span> <span class="path-text">{{ item.targetRoot }}</span></div>
          </div>
        </template>
        <template #item.status="{ item }">
          <StatusChip :value="item.status" />
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" prepend-icon="square-pen" @click="handleUpdate(item)">
            修改
          </v-btn>
          <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(item)">
            删除
          </v-btn>
          <v-btn variant="text" color="primary" size="small" prepend-icon="play" @click="handleExecuteOne(item)">
            执行
          </v-btn>
        </template>
      </v-data-table-server>
    </v-card>

    <!-- Add/Edit Dialog -->
    <RenameTaskFormDialog />

  </div>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useRenameTask } from '@/composables/useRenameTask'
import { usePageStateProvider } from '@/composables/pageStateContext'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'
import { useDataTable } from '@/composables/useDataTable'
import RenameTaskFormDialog from '@/components/dialogs/RenameTaskFormDialog.vue'

const { showSearch } = useSearchPanel()

// 表单弹窗与移动端共用一份（components/dialogs/），它靠 usePageStateProvider 取同一份状态
const {
  taskList, loading, total, queryParams,
  getList, queryRef, handleQuery, resetQuery,
  notOneSelected, noneSelected, handleSelectionChange,
  handleAdd, handleUpdate, handleDelete,
  handleExecuteOne, handleBatchExecute: handleExecute
} = usePageStateProvider(useRenameTask())

const headers = [
  { title: '任务路径配置', key: 'config', minWidth: '300', sortable: false },
  { title: '状态', key: 'status', align: 'center' as const, width: '80' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '260', sortable: false }
]

// 表格接线（选中承接 / 翻页 / 换页长 / 表头排序）统一在 useDataTable 里，见该文件注释
const { selectedRows, onSelectionChange, clearSelection, onPageChange, onSizeChange, sortBy, onSortChange, itemsPerPageOptions } =
  useDataTable({ queryParams, getList, handleSelectionChange })
</script>

<style scoped lang="scss">
/* 公共布局（.page-container/.search-card/.table-card 等）由全局 styles/list.scss 统一提供 */

/* .section-label 已随表单弹窗搬进 components/dialogs/RenameTaskFormDialog.vue */
</style>
