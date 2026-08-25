<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && taskList.length === 0"
    empty-icon="inbox"
    empty-title="暂无黑名单规则"
  >
    <template #head>
      <!-- 搜索 -->
      <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
        <v-form ref="queryRef">
          <v-select
            v-model="queryParams.type"
            label="类型"
            :items="[{ title: '种子(GUID)', value: 'GUID' }, { title: '发布组', value: 'RELEASE_GROUP' }]"
            placeholder="全部类型"
            clearable
            density="compact"
            variant="outlined"
            hide-details
          />
          <v-text-field
            v-model="queryParams.displayValue"
            label="展示内容"
            placeholder="标题或发布组名"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
        </v-form>
      </MobileSearchPanel>

      <!-- 新增 FAB -->
      <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="plus" @click="handleAdd('新增发布组黑名单')">
        新增
      </v-btn>

      <!-- 列表 -->
    </template>

    <v-card v-for="item in taskList" :key="item.id" class="task-card">
      <div class="card-content">
        <div class="card-top">
          <span class="card-title" :title="item.displayValue">{{ item.displayValue || '(无展示内容)' }}</span>
          <StatusChip :type="item.type === 'GUID' ? 'error' : 'warning'" :text="item.type === 'GUID' ? '种子' : '发布组'" />
        </div>
        <div class="card-detail">
          <div class="detail-row">
            <span class="label">匹配键</span>
            <span class="value">{{ item.type === 'GUID' ? shortHash(item.value) : item.value }}</span>
          </div>
          <div class="detail-row">
            <span class="label">原因</span>
            <span class="value">{{ item.reason || '-' }}</span>
          </div>
          <div class="detail-row">
            <span class="label">创建时间</span>
            <span class="value">{{ item.createTime || '-' }}</span>
          </div>
        </div>
        <div class="card-actions">
          <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(item)">
            删除
          </v-btn>
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

      <!-- 新增弹窗（两端共用） -->
      <PtTorrentBlacklistFormDialog />
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import StatusChip from '@/components/StatusChip.vue'
import { usePtTorrentBlacklist } from '@/composables/usePtTorrentBlacklist'
import { usePageStateProvider } from '@/composables/pageStateContext'
import PtTorrentBlacklistFormDialog from '@/components/dialogs/PtTorrentBlacklistFormDialog.vue'

// 表单弹窗与 PC 端共用一份（components/dialogs/），它靠 usePageStateProvider 取同一份状态
const {
  taskList, loading, total, queryParams, queryRef,
  handleQuery, resetQuery,
  handleAdd, handleDelete,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed
} = usePageStateProvider(usePtTorrentBlacklist())

const shortHash = (value: string) => {
  if (!value) return '-'
  return value.length > 12 ? `${value.slice(0, 6)}...${value.slice(-4)}` : value
}
</script>
