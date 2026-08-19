<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-file-multiple-outline"
      title="文件同步记录"
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
      <v-select
        v-model="queryParams.copyStatus"
        label="状态"
        :items="[{ title: '处理中', value: '1' }, { title: '失败', value: '2' }, { title: '成功', value: '3' }, { title: '未知', value: '4' }]"
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
        </div>
        <v-btn variant="text" prepend-icon="mdi-filter-outline" @click="showSearch = !showSearch">
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
        :page="queryParams.pageNum"
        show-select
        item-value="copyId"
        return-object
        :model-value="selectedRows"
        class="modern-table modern-table--fixed"
        @update:model-value="onSelectionChange"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
      >
        <template #item.detail="{ item }">
          <div class="path-box">
            <div class="path-row">
              <span class="path-label path-label--src">源</span>
              <span class="path-name" :title="item.copySrcFileName">{{ item.copySrcFileName }}</span>
              <span class="path-text path-text--muted" :title="item.copySrcPath">{{ item.copySrcPath }}</span>
            </div>
            <div class="path-row">
              <span class="path-label path-label--dst">目</span>
              <span class="path-name" :title="item.copyDstFileName">{{ item.copyDstFileName }}</span>
              <span class="path-text path-text--muted" :title="item.copyDstPath">{{ item.copyDstPath }}</span>
            </div>
          </div>
        </template>
        <template #item.copyStatus="{ item }">
          <StatusChip :type="getCopyStatusType(item.copyStatus)" :text="getCopyStatusText(item.copyStatus)" />
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-refresh" @click="handleRetryOne(item)">
            重试
          </v-btn>
          <v-menu>
            <template #activator="{ props: menuProps }">
              <v-btn v-bind="menuProps" class="more-actions-trigger" variant="text" color="info" size="small" append-icon="mdi-chevron-down">更多</v-btn>
            </template>
            <v-list density="compact">
              <v-list-item prepend-icon="mdi-download-off-outline" @click="handleRemoveNetDiskOne(item)">删除网盘文件</v-list-item>
              <v-divider class="my-1" />
              <v-list-item class="more-actions-danger" prepend-icon="mdi-delete-outline" @click="handleDeleteOne(item)">删除记录</v-list-item>
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
import { useCopyRecord } from '@/composables/useCopyRecord'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'
import { useDataTable } from '@/composables/useDataTable'

const { showSearch } = useSearchPanel()

const {
  recordList, loading, total, queryParams,
  getList, queryRef, dateStart, dateEnd, handleQuery, resetQuery,
  noneSelected, handleSelectionChange,
  handleRetryOne, handleBatchRetry, handleDeleteOne, handleBatchDelete,
  handleRemoveNetDiskOne, handleBatchRemoveNetDisk,
  getCopyStatusText, getCopyStatusType
} = useCopyRecord()

const headers = [
  { title: '复制详情', key: 'detail', minWidth: '300' },
  { title: '状态', key: 'copyStatus', align: 'center' as const, width: '80' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '170', sortable: false }
]

// 表格接线（选中承接 / 翻页 / 换页长）统一在 useDataTable 里，见该文件注释
const { selectedRows, onSelectionChange, clearSelection, onPageChange, onSizeChange } =
  useDataTable({ queryParams, getList, handleSelectionChange })


getList()
</script>
