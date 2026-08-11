<template>
  <div class="mobile-page">
    <!-- 搜索 -->
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <v-text-field
        v-model="queryParams.copySrcPath"
        label="源目录"
        placeholder="请输入源目录"
        clearable
        density="compact"
        variant="outlined"
        @keyup.enter="handleQuery"
      />
      <v-text-field
        v-model="queryParams.copyDstPath"
        label="目标目录"
        placeholder="请输入目标目录"
        clearable
        density="compact"
        variant="outlined"
        @keyup.enter="handleQuery"
      />
      <v-text-field
        v-model="queryParams.copySrcFileName"
        label="源文件名"
        placeholder="请输入源文件名"
        clearable
        density="compact"
        variant="outlined"
        @keyup.enter="handleQuery"
      />
      <v-text-field
        v-model="queryParams.copyDstFileName"
        label="目标名"
        placeholder="请输入目标名"
        clearable
        density="compact"
        variant="outlined"
        @keyup.enter="handleQuery"
      />
      <v-select
        v-model="queryParams.copyStatus"
        label="状态"
        placeholder="全部状态"
        :items="[{ title: '处理中', value: '1' }, { title: '失败', value: '2' }, { title: '成功', value: '3' }, { title: '未知', value: '4' }]"
        clearable
        density="compact"
        variant="outlined"
      />
      <div class="date-range-fields">
        <v-text-field
          v-model="dateStart"
          label="开始日期"
          type="date"
          density="compact"
          variant="outlined"
          class="date-field"
        />
        <span class="date-range-sep">-</span>
        <v-text-field
          v-model="dateEnd"
          label="结束日期"
          type="date"
          density="compact"
          variant="outlined"
          class="date-field"
        />
      </div>
    </MobileSearchPanel>

    <!-- Batch Actions -->
    <div class="batch-bar" v-if="selectedIds.length > 0">
      <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
      <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-refresh" @click="handleBatchRetry">
        重试
      </v-btn>
      <v-btn variant="text" color="error" size="small" prepend-icon="mdi-download-off-outline" @click="handleBatchRemoveNetDisk">
        删网盘
      </v-btn>
      <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleBatchDelete">
        删记录
      </v-btn>
      <v-btn variant="text" size="small" class="batch-select-all-btn" @click="toggleSelectAllPage(!isAllPageSelected)">
        {{ isAllPageSelected ? '取消全选' : '全选' }}
      </v-btn>
      <v-btn variant="text" size="small" @click="clearSelection">
        取消
      </v-btn>
    </div>

    <!-- Record List -->
    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <v-card
        v-for="record in recordList"
        :key="record.copyId"
        class="task-card"
        :class="{ selected: selectedIds.includes(record.copyId) }"
        @click="handleCardClick($event, record.copyId)"
      >
        <div class="card-checkbox">
          <v-checkbox
            :model-value="selectedIds.includes(record.copyId)"
            density="compact"
            hide-details
            @click.stop="toggleSelect(record.copyId)"
          />
        </div>
        <div class="card-content">
          <div class="card-top">
            <div class="card-title-row">
              <v-icon class="card-title-icon" icon="mdi-file-multiple-outline" size="18" />
              <span class="card-title card-title--link" @click.stop="showFullText(record.copySrcFileName, '文件名')">{{ record.copySrcFileName }}</span>
            </div>
            <StatusChip :type="getCopyStatusType(record.copyStatus)" :text="getCopyStatusText(record.copyStatus)" />
          </div>
          <div class="card-path card-path--link" @click.stop="showFullText(record.copySrcPath, '源路径')">
            <v-icon class="card-path-icon" icon="mdi-map-marker-outline" size="14" />
            <span class="card-path-text">{{ record.copySrcPath }}</span>
          </div>
          <div class="card-path card-path--link card-path--success" @click.stop="showFullText(record.copyDstFileName, '目标文件名')">
            <v-icon class="card-path-icon" icon="mdi-file-outline" size="14" />
            <span class="card-path-text">{{ record.copyDstFileName }}</span>
          </div>
          <div class="card-path card-path--link card-path--success" @click.stop="showFullText(record.copyDstPath, '目标路径')">
            <v-icon class="card-path-icon" icon="mdi-map-marker-outline" size="14" />
            <span class="card-path-text">{{ record.copyDstPath }}</span>
          </div>
          <div class="card-time">
            <v-icon icon="mdi-clock-outline" size="12" />
            {{ record.createTime }}
          </div>
          <div class="card-actions" @click.stop>
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-refresh" @click="handleRetryOne(record)">
              重试
            </v-btn>
            <v-btn class="action-more" variant="text" color="default" size="small" icon="mdi-dots-horizontal" @click="openActionDrawer(record)" />
          </div>
        </div>
      </v-card>

      <v-empty-state v-if="!loading && recordList.length === 0" icon="mdi-inbox-outline" title="暂无同步记录" />
    </div>

    <!-- 操作抽屉 -->
    <v-bottom-sheet v-model="actionDrawerOpen">
      <v-card v-if="actionDrawerTarget" title="更多操作">
        <v-card-text>
          <div class="drawer-actions">
            <v-btn color="warning" block prepend-icon="mdi-download-off-outline" @click="handleRemoveNetDiskOne(actionDrawerTarget); actionDrawerOpen = false">删网盘</v-btn>
            <v-btn color="error" block prepend-icon="mdi-delete-outline" @click="handleDeleteOne(actionDrawerTarget); actionDrawerOpen = false">删记录</v-btn>
          </div>
        </v-card-text>
      </v-card>
    </v-bottom-sheet>

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

    <!-- 全文查看 -->
    <FullTextDialog ref="fullTextRef" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import FullTextDialog from '@/components/mobile/FullTextDialog.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useCopyRecord } from '@/composables/useCopyRecord'

const searchCollapsed = ref(true)

const {
  recordList, loading, total, queryParams, totalPages,
  getList, prevPage, nextPage, handleSizeChange,
  dateStart, dateEnd, handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  isAllPageSelected, toggleSelectAllPage,
  handleRetryOne, handleBatchRetry, handleDeleteOne, handleBatchDelete,
  handleRemoveNetDiskOne, handleBatchRemoveNetDisk,
  getCopyStatusText, getCopyStatusType
} = useCopyRecord()

const fullTextRef = ref<InstanceType<typeof FullTextDialog>>()
const showFullText = (content: string, title: string) => fullTextRef.value?.show(content, title)

/** 更多操作抽屉 */
const actionDrawerOpen = ref(false)
const actionDrawerTarget = ref<any>(null)
const openActionDrawer = (row: any) => {
  actionDrawerTarget.value = row
  actionDrawerOpen.value = true
}

getList()
</script>

<style scoped lang="scss">
/* ============================================
   Date Range Fields
   ============================================ */
</style>
