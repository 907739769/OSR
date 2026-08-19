<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-file-remove-outline"
      title="重命名一致性检查"
      desc="双向扫描：记录指向的文件是否还在，以及库里的文件是否还有记录。可清理或忽略"
    />

    <!-- Search Panel -->
    <SearchPanel ref="queryRef" :visible="showSearch" @search="handleQuery" @reset="resetQuery">
      <v-text-field
        v-model="queryParams.title"
        label="影视名称"
        placeholder="请输入影视名称"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        @keyup.enter="handleQuery"
      />
      <v-select
        v-model="queryParams.reason"
        label="原因"
        :items="REASON_OPTIONS"
        placeholder="全部原因"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="field-md"
      />
      <v-select
        v-model="queryParams.status"
        label="状态"
        :items="[{ title: '待处理', value: '0' }, { title: '已清理', value: '1' }, { title: '已忽略', value: '2' }]"
        placeholder="全部状态"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="status-select"
      />
    </SearchPanel>

    <!-- Table Card -->
    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="primary" prepend-icon="mdi-refresh" :loading="scanning" @click="handleScanNow">
            立即扫描
          </v-btn>
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
        <v-btn variant="text" size="small" color="error" :disabled="noneSelected" @click="handleBatchClean()">
          批量清理
        </v-btn>
        <v-btn variant="text" size="small" color="warning" :disabled="noneSelected" @click="handleBatchIgnore()">
          批量忽略
        </v-btn>
        <v-spacer />
        <v-btn variant="text" size="small" class="batch-clear-btn" @click="clearSelection">清空选择</v-btn>
      </div>

      <v-data-table-server
        :loading="loading"
        :items="recordList"
        :items-length="total"
        :headers="headers"
        :items-per-page="queryParams.pageSize"
        :page="queryParams.pageNum"
        show-select
        item-value="id"
        return-object
        :model-value="selectedRows"
        class="modern-table"
        @update:model-value="onSelectionChange"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
      >
        <template #item.title="{ item }">
          <span>{{ item.title || '未知' }}</span>
          <span v-if="item.year" class="orphan-year">（{{ item.year }}）</span>
        </template>
        <template #item.reason="{ item }">
          <StatusChip :type="REASON_META[item.reason]?.type || 'info'" :text="REASON_META[item.reason]?.text || item.reason" />
        </template>
        <template #item.path="{ item }">
          <span class="orphan-path" :title="fullPath(item)">{{ fullPath(item) }}</span>
        </template>
        <template #item.status="{ item }">
          <StatusChip v-if="item.status === '0'" type="info" text="待处理" />
          <StatusChip v-else-if="item.status === '1'" type="success" text="已清理" />
          <StatusChip v-else type="info" text="已忽略" />
        </template>
        <template #item.actions="{ item }">
          <v-btn v-if="item.status === '0'" variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleCleanOne(item)">
            清理
          </v-btn>
          <v-btn v-if="item.status === '0'" variant="text" color="warning" size="small" prepend-icon="mdi-alert-outline" @click="handleIgnoreOne(item)">
            忽略
          </v-btn>
        </template>
      </v-data-table-server>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useRenameOrphanList, REASON_META, REASON_OPTIONS, fullPath } from '@/composables/useRenameOrphanList'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'
import { useDataTable } from '@/composables/useDataTable'

const { showSearch } = useSearchPanel()

const {
  recordList, loading, total, queryParams,
  getList, queryRef, handleQuery, resetQuery,
  noneSelected, handleSelectionChange,
  handleCleanOne, handleBatchClean,
  scanning, handleScanNow,
  handleIgnoreOne, handleBatchIgnore
} = useRenameOrphanList()

const headers = [
  { title: '标题', key: 'title', minWidth: '200' },
  { title: '原因', key: 'reason', align: 'center' as const, width: '130' },
  { title: '重命名后路径', key: 'path', minWidth: '320', sortable: false },
  { title: '状态', key: 'status', align: 'center' as const, width: '90' },
  { title: '发现时间', key: 'foundTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '180', sortable: false }
]

// 表格接线（选中承接 / 翻页 / 换页长）统一在 useDataTable 里，见该文件注释
const { selectedRows, onSelectionChange, clearSelection, onPageChange, onSizeChange } =
  useDataTable({ queryParams, getList, handleSelectionChange })

getList()
</script>

<style scoped lang="scss">
.orphan-year {
  color: var(--osr-text-secondary);
  font-size: 12px;
}

.orphan-path {
  color: var(--osr-text-secondary);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
}
</style>
