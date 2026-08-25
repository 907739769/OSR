<template>
  <div class="page-container">
    <PageHeader
      icon="server"
      title="媒体服务器"
      desc="配置 Emby / Jellyfin，用于入库对账"
    />

    <!-- Search Panel -->
    <SearchPanel :visible="showSearch" @search="handleQuery" @reset="resetQuery">
      <v-text-field
        v-model="queryParams.name"
        label="名称"
        placeholder="请输入名称"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        @keyup.enter="handleQuery"
      />
      <v-select
        v-model="queryParams.enabled"
        :items="[{ title: '启用', value: '1' }, { title: '停用', value: '0' }]"
        label="状态"
        placeholder="状态"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="field-sm"
      />
    </SearchPanel>

    <!-- Table Card -->
    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="primary" prepend-icon="plus" @click="handleAdd('新增媒体服务器')">
            新增
          </v-btn>
          <v-btn color="success" prepend-icon="square-pen" :disabled="notOneSelected" @click="handleUpdate(undefined, '修改媒体服务器')">
            修改
          </v-btn>
          <v-btn color="error" prepend-icon="trash-2" :disabled="noneSelected" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的媒体服务器？`)">
            批量删除
          </v-btn>
          <v-btn variant="text" class="batch-select-all-btn" @click="toggleSelectAllPage(!isAllPageSelected)">
            {{ isAllPageSelected ? '取消全选' : '全选' }}
          </v-btn>
        </div>
        <v-btn variant="text" prepend-icon="funnel" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <div class="card-grid" ref="gridRef">
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <div v-for="item in taskList" :key="item.id" class="item-card">
          <div class="card-header">
            <div class="card-checkbox">
              <v-checkbox
                :model-value="selectedIds.includes(item.id)"
                density="compact"
                hide-details
                @update:model-value="toggleSelect(item.id)"
              />
            </div>
            <span class="card-title" :title="item.name">{{ item.name }}</span>
            <StatusChip :value="item.enabled" />
          </div>
          <div class="card-body">
            <div class="card-row">
              <span class="label">类型</span>
              <span class="value">{{ item.type === 'JELLYFIN' ? 'Jellyfin' : 'Emby' }}</span>
            </div>
            <div class="card-row">
              <span class="label">服务器地址</span>
              <span class="value" :title="item.url">{{ item.url }}</span>
            </div>
            <div class="card-row">
              <span class="label">创建时间</span>
              <span class="value">{{ item.createTime }}</span>
            </div>
          </div>
          <div class="card-footer">
            <v-btn variant="text" color="primary" size="small" prepend-icon="square-pen" @click="handleUpdate(item, '修改媒体服务器')">
              修改
            </v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(item)">
              删除
            </v-btn>
          </div>
        </div>
        <v-empty-state v-if="!loading && taskList.length === 0" icon="inbox" title="暂无媒体服务器" />
      </div>

      <div class="pagination-wrapper">
        <span class="total-text">共 {{ total }} 条</span>
        <v-select
          :model-value="queryParams.pageSize"
          :items="pageSizeOptions"
          density="compact"
          variant="outlined"
          hide-details
          class="page-size-select"
          @update:model-value="setPageSize"
        />
        <v-pagination
          v-model="queryParams.pageNum"
          :length="Math.ceil(total / queryParams.pageSize) || 1"
          density="comfortable"
          @update:model-value="getList"
        />
      </div>
    </v-card>

    <PtMediaServerFormDialog />
  </div>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import PageHeader from '@/components/PageHeader.vue'
import { usePtMediaServer } from '@/composables/usePtMediaServer'
import { usePageStateProvider } from '@/composables/pageStateContext'
import { useGridPageSize } from '@/composables/useGridPageSize'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'
import PtMediaServerFormDialog from '@/components/dialogs/PtMediaServerFormDialog.vue'

const { showSearch } = useSearchPanel()

// 表单弹窗与移动端共用一份（components/dialogs/），它靠 usePageStateProvider 取同一份状态
const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery,
  selectedIds, notOneSelected, noneSelected, toggleSelect,
  isAllPageSelected, toggleSelectAllPage,
  handleAdd, handleUpdate, handleDelete
} = usePageStateProvider(usePtMediaServer({ autoLoad: false }))

// 每页条数按网格实际列数取整到整行，窗口宽度变了跟着重算
const { gridRef, pageSizeOptions, setPageSize } = useGridPageSize((size) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
})
</script>
