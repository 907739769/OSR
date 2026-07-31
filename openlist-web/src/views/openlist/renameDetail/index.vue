<template>
  <div class="page-container">
    <!-- Search Panel -->
    <v-card v-if="showSearch" class="search-card">
      <v-form ref="queryRef" @submit.prevent="handleQuery">
        <div class="search-fields">
          <v-text-field
            v-model="queryParams.originalName"
            label="原文件名"
            placeholder="请输入原文件名"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-text-field
            v-model="queryParams.newName"
            label="新文件名"
            placeholder="请输入新文件名"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-text-field
            v-model="queryParams.originalPath"
            label="原目录"
            placeholder="请输入原目录"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-text-field
            v-model="queryParams.newPath"
            label="新目录"
            placeholder="请输入新目录"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-text-field
            v-model="queryParams.title"
            label="影视名称"
            placeholder="请输入影视名称"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-select
            v-model="queryParams.status"
            label="状态"
            :items="[{ title: '成功', value: '1' }, { title: '失败', value: '0' }]"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            class="status-select"
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
          <div class="search-actions">
            <v-btn color="primary" prepend-icon="mdi-magnify" @click="handleQuery">搜索</v-btn>
            <v-btn variant="outlined" prepend-icon="mdi-refresh" @click="resetQuery">重置</v-btn>
          </div>
        </div>
      </v-form>
    </v-card>

    <!-- Table Card -->
    <v-card class="table-card">
      <!-- Action Bar -->
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="error" prepend-icon="mdi-delete-outline" :disabled="multiple" @click="handleBatchDelete()">
            批量删除记录
          </v-btn>
          <v-btn color="info" prepend-icon="mdi-refresh" :disabled="multiple" @click="handleBatchExecute()">
            批量执行
          </v-btn>
          <v-btn color="warning" prepend-icon="mdi-refresh" :disabled="multiple" @click="handleBatchScrape()">
            批量刮削
          </v-btn>
          <v-btn color="error" variant="outlined" prepend-icon="mdi-delete-outline" :disabled="multiple" @click="handleBatchDeleteScrape()">
            批量删除刮削
          </v-btn>
        </div>
        <v-btn variant="text" prepend-icon="mdi-filter-outline" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <!-- Desktop Table -->
      <v-data-table-server
        v-if="appStore.device === 'desktop'"
        :loading="loading"
        :items="recordList"
        :items-length="total"
        :headers="headers"
        :items-per-page="queryParams.pageSize"
        :page="queryParams.pageNum"
        show-select
        item-value="id"
        return-object
        :model-value="selectedRows"
        class="modern-table"
        @update:model-value="onSelectionChange"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
      >
        <template #item.detail="{ item }">
          <div class="rename-compare">
            <!-- Name comparison -->
            <div class="rename-name-row">
              <div class="rename-side rename-original">
                <span class="rename-badge rename-badge-original">原</span>
                <span class="rename-filename" :title="item.originalName">{{ item.originalName }}</span>
              </div>
              <v-icon class="rename-arrow" icon="mdi-arrow-right" size="16" />
              <div class="rename-side rename-new">
                <span class="rename-badge rename-badge-new">新</span>
                <span class="rename-filename" :title="item.newName">{{ item.newName }}</span>
              </div>
            </div>
            <!-- Path comparison -->
            <div class="rename-path-row">
              <div class="rename-side rename-original">
                <span class="rename-path-text" :title="item.originalPath">{{ item.originalPath }}</span>
              </div>
              <v-icon class="rename-arrow" icon="mdi-arrow-right" size="12" />
              <div class="rename-side rename-new">
                <span class="rename-path-text" :title="item.newPath">{{ item.newPath }}</span>
              </div>
            </div>
          </div>
        </template>
        <template #item.status="{ item }">
          <v-chip size="small" :color="item.status === '0' ? 'error' : 'success'" variant="tonal">
            {{ item.status === '0' ? '失败' : '成功' }}
          </v-chip>
        </template>
        <template #item.scrapeStatus="{ item }">
          <v-chip v-if="item.scrapeStatus === '1'" color="success" size="small" variant="tonal">成功</v-chip>
          <v-chip v-else-if="item.scrapeStatus === '2'" color="error" size="small" variant="tonal">失败</v-chip>
          <v-chip v-else-if="item.scrapeStatus === '0'" color="info" size="small" variant="tonal">未执行</v-chip>
          <span v-else class="scrape-none">-</span>
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-refresh" @click="handleScrapeOne(item)">
            刮削
          </v-btn>
          <v-btn v-if="item.scrapeStatus === '1'" variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDeleteScrapeOne(item)">
            删除刮削
          </v-btn>
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-refresh" @click="handleRetryOne(item)">
            重试
          </v-btn>
          <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDeleteOne(item)">
            删除记录
          </v-btn>
        </template>
      </v-data-table-server>

      <!-- Mobile Card List -->
      <div v-if="appStore.device === 'mobile'" class="mobile-card-list">
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <v-card v-for="item in recordList" :key="item.id" variant="outlined" class="mobile-card">
          <div class="mobile-card-header">
            <div class="mobile-rename-header">
              <span class="mobile-rename-original" :title="item.originalName">{{ item.originalName }}</span>
              <v-icon class="mobile-rename-arrow" icon="mdi-arrow-right" size="14" />
              <span class="mobile-rename-new" :title="item.newName">{{ item.newName }}</span>
            </div>
            <div class="mobile-status-row">
              <v-chip size="small" :color="item.status === '0' ? 'error' : 'success'" variant="tonal">
                {{ item.status === '0' ? '失败' : '成功' }}
              </v-chip>
              <v-chip v-if="item.scrapeStatus === '1'" color="success" size="small" variant="tonal" class="scrape-tag">NFO</v-chip>
              <v-chip v-else-if="item.scrapeStatus === '2'" color="error" size="small" variant="tonal" class="scrape-tag">刮削失败</v-chip>
              <v-chip v-else-if="item.scrapeStatus === '0'" color="info" size="small" variant="tonal" class="scrape-tag">未刮削</v-chip>
            </div>
          </div>
          <div class="mobile-card-body">
            <div class="mobile-card-row">
              <span class="mobile-card-label">原路径</span>
              <span class="mobile-card-value mobile-card-value-path" :title="item.originalPath">{{ item.originalPath }}</span>
            </div>
            <div class="mobile-card-row">
              <span class="mobile-card-label">新路径</span>
              <span class="mobile-card-value mobile-card-value-path" :title="item.newPath">{{ item.newPath }}</span>
            </div>
            <div class="mobile-card-row">
              <span class="mobile-card-label">创建时间</span>
              <span class="mobile-card-value mobile-card-value-light">{{ item.createTime }}</span>
            </div>
          </div>
          <div class="mobile-card-actions">
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-refresh" @click="handleRetryOne(item)">
              重试
            </v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDeleteOne(item)">
              删记录
            </v-btn>
          </div>
        </v-card>
        <v-empty-state v-if="!loading && !recordList.length" icon="mdi-inbox-outline" title="暂无数据" />

        <!-- Pagination (mobile; desktop paginates via v-data-table-server) -->
        <div class="pagination-wrapper">
          <v-pagination
            v-model="queryParams.pageNum"
            :length="Math.ceil(total / queryParams.pageSize) || 1"
            density="comfortable"
            @update:model-value="getList"
          />
        </div>
      </div>
    </v-card>

    <!-- Retry Dialog -->
    <v-dialog v-model="retryDialogVisible" max-width="420" @update:model-value="onRetryDialogUpdate">
      <v-card title="重试重命名">
        <v-card-text>
          <v-form ref="retryFormRef">
            <v-text-field
              v-model="retryForm.title"
              label="标题"
              placeholder="留空则使用原值"
              maxlength="100"
              clearable
              :rules="[titleRule]"
              class="mb-2"
            />
            <v-text-field
              v-model="retryForm.year"
              label="年份"
              placeholder="留空则使用原值"
              maxlength="4"
              clearable
              :rules="[yearRule]"
              class="mb-2"
            />
            <v-text-field
              v-if="retryForm.mediaType === 'tv'"
              v-model="retryForm.season"
              label="季"
              placeholder="如 01"
              maxlength="4"
              clearable
              :rules="[seasonRule]"
              class="mb-2"
            />
            <v-text-field
              v-if="retryForm.mediaType === 'tv'"
              v-model="retryForm.episode"
              label="集"
              placeholder="如 05"
              maxlength="6"
              clearable
              :rules="[episodeRule]"
            />
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="retryDialogVisible = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="retryLoading" @click="handleRetrySubmit">确定</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useAppStore } from '@/stores/app'
import { useRenameDetailList } from '@/composables/useRenameDetailList'

