<template>
  <div class="page-container">
    <!-- Search Panel -->
    <v-card v-if="showSearch" class="search-card">
      <v-form ref="queryRef" @submit.prevent="handleQuery">
        <div class="search-fields">
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
            v-model="rangeStart"
            label="开始时间"
            type="date"
            density="compact"
            variant="outlined"
            hide-details
            class="date-field"
          />
          <v-text-field
            v-model="rangeEnd"
            label="结束时间"
            type="date"
            density="compact"
            variant="outlined"
            hide-details
            class="date-field"
          />
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
          <v-btn color="error" prepend-icon="mdi-download-outline" :disabled="multiple" @click="handleBatchRemoveNetDisk()">
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
        item-value="strmId"
        return-object
        :model-value="selectedRows"
        class="modern-table"
        @update:model-value="onSelectionChange"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
      >
        <template #item.fileInfo="{ item }">
          <div class="file-info-box">
            <div class="file-name" :title="item.strmFileName">
              <v-icon icon="mdi-file-video-outline" size="14" />
              {{ item.strmFileName }}
            </div>
            <div class="file-path" :title="item.strmPath">{{ item.strmPath }}</div>
          </div>
        </template>
        <template #item.strmStatus="{ item }">
          <v-chip size="small" :color="item.strmStatus === '1' ? 'success' : 'error'" variant="tonal">
            {{ item.strmStatus === '1' ? '成功' : '失败' }}
          </v-chip>
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-refresh" @click="handleRetryOne(item)">
            重试
          </v-btn>
          <v-btn variant="text" color="warning" size="small" prepend-icon="mdi-download-outline" @click="handleRemoveNetDiskOne(item)">
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
import { ref, computed } from 'vue'
import { useStrmRecord } from '@/composables/useStrmRecord'

const showSearch = ref(window.innerWidth >= 768)

const {
  recordList, loading, total, queryParams,
  getList, queryRef, dateRange, handleQuery, resetQuery,
  multiple, handleSelectionChange,
  handleRetryOne, handleBatchRetry, handleDeleteOne, handleBatchDelete,
  handleRemoveNetDiskOne, handleBatchRemoveNetDisk
} = useStrmRecord()

// dateRange 是 [开始, 结束] 的字符串数组，这里拆成两个日期输入框分别读写
const rangeStart = computed({
  get: () => dateRange.value?.[0] ?? '',
  set: (val: string) => {
    const end = dateRange.value?.[1] ?? ''
    dateRange.value = (val || end) ? [val, end] : null
  }
})
const rangeEnd = computed({
  get: () => dateRange.value?.[1] ?? '',
  set: (val: string) => {
    const start = dateRange.value?.[0] ?? ''
    dateRange.value = (start || val) ? [start, val] : null
  }
})

const headers = [
  { title: '文件信息', key: 'fileInfo', minWidth: '300' },
  { title: '状态', key: 'strmStatus', align: 'center' as const, width: '80' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '260', sortable: false }
]

// v-data-table-server 的多选需要一个本地 ref 承接当前选中的行对象
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

getList()
</script>

<style scoped lang="scss">



.search-fields {

  .date-field {
    width: 170px;
  }
}



/* ============================================
    Desktop Table Text Overflow
    ============================================ */
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

/* ============================================
    Mobile Responsive
    ============================================ */
@media (max-width: 768px) {

  .search-fields {
    > .v-text-field,
    > .v-select,
    .status-select,
    .date-field {
      width: 100%;
    }

    .search-actions {
      width: 100%;

      .v-btn {
        flex: 1;
      }
    }
  }
}
</style>
