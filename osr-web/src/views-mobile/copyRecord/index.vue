<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && recordList.length === 0"
    empty-icon="mdi-inbox-outline"
    empty-title="暂无同步记录"
  >
    <template #head>
      <!-- 搜索 -->
      <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
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
          placeholder="全部状态"
          :items="[{ title: '处理中', value: '1' }, { title: '失败', value: '2' }, { title: '成功', value: '3' }, { title: '未知', value: '4' }]"
          clearable
          density="compact"
          variant="outlined"
          hide-details
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
      </MobileSearchPanel>

      <!-- Batch Actions -->
      <MobileBatchBar
        :visible="selectedIds.length > 0"
        :count="selectedIds.length"
        :all-selected="isAllPageSelected"
        @toggle-all="toggleSelectAllPage"
        @cancel="clearSelection"
      >
        <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-refresh" @click="handleBatchRetry">
          重试
        </v-btn>
        <v-btn variant="text" color="error" size="small" prepend-icon="mdi-download-off-outline" @click="handleBatchRemoveNetDisk">
          删网盘
        </v-btn>
        <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleBatchDelete">
          删记录
        </v-btn>
      </MobileBatchBar>

      <!-- Record List -->
    </template>

    <v-card
      v-for="record in recordList"
      :key="record.copyId"
      class="task-card"
      :class="{ selected: selectedIds.includes(record.copyId) }"
      @click="handleCardClick($event, record.copyId)"
    >
      <div class="card-checkbox">
        <v-checkbox-btn
          :model-value="selectedIds.includes(record.copyId)"
          density="compact"
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
          <v-btn class="action-more" variant="text" color="default" size="small" icon="mdi-dots-horizontal" @click="openSheet(record)" />
        </div>
      </div>
    </v-card>

    <template #foot>
      <!-- 操作抽屉 -->
      <MobileActionSheet v-model="sheetOpen" :target="sheetTarget">
        <v-btn color="warning" block prepend-icon="mdi-download-off-outline" @click="run(() => handleRemoveNetDiskOne(sheetTarget))">删网盘</v-btn>
        <v-btn color="error" block prepend-icon="mdi-delete-outline" @click="run(() => handleDeleteOne(sheetTarget))">删记录</v-btn>
      </MobileActionSheet>

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
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileActionSheet from '@/components/mobile/MobileActionSheet.vue'
import MobileBatchBar from '@/components/mobile/MobileBatchBar.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import FullTextDialog from '@/components/mobile/FullTextDialog.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useCopyRecord } from '@/composables/useCopyRecord'
import { useActionSheet } from '@/composables/useActionSheet'

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
/** 卡片「更多」动作面板：开关状态与「执行完自动关闭」都在 useActionSheet 里 */
const { sheetOpen, sheetTarget, openSheet, run } = useActionSheet()

getList()
</script>

<style scoped lang="scss">
/* ============================================
   Date Range Fields
   ============================================ */
</style>
