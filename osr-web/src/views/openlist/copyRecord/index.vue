<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-file-multiple-outline"
      title="文件同步记录"
      desc="逐文件的同步结果，失败项可重试或清理网盘文件"
    />

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
import PageHeader from '@/components/PageHeader.vue'
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

// dateRange 是 [start, end] 数组结构（从 el-date-picker daterange 迁移而来），
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
