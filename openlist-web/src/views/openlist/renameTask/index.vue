<template>
  <div class="page-container">
    <!-- Search Panel -->
    <v-card v-if="showSearch" class="search-card">
      <v-form ref="queryRef" @submit.prevent="handleQuery">
        <div class="search-fields">
          <v-text-field
            v-model="queryParams.sourceFolder"
            label="源目录"
            placeholder="请输入源目录"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-text-field
            v-model="queryParams.targetRoot"
            label="目标目录"
            placeholder="请输入目标目录"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-select
            v-model="queryParams.status"
            label="状态"
            :items="[{ title: '启用', value: '1' }, { title: '停用', value: '0' }]"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            class="status-select"
          />
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
          <v-btn color="primary" prepend-icon="mdi-plus" @click="handleAdd">
            新增
          </v-btn>
          <v-btn color="success" prepend-icon="mdi-pencil-outline" :disabled="single" @click="handleUpdate()">
            修改
          </v-btn>
          <v-btn color="error" prepend-icon="mdi-delete-outline" :disabled="multiple" @click="handleDelete()">
            批量删除
          </v-btn>
          <v-btn color="warning" prepend-icon="mdi-play-outline" :disabled="multiple" @click="handleExecute()">
            批量执行
          </v-btn>
          <v-btn color="info" prepend-icon="mdi-auto-fix" @click="handleTest()">
            测试
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
        :items="taskList"
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
        <template #item.config="{ item }">
          <div class="path-box">
            <div class="path-row"><span class="path-label label-src">源</span> <span class="path-text">{{ item.sourceFolder }}</span></div>
            <div class="path-row"><span class="path-label label-dst">目</span> <span class="path-text">{{ item.targetRoot }}</span></div>
          </div>
        </template>
        <template #item.status="{ item }">
          <v-chip size="small" :color="item.status === '0' ? 'error' : 'success'" variant="tonal">
            {{ item.status === '0' ? '停用' : '启用' }}
          </v-chip>
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item)">
            修改
          </v-btn>
          <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">
            删除
          </v-btn>
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-play-outline" @click="handleExecuteOne(item)">
            执行
          </v-btn>
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-auto-fix" @click="handleTestOne(item)">
            测试
          </v-btn>
        </template>
      </v-data-table-server>

      <!-- Mobile Card List -->
      <div v-if="appStore.device === 'mobile'" class="mobile-card-list">
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <v-card v-for="item in taskList" :key="item.id" variant="outlined" class="mobile-card">
          <div class="mobile-card-header">
            <span class="mobile-card-title"><v-icon icon="mdi-swap-horizontal" size="14" /> {{ item.sourceFolder }}</span>
            <v-chip size="small" :color="item.status === '0' ? 'error' : 'success'" variant="tonal">
              {{ item.status === '0' ? '停用' : '启用' }}
            </v-chip>
          </div>
          <div class="mobile-card-body">
            <div class="mobile-card-row">
              <span class="mobile-card-label">目标</span>
              <span class="mobile-card-value mobile-card-value-clip">{{ item.targetRoot }}</span>
            </div>
            <div class="mobile-card-row">
              <span class="mobile-card-label">创建时间</span>
              <span class="mobile-card-value mobile-card-value-light">{{ item.createTime }}</span>
            </div>
          </div>
          <div class="mobile-card-actions">
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item)">
              修改
            </v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">
              删除
            </v-btn>
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-play-outline" @click="handleExecuteOne(item)">
              执行
            </v-btn>
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-auto-fix" @click="handleTestOne(item)">
              测试
            </v-btn>
          </div>
        </v-card>
        <v-empty-state v-if="!loading && !taskList.length" icon="mdi-inbox-outline" title="暂无数据" />

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

    <!-- Add/Edit Dialog -->
    <v-dialog v-model="open" max-width="600">
      <v-card :title="dialogTitle">
        <v-card-text>
          <v-form ref="formRef">
            <div class="form-item">
              <label class="form-label">源目录</label>
              <DirectoryTreeSelect v-model="form.sourceFolder" type="local" placeholder="请选择源目录" />
            </div>
            <div class="form-item">
              <label class="form-label">目标目录</label>
              <DirectoryTreeSelect v-model="form.targetRoot" type="local" placeholder="请选择目标目录" />
            </div>
            <div class="form-item">
              <label class="form-label">状态</label>
              <v-radio-group v-model="form.status" inline hide-details>
                <v-radio label="停用" value="0" />
                <v-radio label="启用" value="1" />
              </v-radio-group>
            </div>
            <div class="section-label">刮削配置</div>
            <v-divider class="mb-3" />
            <div class="form-item form-item-inline">
              <label class="form-label">启用刮削</label>
              <v-switch v-model="form.scrapeEnabled" true-value="1" false-value="0" color="primary" hide-details density="compact" />
            </div>
            <div class="form-item form-item-inline" v-if="form.scrapeEnabled === '1'">
              <label class="form-label">生成NFO</label>
              <v-switch v-model="form.scrapeNfo" true-value="1" false-value="0" color="primary" hide-details density="compact" />
            </div>
            <div class="form-item form-item-inline" v-if="form.scrapeEnabled === '1'">
              <label class="form-label">下载图片</label>
              <v-switch v-model="form.scrapeImages" true-value="1" false-value="0" color="primary" hide-details density="compact" />
            </div>
            <div class="form-item form-item-inline" v-if="form.scrapeEnabled === '1'">
              <label class="form-label">强制覆盖</label>
              <v-switch v-model="form.scrapeForceOverwrite" true-value="1" false-value="0" color="primary" hide-details density="compact" />
            </div>
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="open = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="submitLoading" @click="submitForm">确定</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Test Dialog -->
    <v-dialog v-model="testOpen" max-width="700">
      <v-card :title="testTitle">
        <v-card-text>
          <v-textarea
            v-model="testForm.filename"
            label="原文件名"
            placeholder="例如: The.Movie.2024.1080p.mkv"
            rows="3"
            density="compact"
            variant="outlined"
          />
          <v-textarea
            v-model="testForm.template"
            label="重命名模板"
            placeholder="留空则使用默认配置"
            rows="4"
            density="compact"
            variant="outlined"
            hint="留空则使用默认配置"
            persistent-hint
          />
          <v-btn color="primary" prepend-icon="mdi-auto-fix" :loading="testLoading" class="mt-3" @click="doTest">
            开始分析
          </v-btn>

          <div v-if="testResult" class="test-result">
            <v-alert type="success" variant="tonal" density="compact" class="mb-3">
              <template #title>重命名结果预览</template>
              <div class="result-text">{{ testResult.renamed }}</div>
            </v-alert>
            <v-alert type="info" variant="tonal" density="compact">
              <template #title>识别参数详情</template>
              <pre class="result-json">{{ JSON.stringify(testResult.info, null, 2) }}</pre>
            </v-alert>
          </div>
        </v-card-text>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import DirectoryTreeSelect from '@/components/DirectoryTreeSelect/index.vue'
