<template>
  <div class="page-container">
    <PageHeader
      icon="clapperboard"
      title="STRM 生成记录"
      desc="每个 STRM 文件与字幕的生成结果，失败项可重试或清理网盘源文件"
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
        label="网盘目录"
        placeholder="请输入网盘目录"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        @keyup.enter="handleQuery"
      />
      <v-select
        v-model="queryParams.fileType"
        label="文件类型"
        :items="STRM_FILE_TYPE_OPTIONS"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="status-select"
      />
      <v-select
        v-model="queryParams.strmStatus"
        label="状态"
        :items="STRM_STATUS_OPTIONS"
        item-title="title"
        item-value="value"
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
          <RecordStatusBar v-model="queryParams.strmStatus" :options="STRM_STATUS_OPTIONS" :stats="stats" />
        </div>
        <div class="action-right">
          <v-btn
            variant="text"
            color="primary"
            prepend-icon="refresh-cw"
            :disabled="!failedCount"
            @click="handleRetryAllFailed"
          >
            重试全部失败{{ failedCount ? `（${failedCount}）` : '' }}
          </v-btn>
          <v-btn variant="text" prepend-icon="funnel" @click="showSearch = !showSearch">
            {{ showSearch ? '隐藏搜索' : '显示搜索' }}
          </v-btn>
        </div>
      </div>

      <!-- 选中后才出现：给出「已选 N 项」这个此前完全缺失的反馈。
           批量按钮从 action-bar 挪到这里 —— 常驻一排灰按钮既占地方，又要靠用户
           猜「为什么点不动」；卡片型列表页（订阅/下载记录）本来就是这个形态。 -->
      <div v-if="selectedRows.length" class="batch-toolbar">
        已选 {{ selectedRows.length }} 项
        <v-btn variant="text" size="small" color="primary" :disabled="noneSelected" @click="handleBatchRetry()">
          批量重试
        </v-btn>
        <v-menu>
          <template #activator="{ props: menuProps }">
            <v-btn v-bind="menuProps" variant="text" size="small" color="error" append-icon="chevron-down">危险操作</v-btn>
          </template>
          <v-list density="compact">
            <v-list-item prepend-icon="cloud-off" :disabled="noneSelected" @click="handleBatchRemoveNetDisk()">批量删除网盘文件</v-list-item>
            <v-list-item class="more-actions-danger" prepend-icon="trash-2" :disabled="noneSelected" @click="handleBatchDelete()">批量删除记录</v-list-item>
          </v-list>
        </v-menu>
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
            <div class="file-name" :title="item.strmFileName" @click="openDetail(item)">
              <v-icon :icon="isSubtitleFile(item.strmFileName) ? 'captions' : 'file-video-camera'" size="14" />
              {{ item.strmFileName }}
            </div>
            <div class="file-path" :title="item.strmPath">{{ item.strmPath }}</div>
            <div v-if="item.strmStatus === '0' && item.failReason" class="record-fail-reason" :title="item.failReason">
              {{ item.failReason }}
            </div>
          </div>
        </template>
        <template #item.fileSize="{ item }">
          {{ formatFileSize(item.fileSize) }}
        </template>
        <template #item.strmStatus="{ item }">
          <StatusChip :value="item.strmStatus" enabled-value="1" on-text="成功" off-text="失败" />
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" prepend-icon="refresh-cw" @click="handleRetryOne(item)">
            {{ item.strmStatus === '1' ? '重新生成' : '重试' }}
          </v-btn>
          <v-menu>
            <template #activator="{ props: menuProps }">
              <v-btn v-bind="menuProps" class="more-actions-trigger" variant="text" color="info" size="small" append-icon="chevron-down">更多</v-btn>
            </template>
            <v-list density="compact">
              <v-list-item prepend-icon="eye" @click="openDetail(item)">查看详情</v-list-item>
              <v-list-item prepend-icon="cloud-off" @click="handleRemoveNetDiskOne(item)">删除网盘文件</v-list-item>
              <v-divider class="my-1" />
              <v-list-item class="more-actions-danger" prepend-icon="trash-2" @click="handleDeleteOne(item)">删除记录</v-list-item>
            </v-list>
          </v-menu>
        </template>
      </v-data-table-server>
    </v-card>

    <RecordDetailDrawer v-model="detailOpen" title="STRM 生成记录详情" :fields="detailFields" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusChip from '@/components/StatusChip.vue'
import RecordStatusBar from '@/components/RecordStatusBar.vue'
import RecordDetailDrawer, { type RecordDetailField } from '@/components/RecordDetailDrawer.vue'
import { useStrmRecord, STRM_STATUS_OPTIONS, STRM_FILE_TYPE_OPTIONS, isSubtitleFile } from '@/composables/useStrmRecord'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'
import { useDataTable } from '@/composables/useDataTable'
import { formatFileSize } from '@/composables/useRecordList'

const { showSearch } = useSearchPanel()

const {
  recordList, loading, total, queryParams, stats,
  getList, queryRef, dateStart, dateEnd, handleQuery, resetQuery,
  noneSelected, handleSelectionChange,
  handleRetryOne, handleBatchRetry, handleDeleteOne, handleBatchDelete,
  handleRemoveNetDiskOne, handleBatchRemoveNetDisk, handleRetryAllFailed
} = useStrmRecord()

const headers = [
  { title: '文件信息', key: 'fileInfo', minWidth: '300', sortable: false },
  { title: '大小', key: 'fileSize', align: 'end' as const, width: '100' },
  { title: '状态', key: 'strmStatus', align: 'center' as const, width: '80' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '190', sortable: false }
]

const failedCount = computed(() => stats.value?.['0'] ?? 0)

// 表格接线（选中承接 / 翻页 / 换页长 / 表头排序）统一在 useDataTable 里，见该文件注释
const { selectedRows, onSelectionChange, clearSelection, onPageChange, onSizeChange, sortBy, onSortChange, itemsPerPageOptions } =
  useDataTable({ queryParams, getList, handleSelectionChange })

const detailOpen = ref(false)
const detailRow = ref<any>(null)
const openDetail = (row: any) => {
  detailRow.value = row
  detailOpen.value = true
}
const detailFields = computed<RecordDetailField[]>(() => {
  const row = detailRow.value
  if (!row) return []
  return [
    { label: '类型', value: isSubtitleFile(row.strmFileName) ? '字幕' : '视频' },
    { label: '状态', value: row.strmStatus === '1' ? '成功' : '失败' },
    { label: '失败原因', value: row.strmStatus === '0' ? row.failReason : null, error: true },
    // strm_path 存的是网盘上的源路径，不是本地 .strm 的输出目录
    { label: '网盘源文件', value: `${row.strmPath}/${row.strmFileName}`, mono: true, copyable: true },
    { label: '文件大小', value: row.fileSize != null ? formatFileSize(row.fileSize) : null },
    { label: '创建时间', value: row.createTime },
    { label: '最后更新', value: row.updateTime }
  ]
})

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
  cursor: pointer;

  &:hover {
    color: var(--osr-primary);
  }

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