const appStore = useAppStore()
const showSearch = ref(window.innerWidth >= 768)

const {
  recordList, loading, total, queryParams,
  getList, queryRef, dateRange, handleQuery, resetQuery,
  multiple, handleSelectionChange,
  handleDeleteOne, handleBatchDelete,
  retryDialogVisible, retryLoading, retryFormRef, retryForm,
  handleRetryOne, handleRetryClose, handleRetrySubmit,
  handleBatchExecute, handleScrapeOne, handleBatchScrape,
  handleDeleteScrapeOne, handleBatchDeleteScrape
} = useRenameDetailList()

getList()

// dateRange 是 el-date-picker daterange 遗留的 [start, end] 数组结构，
// 拆成两个独立日期输入框绑定，写回时仍保持数组形状供 handleQuery 组装 params
const dateStart = computed({
  get: () => dateRange.value?.[0] ?? '',
  set: (val: string) => {
    dateRange.value = [val || '', dateRange.value?.[1] ?? '']
    if (!dateRange.value[0] && !dateRange.value[1]) dateRange.value = null
  }
})
const dateEnd = computed({
  get: () => dateRange.value?.[1] ?? '',
  set: (val: string) => {
    dateRange.value = [dateRange.value?.[0] ?? '', val || '']
    if (!dateRange.value[0] && !dateRange.value[1]) dateRange.value = null
  }
})