import { useAppStore } from '@/stores/app'
import { useRenameTask } from '@/composables/useRenameTask'

const appStore = useAppStore()
const showSearch = ref(window.innerWidth >= 768)

const {
  taskList, loading, total, queryParams,
  getList, queryRef, handleQuery, resetQuery,
  single, multiple, handleSelectionChange,
  open, dialogTitle, submitLoading, formRef, form,
  handleAdd, handleUpdate, submitForm, handleDelete,
  handleExecuteOne, handleBatchExecute: handleExecute,
  testOpen, testTitle, testLoading, testResult, testForm, handleTest, handleTestOne, doTest
} = useRenameTask()

const headers = [
  { title: '任务路径配置', key: 'config', minWidth: '300' },
  { title: '状态', key: 'status', align: 'center' as const, width: '80' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '260', sortable: false }
]

// v-data-table-server 的多选需要一个本地 ref 承接当前选中的行对象，
// 再转给 useRenameTask 的 handleSelectionChange 去派生 selectedIds/single/multiple
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
</script>

<style scoped lang="scss">
.page-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ============================================
   Search Card
   ============================================ */
.search-card {
  padding: 16px 20px;
}

.search-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 12px;

  > .v-text-field,
  > .v-select {
    width: 220px;
    flex: 0 0 auto;
  }

  .status-select {
    width: 140px;
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
  padding: 20px;
  display: flex;
  flex-direction: column;
}

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;

  .action-left {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
}

/* ============================================
   Pagination
   ============================================ */
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

/* ============================================
   Rename Config Column (Desktop Table)
   ============================================ */
.path-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 0;
}

.path-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.path-label {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 3px;
  line-height: 1.4;
}

.label-src {
  color: #409eff;
  background: rgba(64, 158, 255, 0.1);
}

.label-dst {
  color: #67c23a;
  background: rgba(103, 194, 58, 0.1);
}

.path-text {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--osr-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ============================================
   Form
   ============================================ */
.form-item {
  margin-bottom: 16px;

  .form-label {
    display: block;
    margin-bottom: 6px;
    font-size: 13px;
    color: var(--osr-text-secondary);
  }

  &.form-item-inline {
    display: flex;
    align-items: center;
    justify-content: space-between;

    .form-label {
      margin-bottom: 0;
    }
  }
}

.section-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--osr-text-primary);
  margin-bottom: 8px;
}

/* ============================================
   Test Dialog
   ============================================ */
.test-result {
  margin-top: 8px;

  .result-text {
    font-family: Consolas, monospace;
    word-break: break-all;
    white-space: pre-wrap;
  }

  .result-json {
    max-height: 300px;
    overflow: auto;
    font-size: 12px;
    background: var(--osr-bg-page);
    padding: 10px;
    border-radius: 4px;
  }
}

/* ============================================
   Mobile Responsive
   ============================================ */
@media (max-width: 768px) {
  .search-fields {
    > .v-text-field,
    > .v-select,
    .status-select {
      width: 100%;
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
    gap: 8px;
  }
}

/* ============================================
   Mobile Card List
   ============================================ */
.mobile-card-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px 0;
}

.mobile-card {
  overflow: hidden;

  .mobile-card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 12px 14px 8px;
    border-bottom: 1px solid var(--osr-border-light);

    .mobile-card-title {
      font-size: 14px;
      font-weight: 600;
      color: var(--osr-text-primary);
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      margin-right: 8px;

      .v-icon { color: var(--osr-primary); margin-right: 4px; }
    }
  }

  .mobile-card-body {
    padding: 10px 14px;

    .mobile-card-row {
      display: flex;
      padding: 4px 0;
      font-size: 13px;

      .mobile-card-label {
        width: 72px;
        color: var(--osr-text-secondary);
        flex-shrink: 0;
      }

      .mobile-card-value {
        flex: 1;
        color: var(--osr-text-primary);
        word-break: break-all;

        &.mobile-card-value-clip {
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
          max-width: 200px;
        }

        &.mobile-card-value-light {
          color: var(--osr-text-secondary);
        }
      }
    }
  }

  .mobile-card-actions {
    display: flex;
    justify-content: flex-end;
    gap: 4px;
    padding: 8px 14px 12px;
    border-top: 1px solid var(--osr-border-light);
  }
}
</style>
