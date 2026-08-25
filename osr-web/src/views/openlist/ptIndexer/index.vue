<template>
  <div class="page-container">
    <PageHeader
      icon="scan-search"
      title="PT 索引器"
      desc="配置 Torznab 接口，用于 RSS 轮询与搜索补集"
    />

    <!-- Search Panel -->
    <SearchPanel ref="queryRef" :visible="showSearch" @search="handleQuery" @reset="resetQuery">
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
        label="状态"
        :items="[{ title: '启用', value: '1' }, { title: '停用', value: '0' }]"
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
          <v-btn color="primary" prepend-icon="plus" @click="handleAdd('新增索引器')">
            新增
          </v-btn>
          <v-btn color="success" prepend-icon="square-pen" :disabled="notOneSelected" @click="handleUpdate(undefined, '修改索引器')">
            修改
          </v-btn>
          <v-btn color="error" prepend-icon="trash-2" :disabled="noneSelected" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的索引器？`)">
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
              <span class="label">接口地址</span>
              <span class="value" :title="item.url">{{ item.url }}</span>
            </div>
            <div class="card-row">
              <span class="label">分类</span>
              <span class="value">{{ item.categories || '不限' }}</span>
            </div>
            <div class="card-row">
              <span class="label">轮询周期</span>
              <span class="value">{{ item.pollInterval }} 秒</span>
            </div>
            <div class="card-row" v-if="item.hrEnabled === '1'">
              <span class="label">H&amp;R</span>
              <span class="value">
                <StatusChip type="warning" :text="hrLabel(item)" />
              </span>
            </div>
            <div class="card-row">
              <span class="label">上次轮询</span>
              <span class="value">{{ item.lastPollTime || '-' }}</span>
            </div>
            <div class="card-row">
              <span class="label">上次结果</span>
              <span class="value">
                <span v-if="!item.lastStatus">-</span>
                <StatusChip v-else-if="item.lastStatus === 'OK'" type="success" text="正常" />
                <StatusChip v-else type="error" :text="item.lastStatus" />
              </span>
            </div>
            <div class="card-row" v-if="item.failCount > 0">
              <span class="label">连续失败</span>
              <span class="value">
                <v-chip :color="item.failCount >= 10 ? 'error' : 'warning'" size="small" variant="tonal">
                  {{ item.failCount }} 次
                </v-chip>
              </span>
            </div>
          </div>
          <div class="card-footer">
            <v-btn variant="text" color="primary" size="small" prepend-icon="square-pen" @click="handleUpdate(item, '修改索引器')">
              修改
            </v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(item)">
              删除
            </v-btn>
          </div>
        </div>
        <v-empty-state v-if="!loading && taskList.length === 0" icon="inbox" title="暂无索引器" />
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

    <PtIndexerFormDialog />
  </div>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import PageHeader from '@/components/PageHeader.vue'
import { usePtIndexer } from '@/composables/usePtIndexer'
import { usePageStateProvider } from '@/composables/pageStateContext'
import { useGridPageSize } from '@/composables/useGridPageSize'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'
import PtIndexerFormDialog from '@/components/dialogs/PtIndexerFormDialog.vue'

const { showSearch } = useSearchPanel()

/** 列表卡片上的 H&R 要求摘要。两项是「或」的关系，只填了一项就只显示那一项 */
const hrLabel = (item: any) => {
  const parts: string[] = []
  if (item.hrSeedHours > 0) parts.push(`做满 ${item.hrSeedHours}h`)
  if (item.hrRatio > 0) parts.push(`分享率 ${item.hrRatio}`)
  return parts.length ? parts.join(' 或 ') : '未配置阈值'
}

// 表单弹窗与移动端共用一份（components/dialogs/），它靠 usePageStateProvider 取同一份状态
const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  selectedIds, notOneSelected, noneSelected, toggleSelect,
  isAllPageSelected, toggleSelectAllPage,
  handleAdd, handleUpdate, handleDelete
} = usePageStateProvider(usePtIndexer({ autoLoad: false }))

// 每页条数按网格实际列数取整到整行，窗口宽度变了跟着重算
const { gridRef, pageSizeOptions, setPageSize } = useGridPageSize((size) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
})
</script>

<style scoped lang="scss">
/* .card-grid / .item-card / .card-header / .card-body / .card-row / .card-footer
   已统一由 styles/list.scss 提供，本页不再重复定义 */
</style>
