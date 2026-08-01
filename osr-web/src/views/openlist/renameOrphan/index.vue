<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-file-remove-outline"
      title="重命名一致性检查"
      desc="扫描重命名后已失效的记录（本地文件丢失或网盘源丢失），可清理或忽略"
    />

    <!-- Search Panel -->
    <v-card v-if="showSearch" class="search-card">
      <v-form ref="queryRef" @submit.prevent="handleQuery">
        <div class="search-fields">
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
            :items="[{ title: '本地文件丢失', value: 'local_missing' }, { title: '网盘源丢失', value: 'source_missing' }]"
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
          <div class="search-actions">
            <v-btn color="primary" prepend-icon="mdi-magnify" @click="handleQuery">搜索</v-btn>
            <v-btn variant="outlined" prepend-icon="mdi-refresh" @click="resetQuery">重置</v-btn>
          </div>
        </div>
      </v-form>
    </v-card>

    <!-- Table Card -->
    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="primary" prepend-icon="mdi-refresh" :loading="scanning" @click="handleScanNow">
            立即扫描
          </v-btn>
          <v-btn color="error" prepend-icon="mdi-delete-outline" :disabled="multiple" @click="handleBatchClean()">
            批量清理
          </v-btn>
          <v-btn color="warning" prepend-icon="mdi-alert-outline" :disabled="multiple" @click="handleBatchIgnore()">
            批量忽略
          </v-btn>
        </div>
        <v-btn variant="text" prepend-icon="mdi-filter-outline" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
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
          <StatusChip v-if="item.reason === 'local_missing'" type="warning" text="本地文件丢失" />
          <StatusChip v-else-if="item.reason === 'source_missing'" type="error" text="网盘源丢失" />
        </template>
        <template #item.path="{ item }">
          <span class="orphan-path" :title="`${item.newPath}/${item.newName}`">{{ item.newPath }}/{{ item.newName }}</span>
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
import { ref } from 'vue'
import { useRenameOrphanList } from '@/composables/useRenameOrphanList'

const showSearch = ref(window.innerWidth >= 768)

const {
  recordList, loading, total, queryParams,
  getList, queryRef, handleQuery, resetQuery,
  multiple, handleSelectionChange,
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

// v-data-table-server 的多选需要一个本地 ref 承接当前选中的行对象，
// 再转给 useRenameOrphanList 的 handleSelectionChange 去派生 selectedIds/multiple
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
