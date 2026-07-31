<template>
  <div class="mobile-page">
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
          class="mb-3"
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
          class="mb-3"
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
          class="mb-3"
        />
        <div class="date-row">
          <v-text-field
            v-model="rangeStart"
            label="开始日期"
            type="date"
            density="compact"
            variant="outlined"
            hide-details
          />
          <v-text-field
            v-model="rangeEnd"
            label="结束日期"
            type="date"
            density="compact"
            variant="outlined"
            hide-details
          />
        </div>
      </v-form>
    </MobileSearchPanel>

    <!-- Batch Actions -->
    <div class="batch-bar" v-if="selectedIds.length > 0">
      <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
      <v-btn variant="text" color="primary" size="small" @click="handleBatchRetry">
        <v-icon icon="mdi-refresh" start />重试
      </v-btn>
      <v-btn variant="text" color="error" size="small" @click="handleBatchRemoveNetDisk">
        <v-icon icon="mdi-download-outline" start />删网盘
      </v-btn>
      <v-btn variant="text" color="error" size="small" @click="handleBatchDelete">
        <v-icon icon="mdi-delete-outline" start />删记录
      </v-btn>
      <v-btn variant="text" size="small" @click="clearSelection">
        取消
      </v-btn>
    </div>

    <!-- Record List -->
    <div class="record-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <div
        v-for="record in recordList"
        :key="record.strmId"
        class="record-card"
        :class="{ selected: selectedIds.includes(record.strmId) }"
        @click="handleCardClick($event, record.strmId)"
      >
        <div class="card-checkbox">
          <v-checkbox
            :model-value="selectedIds.includes(record.strmId)"
            density="compact"
            hide-details
            @click.stop="toggleSelect(record.strmId)"
          />
        </div>
        <div class="card-content">
          <div class="card-top">
            <div class="file-name-row">
              <v-icon class="file-icon" icon="mdi-file-video-outline" size="18" />
              <span class="file-name" @click.stop="showFullText(record.strmFileName, '文件名')">{{ record.strmFileName }}</span>
            </div>
            <v-chip :color="record.strmStatus === '1' ? 'success' : 'error'" size="small" variant="tonal">
              {{ record.strmStatus === '1' ? '成功' : '失败' }}
            </v-chip>
          </div>
          <div class="file-path" @click.stop="showFullText(record.strmPath, '路径')">
            <v-icon class="path-icon" icon="mdi-map-marker-outline" size="14" />
            <span class="path-text">{{ record.strmPath }}</span>
          </div>
          <div class="card-time">
            <v-icon icon="mdi-clock-outline" size="14" />
            {{ record.createTime }}
          </div>
        </div>
        <div class="card-actions" @click.stop>
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-refresh" @click="handleRetryOne(record)">
            重试
          </v-btn>
          <v-btn variant="text" color="warning" size="small" prepend-icon="mdi-download-outline" @click="handleRemoveNetDiskOne(record)">
            删网盘
          </v-btn>
          <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDeleteOne(record)">
            删记录
          </v-btn>
        </div>
      </div>

      <v-empty-state v-if="!loading && recordList.length === 0" icon="mdi-inbox-outline" title="暂无STRM记录" />
    </div>

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
import { ref, computed } from 'vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import FullTextDialog from '@/components/mobile/FullTextDialog.vue'
import { useStrmRecord } from '@/composables/useStrmRecord'

const searchCollapsed = ref(true)

const {
  recordList, loading, total, queryParams, totalPages,
  getList, prevPage, nextPage, handleSizeChange,
  queryRef, dateRange, handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  handleRetryOne, handleBatchRetry, handleDeleteOne, handleBatchDelete,
  handleRemoveNetDiskOne, handleBatchRemoveNetDisk
} = useStrmRecord()

// dateRange 是 [开始, 结束] 的字符串数组，这里拆成两个日期输入框分别读写
const rangeStart = computed({
  get: () => dateRange.value?.[0] ?? '',
  set: (val: string) => {
    const end = dateRange.value?.[1] ?? ''
    dateRange.value = (val || end) ? [val, end] : null
  }
})
const rangeEnd = computed({
  get: () => dateRange.value?.[1] ?? '',
  set: (val: string) => {
    const start = dateRange.value?.[0] ?? ''
    dateRange.value = (start || val) ? [start, val] : null
  }
})

const fullTextRef = ref<InstanceType<typeof FullTextDialog>>()
const showFullText = (content: string, title: string) => fullTextRef.value?.show(content, title)

getList()
</script>

<style scoped lang="scss">
.mobile-page {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: calc(100vh - 120px);
  padding-bottom: 8px;

  .record-list {
    flex: 1;
  }
}

.date-row {
  display: flex;
  gap: 8px;

  .v-text-field {
    flex: 1;
    min-width: 0;
  }
}

/* ============================================
   Batch Action Bar
   ============================================ */


/* ============================================
   Record List
   ============================================ */
.record-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 200px;
}

.record-card {
  display: flex;
  gap: 10px;
  background: var(--osr-surface);
  border-radius: var(--osr-radius-lg);
  padding: 12px;
  box-shadow: var(--osr-shadow-base);
  border: 2px solid transparent;
  transition: all var(--osr-transition-fast);

  &.selected {
    border-color: var(--osr-primary-light-5);
    background: var(--osr-primary-light-9);
  }

  &:active {
    transform: scale(0.99);
  }

  .card-checkbox {
    flex-shrink: 0;
    display: flex;
    align-items: flex-start;
    padding-top: 2px;
    padding-left: 2px;
  }

  .card-content {
    flex: 1;
    min-width: 0;
  }

  .card-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;
    gap: 8px;
  }

  .file-name-row {
    display: flex;
    align-items: center;
    gap: 5px;
    min-width: 0;
    flex: 1;

    .file-icon {
      color: var(--osr-primary);
      flex-shrink: 0;
    }

    .file-name {
      font-size: 14px;
      font-weight: 500;
      color: var(--osr-text-primary);
      overflow: hidden;
      text-overflow: ellipsis;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      line-height: 1.4;
      cursor: pointer;
      word-break: break-all;

      &:hover {
        color: var(--osr-primary);
      }
    }
  }

  .file-path {
    display: flex;
    align-items: flex-start;
    gap: 3px;
    font-size: 12px;
    color: var(--osr-text-secondary);
    margin-bottom: 6px;
    cursor: pointer;
    line-height: 1.5;

    .path-icon {
      flex-shrink: 0;
      margin-top: 2px;
      color: var(--osr-text-disabled);
    }

    .path-text {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      word-break: break-all;
    }

    &:hover {
      color: var(--osr-primary);
    }
  }

  .card-time {
    display: flex;
    align-items: center;
    gap: 3px;
    font-size: 11px;
    color: var(--osr-text-disabled);
  }

  .card-actions {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex-shrink: 0;
    padding-left: 8px;
    border-left: 1px solid var(--osr-border-light);

    .v-btn {
      min-width: 0;
    }
  }
}
</style>
