<template>
  <div class="page-container">
    <!-- Search Panel -->
    <v-card v-if="showSearch" class="search-card">
      <v-form ref="queryRef" @submit.prevent="handleQuery">
        <div class="search-fields">
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
          <div class="search-actions">
            <v-btn color="primary" prepend-icon="mdi-magnify" @click="handleQuery">搜索</v-btn>
            <v-btn variant="outlined" prepend-icon="mdi-refresh" @click="resetQuery">重置</v-btn>
          </div>
        </div>
      </v-form>
    </v-card>

    <!-- Table Card -->
    <v-card class="table-card">
      <!-- Action Bar -->
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="error" prepend-icon="mdi-delete-outline" :disabled="multiple" @click="handleBatchDelete()">
            批量删除记录
          </v-btn>
          <v-btn color="error" prepend-icon="mdi-download-off-outline" :disabled="multiple" @click="handleBatchRemoveNetDisk()">
            批量删除网盘文件
          </v-btn>
          <v-btn color="primary" prepend-icon="mdi-refresh" :disabled="multiple" @click="handleBatchRetry()">
            批量重试
          </v-btn>
        </div>
        <v-btn variant="text" prepend-icon="mdi-filter-outline" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
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
        class="modern-table"
        @update:model-value="onSelectionChange"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
      >
        <template #item.detail="{ item }">
          <div class="file-change-box">
            <div class="file-row">
              <span class="file-label label-src">源</span>
              <span class="file-name" :title="item.copySrcFileName">{{ item.copySrcFileName }}</span>
              <span class="file-path" :title="item.copySrcPath">{{ item.copySrcPath }}</span>
            </div>
            <div class="file-row">
              <span class="file-label label-dst">目</span>
              <span class="file-name" :title="item.copyDstFileName">{{ item.copyDstFileName }}</span>
              <span class="file-path" :title="item.copyDstPath">{{ item.copyDstPath }}</span>
            </div>
          </div>
        </template>
        <template #item.copyStatus="{ item }">
          <v-chip size="small" :color="getCopyStatusType(item.copyStatus)" variant="tonal">
            {{ getCopyStatusText(item.copyStatus) }}
          </v-chip>
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-refresh" @click="handleRetryOne(item)">
            重试
          </v-btn>
          <v-btn variant="text" color="warning" size="small" prepend-icon="mdi-download-off-outline" @click="handleRemoveNetDiskOne(item)">
            删除网盘文件
          </v-btn>
          <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDeleteOne(item)">
            删除记录
          </v-btn>
        </template>
      </v-data-table-server>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useCopyRecord } from '@/composables/useCopyRecord'
import { useDebounce } from '@/composables/useDebounce'

const showSearch = ref(window.innerWidth >= 768)

const {
  recordList, loading, total, queryParams,
  getList, queryRef, dateRange, handleQuery, resetQuery,
  multiple, handleSelectionChange,
  handleRetryOne, handleBatchRetry, handleDeleteOne, handleBatchDelete,
  handleRemoveNetDiskOne, handleBatchRemoveNetDisk,
  getCopyStatusText, getCopyStatusType
} = useCopyRecord()

// dateRange 是 el-date-picker daterange 遗留的 [start, end] 数组结构，
// 拆成两个独立日期输入框绑定，写回时仍保持数组形状供 handleQuery 组装 params
const dateStart = computed({
  get: () => dateRange.value?.[0] ?? '',
  set: (val: string) => {
    dateRange.value = [val || '', dateRange.value?.[1] ?? '']
    if (!dateRange.value[0] && !dateRange.value[1]) dateRange.value = null
  }
})
const dateEnd = computed({
  get: () => dateRange.value?.[1] ?? '',
  set: (val: string) => {
    dateRange.value = [dateRange.value?.[0] ?? '', val || '']
    if (!dateRange.value[0] && !dateRange.value[1]) dateRange.value = null
  }
})

const headers = [
  { title: '复制详情', key: 'detail', minWidth: '300' },
  { title: '状态', key: 'copyStatus', align: 'center' as const, width: '80' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '260', sortable: false }
]

// v-data-table-server 的多选需要一个本地 ref 承接当前选中的行对象，
// 再转给 useRecordList 的 handleSelectionChange 去派生 selectedIds/multiple
const selectedRows = ref<any[]>([])
const onSelectionChange = (rows: any[]) => {
  selectedRows.value = rows
  handleSelectionChange(rows)
}

const onPageChange = (page: number) => {
  queryParams.pageNum = page
  getList()
}

const onSizeChange = (size: number) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
}

// 搜索输入防抖：输入停止 300ms 后自动触发搜索
const debouncedSearch = useDebounce(() => {
  handleQuery()
}, 300)

watch(
  () => [queryParams.copySrcPath, queryParams.copyDstPath, queryParams.copySrcFileName, queryParams.copyDstFileName, queryParams.copyStatus, dateRange.value],
  () => debouncedSearch()
)

getList()
</script>

<style scoped lang="scss">
.page-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* ============================================
   Search Card
   ============================================ */
.search-card {
  padding: 14px 16px;
}

.search-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 12px;

  > .v-text-field,
  > .v-select {
    width: 200px;
    flex: 0 0 auto;
  }

  .status-select {
    width: 140px;
  }

  .date-range-fields {
    display: flex;
    align-items: center;
    gap: 6px;
    flex: 0 0 auto;

    .date-field {
      width: 150px;
    }

    .date-range-sep {
      color: var(--osr-text-secondary);
    }
  }

  .search-actions {
    display: flex;
    gap: 8px;
    align-items: center;
    margin-top: 2px;
  }
}

/* ============================================
   Table Card
   ============================================ */
.table-card {
  padding: 16px;
  display: flex;
  flex-direction: column;
}

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;

  .action-left {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }
}

/* ============================================
   Copy Detail Column (Desktop Table)
   ============================================ */
.file-change-box {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 0;
}

.file-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.file-label {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 3px;
  line-height: 1.4;
}

.label-src {
  color: #409eff;
  background: rgba(64, 158, 255, 0.1);
}

.label-dst {
  color: #67c23a;
  background: rgba(103, 194, 58, 0.1);
}

.file-name {
  flex-shrink: 0;
  max-width: 200px;
  font-size: 13px;
  font-weight: 500;
  color: var(--osr-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-path {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--osr-text-placeholder);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ============================================
   Mobile Responsive
   ============================================ */
@media (max-width: 768px) {
  .page-container {
    gap: 10px;
  }

  .search-fields {
    > .v-text-field,
    > .v-select,
    .status-select {
      width: 100%;
    }

    .date-range-fields {
      width: 100%;

      .date-field {
        width: 100%;
      }
    }

    .search-actions {
      width: 100%;

      .v-btn {
        flex: 1;
      }
    }
  }

  .action-bar {
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 10px;

    .action-left {
      gap: 4px;
    }
  }

  .table-card {
    padding: 12px;
  }
}
</style>
