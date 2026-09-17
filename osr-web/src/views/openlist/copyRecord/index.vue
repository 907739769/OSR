<template>
  <div class="page-container">
    <PageHeader
      icon="files"
      title="同步任务记录"
      desc="逐文件的同步结果，失败项可重试或清理网盘文件"
    />

    <!-- Search Panel -->
    <SearchPanel ref="queryRef" :visible="showSearch" @search="handleQuery" @reset="resetQuery">
      <v-text-field
        v-model="queryParams.copySrcPath"
        label="源目录"
        placeholder="请输入源目录"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        @keyup.enter="handleQuery"
      />
      <v-text-field
        v-model="queryParams.copyDstPath"
        label="目标目录"
        placeholder="请输入目标目录"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        @keyup.enter="handleQuery"
      />
      <v-text-field
        v-model="queryParams.copySrcFileName"
        label="源文件名"
        placeholder="请输入源文件名"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        @keyup.enter="handleQuery"
      />
      <v-text-field
        v-model="queryParams.copyDstFileName"
        label="目标名"
        placeholder="请输入目标名"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        @keyup.enter="handleQuery"
      />
      <v-text-field
        v-model="queryParams.copyTaskId"
        label="OpenList 任务 ID"
        placeholder="精确匹配"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        @keyup.enter="handleQuery"
      />
      <v-select
        v-model="queryParams.copyStatus"
        label="状态"
        :items="COPY_STATUS_OPTIONS"
        item-title="title"
        item-value="value"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="status-select"
      />
      <div class="date-range-fields">
        <v-text-field
          v-model="dateStart"
          label="开始日期"
          type="date"
          density="compact"
          variant="outlined"
          hide-details
          class="date-field"
        />
        <span class="date-range-sep">-</span>
        <v-text-field
          v-model="dateEnd"
          label="结束日期"
          type="date"
          density="compact"
          variant="outlined"
          hide-details
          class="date-field"
        />
      </div>
    </SearchPanel>

    <!-- Table Card -->
    <v-card class="table-card">
      <!-- Action Bar -->
      <div class="action-bar">
        <div class="action-left">
          <RecordStatusBar v-model="queryParams.copyStatus" :options="COPY_STATUS_OPTIONS" :stats="stats" />
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
        已选 {{ selectedRows.length }} 项<template v-if="selectedSizeText">，{{ selectedSizeText }}</template>
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
        item-value="copyId"
        return-object
        :model-value="selectedRows"
        class="modern-table modern-table--fixed"
        @update:model-value="onSelectionChange"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
        @update:sort-by="onSortChange"
      >
        <template #item.detail="{ item }">
          <div class="path-box">
            <div class="path-row">
              <span class="path-label path-label--src">源</span>
              <span class="path-name record-detail-link" :title="item.copySrcFileName" @click="openDetail(item)">{{ item.copySrcFileName }}</span>
              <span class="path-text path-text--muted" :title="item.copySrcPath">{{ item.copySrcPath }}</span>
            </div>
            <div class="path-row">
              <span class="path-label path-label--dst">目</span>
              <span class="path-name" :title="item.copyDstFileName">{{ item.copyDstFileName }}</span>
              <span class="path-text path-text--muted" :title="item.copyDstPath">{{ item.copyDstPath }}</span>
            </div>
            <div v-if="canRetryCopy(item.copyStatus) && item.failReason" class="record-fail-reason" :title="item.failReason">
              {{ item.failReason }}
            </div>
          </div>
        </template>
        <template #item.fileSize="{ item }">
          {{ formatFileSize(item.fileSize) }}
        </template>
        <template #item.copyStatus="{ item }">
          <StatusChip :type="getCopyStatusType(item.copyStatus)" :text="getCopyStatusText(item.copyStatus)" :pulse="item.copyStatus === '1'" />
        </template>
        <template #item.actions="{ item }">
          <!-- 只有失败/异常能重试：处理中的由监控收尾，已成功的无事可做，后端也会拒绝 -->
          <v-btn v-if="canRetryCopy(item.copyStatus)" variant="text" color="primary" size="small" prepend-icon="refresh-cw" @click="handleRetryOne(item)">
            重试
          </v-btn>
          <v-btn v-else variant="text" size="small" prepend-icon="eye" @click="openDetail(item)">
            详情
          </v-btn>
          <v-menu>
            <template #activator="{ props: menuProps }">
              <v-btn v-bind="menuProps" class="more-actions-trigger" variant="text" color="info" size="small" append-icon="chevron-down">更多</v-btn>
            </template>
            <v-list density="compact">
              <v-list-item v-if="canRetryCopy(item.copyStatus)" prepend-icon="eye" @click="openDetail(item)">查看详情</v-list-item>
              <v-list-item prepend-icon="cloud-off" @click="handleRemoveNetDiskOne(item)">删除网盘文件</v-list-item>
              <v-divider class="my-1" />
              <v-list-item class="more-actions-danger" prepend-icon="trash-2" @click="handleDeleteOne(item)">删除记录</v-list-item>
            </v-list>
          </v-menu>
        </template>
      </v-data-table-server>
    </v-card>

    <RecordDetailDrawer v-model="detailOpen" title="同步记录详情" :fields="detailFields" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusChip from '@/components/StatusChip.vue'
import RecordStatusBar from '@/components/RecordStatusBar.vue'
import RecordDetailDrawer, { type RecordDetailField } from '@/components/RecordDetailDrawer.vue'
import { useCopyRecord, COPY_STATUS_OPTIONS } from '@/composables/useCopyRecord'
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
  handleRemoveNetDiskOne, handleBatchRemoveNetDisk, handleRetryAllFailed,
  getCopyStatusText, getCopyStatusType, canRetryCopy, selectedSizeText
} = useCopyRecord()

const headers = [
  { title: '复制详情', key: 'detail', minWidth: '300', sortable: false },
  { title: '大小', key: 'fileSize', align: 'end' as const, width: '100' },
  { title: '状态', key: 'copyStatus', align: 'center' as const, width: '80' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '170', sortable: false }
]

// 「重试全部失败」捞的是失败 + 异常两种（与后端 RETRYABLE_STATUSES 一致）
const failedCount = computed(() => (stats.value?.['2'] ?? 0) + (stats.value?.['4'] ?? 0))

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
    { label: '状态', value: getCopyStatusText(row.copyStatus) },
    { label: '失败原因', value: canRetryCopy(row.copyStatus) ? row.failReason : null, error: true },
    { label: '源文件', value: `${row.copySrcPath}/${row.copySrcFileName}`, mono: true, copyable: true },
    { label: '目标文件', value: `${row.copyDstPath}/${row.copyDstFileName}`, mono: true, copyable: true },
    { label: '文件大小', value: row.fileSize != null ? formatFileSize(row.fileSize) : null },
    { label: 'OpenList 任务 ID', value: row.copyTaskId, mono: true, copyable: true },
    { label: '创建时间', value: row.createTime },
    { label: '最后更新', value: row.updateTime }
  ]
})

getList()
</script>

<style scoped lang="scss">
.record-detail-link {
  cursor: pointer;

  &:hover {
    color: var(--osr-primary);
  }
}
</style>