const headers = [
  { title: '重命名详情', key: 'detail', minWidth: '400' },
  { title: '状态', key: 'status', align: 'center' as const, width: '80' },
  { title: '刮削', key: 'scrapeStatus', align: 'center' as const, width: '90' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '320', sortable: false }
]

// v-data-table-server 的多选需要一个本地 ref 承接当前选中的行对象，
// 再转给 useRenameDetailList 的 handleSelectionChange 去派生 selectedIds/multiple
const selectedRows = ref<any[]>([])
const onSelectionChange = (rows: any[]) => {
  selectedRows.value = rows
  handleSelectionChange(rows)
}

const onPageChange = (page: number) => {
  queryParams.pageNum = page
  getList()
}

const onSizeChange = (size: number) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
}

const onRetryDialogUpdate = (val: boolean) => {
  if (!val) handleRetryClose()
}

// 重试弹窗字段校验，规则与原 retryRules 保持一致
const titleRule = (v: string) => !v || v.length <= 100 || '最多 100 个字符'
const yearRule = (v: string) => !v || /^\d{0,4}$/.test(v) || '年份为 4 位数字'
const seasonRule = (v: string) => !v || /^\d{1,2}$/.test(v) || '季为 1-2 位数字'
const episodeRule = (v: string) => !v || /^\d{1,4}$/.test(v) || '集为 1-4 位数字'
</script>

<style scoped lang="scss">
.page-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* ============================================
   Search Card
   ============================================ */
.search-card {
  padding: 14px 16px;
}

.search-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 12px;

  > .v-text-field,
  > .v-select {
    width: 200px;
    flex: 0 0 auto;
  }

  .status-select {
    width: 140px;
  }

  .date-range-fields {
    display: flex;
    align-items: center;
    gap: 6px;
    flex: 0 0 auto;

    .date-field {
      width: 150px;
    }

    .date-range-sep {
      color: var(--osr-text-secondary);
    }
  }

  .search-actions {
    display: flex;
    gap: 8px;
    align-items: center;
    margin-top: 2px;
  }
}

