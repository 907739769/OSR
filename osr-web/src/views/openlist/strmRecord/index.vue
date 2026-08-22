<template>
  <div class="page-container">
    <PageHeader
      icon="clapperboard"
      title="STRM 生成记录"
      desc="每个 STRM 文件的生成结果，失败项可重试或清理网盘源文件"
    />

    <!-- Search Panel -->
    <SearchPanel ref="queryRef" :visible="showSearch" @search="handleQuery" @reset="resetQuery">
      <v-text-field
        v-model="queryParams.strmFileName"
        label="文件名称"
        placeholder="请输入文件名称"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        @keyup.enter="handleQuery"
      />
      <v-text-field
        v-model="queryParams.strmPath"
        label="目录路径"
        placeholder="请输入目录路径"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        @keyup.enter="handleQuery"
      />
      <v-select
        v-model="queryParams.strmStatus"
        label="状态"
        :items="[{ title: '成功', value: '1' }, { title: '失败', value: '0' }]"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="status-select"
      />
      <v-text-field
        v-model="dateStart"
        label="开始时间"
        type="date"
        density="compact"
        variant="outlined"
        hide-details
        class="date-field"
      />
      <v-text-field
        v-model="dateEnd"
        label="结束时间"
        type="date"
        density="compact"
        variant="outlined"
        hide-details
        class="date-field"
      />
    </SearchPanel>

    <!-- Table Card -->
    <v-card class="table-card">
      <!-- Action Bar -->
      <div class="action-bar">
        <div class="action-left">
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
        <v-btn variant="text" size="small" color="error" :disabled="noneSelected" @click="handleBatchDelete()">
          批量删除记录
        </v-btn>
        <v-btn variant="text" size="small" color="error" :disabled="noneSelected" @click="handleBatchRemoveNetDisk()">
          批量删除网盘文件
        </v-btn>
        <v-btn variant="text" size="small" color="primary" :disabled="noneSelected" @click="handleBatchRetry()">
          批量重试
        </v-btn>
        <v-spacer />
        <v-btn variant="text" size="small" class="batch-clear-btn" @click="clearSelection">清空选择</v-btn>
      </div>

      <!-- Desktop Table -->
      <v-data-table-server
        :loading="loading"
        :items="recordList"
        :items-length="total"
        :headers="headers"
        :items-per-page="queryParams.pageSize"
        :items-per-page-options="itemsPerPageOptions"
        :page="queryParams.pageNum"
        :sort-by="sortBy"
        show-select
        item-value="strmId"
        return-object
        :model-value="selectedRows"
        class="modern-table modern-table--fixed"
        @update:model-value="onSelectionChange"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
        @update:sort-by="onSortChange"
      >
        <template #item.fileInfo="{ item }">
          <div class="file-info-box">
            <div class="file-name" :title="item.strmFileName">
              <v-icon icon="file-video-camera" size="14" />
              {{ item.strmFileName }}
            </div>
            <div class="file-path" :title="item.strmPath">{{ item.strmPath }}</div>
          </div>
        </template>
        <template #item.strmStatus="{ item }">
          <StatusChip :value="item.strmStatus" enabled-value="1" on-text="成功" off-text="失败" />
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" prepend-icon="refresh-cw" @click="handleRetryOne(item)">
            重试
          </v-btn>
          <v-menu>
            <template #activator="{ props: menuProps }">
              <v-btn v-bind="menuProps" class="more-actions-trigger" variant="text" color="info" size="small" append-icon="chevron-down">更多</v-btn>
            </template>
            <v-list density="compact">
              <v-list-item prepend-icon="download" @click="handleRemoveNetDiskOne(item)">删除网盘文件</v-list-item>
              <v-divider class="my-1" />
              <v-list-item class="more-actions-danger" prepend-icon="trash-2" @click="handleDeleteOne(item)">删除记录</v-list-item>
            </v-list>
          </v-menu>
        </template>
      </v-data-table-server>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useStrmRecord } from '@/composables/useStrmRecord'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'
import { useDataTable } from '@/composables/useDataTable'

const { showSearch } = useSearchPanel()

const {
  recordList, loading, total, queryParams,
  getList, queryRef, dateStart, dateEnd, handleQuery, resetQuery,
  noneSelected, handleSelectionChange,
  handleRetryOne, handleBatchRetry, handleDeleteOne, handleBatchDelete,
  handleRemoveNetDiskOne, handleBatchRemoveNetDisk
} = useStrmRecord()

const headers = [
  { title: '文件信息', key: 'fileInfo', minWidth: '300', sortable: false },
  { title: '状态', key: 'strmStatus', align: 'center' as const, width: '80' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '170', sortable: false }
]

// 表格接线（选中承接 / 翻页 / 换页长 / 表头排序）统一在 useDataTable 里，见该文件注释
const { selectedRows, onSelectionChange, clearSelection, onPageChange, onSizeChange, sortBy, onSortChange, itemsPerPageOptions } =
  useDataTable({ queryParams, getList, handleSelectionChange })

getList()
</script>

<style scoped lang="scss">
/* 表格「文件信息」列：文件名 + 目录路径两行 */
.file-info-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.file-name {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 500;
  color: var(--osr-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  .v-icon {
    color: var(--osr-text-secondary);
    flex-shrink: 0;
  }
}

.file-path {
  font-size: 12px;
  color: var(--osr-text-placeholder);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
