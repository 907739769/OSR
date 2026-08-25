<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && taskList.length === 0"
    empty-icon="inbox"
    empty-title="暂无索引器"
  >
    <template #head>
      <!-- 搜索 -->
      <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
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
          placeholder="全部状态"
          clearable
          density="compact"
          variant="outlined"
          hide-details
        />
      </MobileSearchPanel>

      <!-- 批量操作 -->
      <MobileBatchBar
        :visible="selectedIds.length > 0"
        :count="selectedIds.length"
        :all-selected="isAllPageSelected"
        @toggle-all="toggleSelectAllPage"
        @cancel="clearSelection"
      >
        <v-btn variant="text" color="error" size="small" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的索引器？`)">
          批量删除
        </v-btn>
      </MobileBatchBar>

      <!-- 新增 FAB -->
      <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="plus" @click="handleAdd('新增索引器')">
        新增
      </v-btn>

      <!-- 列表 -->
    </template>

    <v-card
      v-for="item in taskList"
      :key="item.id"
      class="task-card"
      :class="{ selected: selectedIds.includes(item.id) }"
      @click="handleCardClick($event, item.id)"
    >
      <div class="card-checkbox">
        <v-checkbox-btn
          :model-value="selectedIds.includes(item.id)"
          density="compact"
          @click.stop="toggleSelect(item.id)"
        />
      </div>
      <div class="card-content">
        <div class="card-top">
          <span class="card-title">{{ item.name }}</span>
          <StatusChip :value="item.enabled" />
        </div>
        <div class="card-detail">
          <div class="detail-row">
            <span class="label">接口地址</span>
            <span class="value">{{ item.url }}</span>
          </div>
          <div class="detail-row">
            <span class="label">分类</span>
            <span class="value">{{ item.categories || '不限' }}</span>
          </div>
          <div class="detail-row">
            <span class="label">轮询周期</span>
            <span class="value">{{ item.pollInterval }} 秒</span>
          </div>
          <div class="detail-row">
            <span class="label">上次轮询</span>
            <span class="value">{{ item.lastPollTime || '-' }}</span>
          </div>
          <div class="detail-row">
            <span class="label">上次结果</span>
            <span class="value">
              <span v-if="!item.lastStatus">-</span>
              <StatusChip v-else-if="item.lastStatus === 'OK'" type="success" text="正常" />
              <StatusChip v-else type="error" :text="item.lastStatus" />
            </span>
          </div>
          <div class="detail-row" v-if="item.failCount > 0">
            <span class="label">连续失败</span>
            <span class="value">
              <v-chip :color="item.failCount >= 10 ? 'error' : 'warning'" size="small" variant="tonal">
                {{ item.failCount }} 次
              </v-chip>
            </span>
          </div>
        </div>
        <div class="card-actions" @click.stop>
          <v-btn variant="text" color="primary" size="small" prepend-icon="square-pen" @click="handleUpdate(item, '修改索引器')">修改</v-btn>
          <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(item)">删除</v-btn>
        </div>
      </div>
    </v-card>

    <template #foot>
      <!-- 分页 -->
      <MobilePager
        v-model:page-size="queryParams.pageSize"
        :page-num="queryParams.pageNum"
        :total="total"
        :total-pages="totalPages"
        @prev="prevPage"
        @next="nextPage"
        @size-change="handleSizeChange"
      />

      <!-- 新增/编辑弹窗（两端共用） -->
      <PtIndexerFormDialog />
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileBatchBar from '@/components/mobile/MobileBatchBar.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtIndexer } from '@/composables/usePtIndexer'
import { usePageStateProvider } from '@/composables/pageStateContext'
import PtIndexerFormDialog from '@/components/dialogs/PtIndexerFormDialog.vue'

// 表单弹窗与 PC 端共用一份（components/dialogs/），它靠 usePageStateProvider 取同一份状态
const {
  taskList, loading, total, queryParams,
  handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  isAllPageSelected, toggleSelectAllPage,
  handleAdd, handleUpdate, handleDelete,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed
} = usePageStateProvider(usePtIndexer())
</script>
