<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && recordList.length === 0"
    empty-icon="inbox"
    empty-title="暂无STRM记录"
  >
    <template #head>
      <!-- 搜索 -->
      <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
        <v-form ref="queryRef">
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
            placeholder="全部状态"
            :items="[{ title: '成功', value: '1' }, { title: '失败', value: '0' }]"
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
        </v-form>
      </MobileSearchPanel>

      <!-- Batch Actions -->
      <MobileBatchBar
        :visible="selectedIds.length > 0"
        :count="selectedIds.length"
        :all-selected="isAllPageSelected"
        @toggle-all="toggleSelectAllPage"
        @cancel="clearSelection"
      >
        <v-btn variant="text" color="primary" size="small" @click="handleBatchRetry">
          <v-icon icon="refresh-cw" start />重试
        </v-btn>
        <v-btn variant="text" color="error" size="small" @click="handleBatchRemoveNetDisk">
          <v-icon icon="download" start />删网盘
        </v-btn>
        <v-btn variant="text" color="error" size="small" @click="handleBatchDelete">
          <v-icon icon="trash-2" start />删记录
        </v-btn>
      </MobileBatchBar>

      <!-- Record List -->
    </template>

    <v-card
      v-for="record in recordList"
      :key="record.strmId"
      class="task-card"
      :class="{ selected: selectedIds.includes(record.strmId) }"
      @click="handleCardClick($event, record.strmId)"
    >
      <div class="card-checkbox">
        <v-checkbox-btn
          :model-value="selectedIds.includes(record.strmId)"
          density="compact"
          @click.stop="toggleSelect(record.strmId)"
        />
      </div>
      <div class="card-content">
        <div class="card-top">
          <div class="card-title-row">
            <v-icon class="card-title-icon" icon="file-video-camera" size="18" />
            <span class="card-title card-title--link" @click.stop="showFullText(record.strmFileName, '文件名')">{{ record.strmFileName }}</span>
          </div>
          <StatusChip :value="record.strmStatus" enabled-value="1" on-text="成功" off-text="失败" />
        </div>
        <div class="card-path card-path--link" @click.stop="showFullText(record.strmPath, '路径')">
          <v-icon class="card-path-icon" icon="map-pin" size="14" />
          <span class="card-path-text">{{ record.strmPath }}</span>
        </div>
        <div class="card-time">
          <v-icon icon="clock" size="14" />
          {{ record.createTime }}
        </div>
        <div class="card-actions" @click.stop>
          <v-btn variant="text" color="primary" size="small" prepend-icon="refresh-cw" @click="handleRetryOne(record)">
            重试
          </v-btn>
          <v-btn class="action-more" variant="text" color="default" size="small" icon="ellipsis" @click="openSheet(record)" />
        </div>
      </div>
    </v-card>

    <template #foot>
      <!-- 操作抽屉 -->
      <MobileActionSheet v-model="sheetOpen" :target="sheetTarget">
        <v-btn color="warning" block prepend-icon="download" @click="run(() => handleRemoveNetDiskOne(sheetTarget))">删网盘</v-btn>
        <v-btn color="error" block prepend-icon="trash-2" @click="run(() => handleDeleteOne(sheetTarget))">删记录</v-btn>
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
import { useStrmRecord } from '@/composables/useStrmRecord'
import { useActionSheet } from '@/composables/useActionSheet'

const searchCollapsed = ref(true)

const {
  recordList, loading, total, queryParams, totalPages,
  getList, prevPage, nextPage, handleSizeChange,
  queryRef, dateStart, dateEnd, handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  isAllPageSelected, toggleSelectAllPage,
  handleRetryOne, handleBatchRetry, handleDeleteOne, handleBatchDelete,
  handleRemoveNetDiskOne, handleBatchRemoveNetDisk
} = useStrmRecord()

const fullTextRef = ref<InstanceType<typeof FullTextDialog>>()
const showFullText = (content: string, title: string) => fullTextRef.value?.show(content, title)

/** 更多操作抽屉 */
/** 卡片「更多」动作面板：开关状态与「执行完自动关闭」都在 useActionSheet 里 */
const { sheetOpen, sheetTarget, openSheet, run } = useActionSheet()

getList()
</script>

<style scoped lang="scss">
</style>
