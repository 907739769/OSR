<template>
  <div class="mobile-page">
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <v-form ref="queryRef">
        <v-text-field
          v-model="queryParams.title"
          label="影视名称"
          placeholder="请输入影视名称"
          clearable
          density="compact"
          variant="outlined"
          class="mb-3"
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
          class="mb-3"
        />
        <v-select
          v-model="queryParams.status"
          label="状态"
          :items="[{ title: '待处理', value: '0' }, { title: '已清理', value: '1' }, { title: '已忽略', value: '2' }]"
          placeholder="全部状态"
          clearable
          density="compact"
          variant="outlined"
        />
      </v-form>
    </MobileSearchPanel>

    <div class="scan-bar">
      <v-btn color="primary" size="small" prepend-icon="mdi-refresh" :loading="scanning" @click="handleScanNow">
        立即扫描
      </v-btn>
    </div>

    <div class="batch-bar" v-if="selectedIds.length > 0">
      <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
      <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleBatchClean()">
        清理
      </v-btn>
      <v-btn variant="text" color="warning" size="small" prepend-icon="mdi-alert-outline" @click="handleBatchIgnore()">
        忽略
      </v-btn>
      <v-btn variant="text" size="small" @click="clearSelection">
        取消
      </v-btn>
    </div>

    <div class="mobile-card-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <div v-for="item in recordList" :key="item.id" class="mobile-card" @click="handleCardClick($event, item.id)">
        <div class="mobile-card-header">
          <v-checkbox
            class="card-checkbox"
            :model-value="selectedIds.includes(item.id)"
            density="compact"
            hide-details
            @click.stop
            @update:model-value="toggleSelect(item.id)"
          />
          <span class="mobile-title">{{ item.title || '未知' }}<span v-if="item.year">（{{ item.year }}）</span></span>
          <v-chip v-if="item.reason === 'local_missing'" size="small" color="warning" variant="tonal">本地丢失</v-chip>
          <v-chip v-else size="small" color="error" variant="tonal">网盘源丢失</v-chip>
        </div>
        <div class="mobile-card-body">
          <div class="mobile-card-row">
            <span class="mobile-card-label">路径</span>
            <span class="mobile-card-value mobile-card-value-path" :title="`${item.newPath}/${item.newName}`">{{ item.newPath }}/{{ item.newName }}</span>
          </div>
          <div class="mobile-card-row">
            <span class="mobile-card-label">发现时间</span>
            <span class="mobile-card-value mobile-card-value-light">{{ item.foundTime }}</span>
          </div>
        </div>
        <div class="mobile-card-actions" v-if="item.status === '0'">
          <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click.stop="handleCleanOne(item)">
            清理
          </v-btn>
          <v-btn variant="text" color="warning" size="small" prepend-icon="mdi-alert-outline" @click.stop="handleIgnoreOne(item)">
            忽略
          </v-btn>
        </div>
      </div>
      <v-empty-state v-if="!loading && !recordList.length" icon="mdi-inbox-outline" title="暂无数据" />
    </div>

    <div class="pagination-wrapper">
      <v-pagination
        v-model="queryParams.pageNum"
        :length="Math.ceil(total / queryParams.pageSize) || 1"
        density="comfortable"
        @update:model-value="getList"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import { useRenameOrphanList } from '@/composables/useRenameOrphanList'

const searchCollapsed = ref(true)

const {
  recordList, loading, total, queryParams,
  getList, queryRef, handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  handleCleanOne, handleBatchClean,
  scanning, handleScanNow,
  handleIgnoreOne, handleBatchIgnore
} = useRenameOrphanList()

getList()
</script>

<style scoped lang="scss">
.mobile-page {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
}

.scan-bar {
  display: flex;
  justify-content: flex-end;
}

.batch-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--osr-bg-page);
  border-radius: 8px;

  .selected-count {
    font-size: 13px;
    color: var(--osr-text-secondary);
    margin-right: auto;
  }
}

.mobile-card-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.mobile-card {
  background: var(--osr-surface);
  border-radius: 8px;
  border: 1px solid var(--osr-border-light);
  overflow: hidden;

  .mobile-card-header {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 10px 12px 8px;
    border-bottom: 1px solid var(--osr-border-light);
    background: var(--osr-bg-page);

    .card-checkbox {
      flex: 0 0 auto;

      :deep(.v-selection-control) {
        min-height: unset;
      }
    }

    .mobile-title {
      flex: 1;
      min-width: 0;
      font-size: 13px;
      font-weight: 500;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .mobile-card-body {
    padding: 0;

    .mobile-card-row {
      display: flex;
      align-items: flex-start;
      padding: 8px 12px;
      font-size: 13px;
      border-bottom: 1px solid var(--osr-border-light);

      &:last-child {
        border-bottom: none;
      }

      .mobile-card-label {
        width: 64px;
        color: var(--osr-text-secondary);
        flex-shrink: 0;
        font-size: 12px;
      }

      .mobile-card-value {
        flex: 1;
        min-width: 0;
        font-size: 13px;
        word-break: break-all;

        &.mobile-card-value-path {
          color: var(--osr-text-placeholder);
          font-size: 12px;
        }

        &.mobile-card-value-light {
          color: var(--osr-text-secondary);
          font-size: 12px;
        }
      }
    }
  }

  .mobile-card-actions {
    display: flex;
    justify-content: flex-end;
    gap: 2px;
    padding: 8px 12px 10px;
    border-top: 1px solid var(--osr-border-light);
  }
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  padding-top: 8px;
}
</style>