/* ============================================
   Table Card
   ============================================ */
.table-card {
  padding: 16px;
  display: flex;
  flex-direction: column;
}

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;

  .action-left {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }
}

/* ============================================
   Rename Comparison (PC Table)
   ============================================ */
:deep(.rename-compare) {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 0;

  .rename-name-row {
    display: flex;
    align-items: center;
    gap: 6px;
    min-height: 28px;

    .rename-side {
      display: flex;
      align-items: center;
      gap: 4px;
      min-width: 0;
      flex: 1;

      .rename-badge {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 20px;
        height: 20px;
        border-radius: 4px;
        font-size: 11px;
        font-weight: 700;
        flex-shrink: 0;

        &.rename-badge-original {
          background: #fef2f2;
          color: #ef4444;
          border: 1px solid #fecaca;
        }

        &.rename-badge-new {
          background: #f0fdf4;
          color: #22c55e;
          border: 1px solid #bbf7d0;
        }
      }

      .rename-filename {
        font-size: 13px;
        font-weight: 500;
        color: var(--osr-text-primary);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        line-height: 1.4;
      }
    }

    .rename-arrow {
      flex-shrink: 0;
      color: var(--osr-text-disabled);
    }
  }

  .rename-path-row {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 11px;

    .rename-side {
      min-width: 0;
      flex: 1;

      .rename-path-text {
        color: var(--osr-text-secondary);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        display: block;
        line-height: 1.4;
      }
    }

    .rename-arrow {
      flex-shrink: 0;
      color: var(--osr-text-disabled);
    }
  }
}

/* ============================================
   Scrape Status
   ============================================ */
.scrape-none {
  color: var(--osr-text-placeholder);
  font-size: 13px;
}

.scrape-tag {
  margin-left: 4px;
  font-size: 11px;
}

.mobile-status-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

/* ============================================
   Pagination
   ============================================ */
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: auto;
  padding-top: 12px;
}

/* ============================================
   Mobile Responsive
   ============================================ */
@media (max-width: 768px) {
  .page-container {
    gap: 10px;
  }

  .search-fields {
    > .v-text-field,
    > .v-select,
    .status-select {
      width: 100%;
    }

    .date-range-fields {
      width: 100%;

      .date-field {
        width: 100%;
      }
    }

    .search-actions {
      width: 100%;

      .v-btn {
        flex: 1;
      }
    }
  }

  .action-bar {
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 10px;

    .action-left {
      gap: 4px;
    }
  }

  .table-card {
    padding: 12px;
  }

  /* ============================================
     Mobile Card List
     ============================================ */
  .mobile-card-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .mobile-card {
    overflow: hidden;

    .mobile-card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 10px 12px 8px;
      border-bottom: 1px solid var(--osr-border-light);
      background: var(--osr-bg-page);

      .mobile-rename-header {
        display: flex;
        align-items: center;
        gap: 4px;
        flex: 1;
        min-width: 0;

        .mobile-rename-original {
          font-size: 13px;
          font-weight: 500;
          color: #dc2626;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
          text-decoration: line-through;
          text-decoration-color: #dc2626;
          flex: 1;
          min-width: 0;
        }

        .mobile-rename-arrow {
          flex-shrink: 0;
          color: var(--osr-text-disabled);
        }

        .mobile-rename-new {
          font-size: 13px;
          font-weight: 600;
          color: #16a34a;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
          flex: 1;
          min-width: 0;
        }
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
          line-height: 1.5;
          padding-top: 1px;
        }

        .mobile-card-value {
          flex: 1;
          min-width: 0;
          color: var(--osr-text-primary);
          font-size: 13px;
          line-height: 1.5;
          word-break: break-all;

          &.mobile-card-value-clip {
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
          }

          &.mobile-card-value-path {
            color: var(--osr-text-placeholder);
            font-size: 12px;
            line-height: 1.6;
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
}
</style>
