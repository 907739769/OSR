<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && taskList.length === 0"
    empty-icon="inbox"
    empty-title="暂无下载器"
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
        <v-btn variant="text" color="error" size="small" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的下载器？`)">
          批量删除
        </v-btn>
      </MobileBatchBar>

      <!-- 新增 FAB -->
      <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="plus" @click="handleAdd('新增下载器')">
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
            <span class="label">类型</span>
            <span class="value">{{ downloaderTypeLabel(item.type) }}</span>
          </div>
          <div class="detail-row">
            <span class="label">地址</span>
            <span class="value">{{ (item.useHttps === '1' ? 'https://' : 'http://') + item.host + ':' + item.port }}</span>
          </div>
          <div class="detail-row">
            <span class="label">保存路径</span>
            <span class="value">{{ item.savePath }}</span>
          </div>
          <div class="detail-row">
            <span class="label">标签</span>
            <span class="value">{{ item.tag }}</span>
          </div>
          <div class="detail-row">
            <span class="label">最大并发</span>
            <span class="value">{{ item.maxConcurrent ? item.maxConcurrent : '不限' }}</span>
          </div>
          <div class="detail-row">
            <span class="label">智能分类</span>
            <span class="value">{{ smartClassifyLabel(item.smartClassifyLevel) }}</span>
          </div>
          <div class="detail-row">
            <span class="label">分工</span>
            <span class="value">{{ roleLabel(item.role) }}</span>
          </div>
          <div class="detail-row">
            <span class="label">自动删种</span>
            <span class="value">{{ item.autoDeleteEnabled === '1' ? '已开启' : '未开启' }}</span>
          </div>
        </div>
        <div class="card-actions" @click.stop>
          <v-btn variant="text" color="primary" size="small" prepend-icon="square-pen" @click="handleUpdate(item, '修改下载器')">修改</v-btn>
          <v-btn variant="text" size="small" prepend-icon="brush-cleaning" @click="openCleanRules(item)">删种规则</v-btn>
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

      <!-- 新增/编辑弹窗 -->
      <!-- 新增/编辑弹窗（两端共用） -->
      <PtDownloaderFormDialog />

      <!-- 自动删种规则 -->
      <PtCleanRuleDialog v-model="cleanRuleOpen" :downloader="cleanRuleTarget" mobile />
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileBatchBar from '@/components/mobile/MobileBatchBar.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import PtCleanRuleDialog from '@/components/dialogs/PtCleanRuleDialog.vue'
import PtDownloaderFormDialog from '@/components/dialogs/PtDownloaderFormDialog.vue'
import {
  usePtDownloader,
  downloaderTypeLabel,
  smartClassifyLabel,
  roleLabel
} from '@/composables/usePtDownloader'
import { usePageStateProvider } from '@/composables/pageStateContext'

// 表单弹窗与 PC 端共用一份（components/dialogs/），它靠 usePageStateProvider 取同一份状态
const {
  taskList, loading, total, queryParams,
  handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  isAllPageSelected, toggleSelectAllPage,
  handleAdd, handleUpdate, handleDelete,
  cleanRuleOpen, cleanRuleTarget, openCleanRules,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed
} = usePageStateProvider(usePtDownloader())
</script>
