<template>
  <div class="mobile-page">
    <!-- 搜索 -->
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <v-text-field
        v-model="queryParams.copyTaskSrc"
        label="源目录"
        placeholder="请输入源目录"
        clearable
        density="compact"
        variant="outlined"
        @keyup.enter="handleQuery"
      />
      <v-text-field
        v-model="queryParams.copyTaskDst"
        label="目标目录"
        placeholder="请输入目标目录"
        clearable
        density="compact"
        variant="outlined"
        @keyup.enter="handleQuery"
      />
      <v-text-field
        v-model="queryParams.monitorDir"
        label="监控目录"
        placeholder="请输入监控目录"
        clearable
        density="compact"
        variant="outlined"
        @keyup.enter="handleQuery"
      />
      <v-select
        v-model="queryParams.copyTaskStatus"
        label="状态"
        placeholder="全部状态"
        :items="[{ title: '启用', value: '1' }, { title: '停用', value: '0' }]"
        clearable
        density="compact"
        variant="outlined"
      />
    </MobileSearchPanel>

    <!-- Batch Actions -->
    <div class="batch-bar" v-if="selectedIds.length > 0">
      <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
      <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-play-outline" @click="handleBatchExecute">
        批量执行
      </v-btn>
      <v-btn variant="text" size="small" @click="clearSelection">
        取消
      </v-btn>
    </div>

    <!-- Add Button (FAB) -->
    <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="mdi-plus" @click="handleAdd('新增文件同步任务')">
      新增
    </v-btn>

    <!-- Task List -->
    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <div
        v-for="task in taskList"
        :key="task.copyTaskId"
        class="task-card"
        :class="{ selected: selectedIds.includes(task.copyTaskId) }"
        @click="handleCardClick($event, task.copyTaskId)"
      >
        <div class="card-checkbox">
          <v-checkbox
            :model-value="selectedIds.includes(task.copyTaskId)"
            density="compact"
            hide-details
            @click.stop="toggleSelect(task.copyTaskId)"
          />
        </div>
        <div class="card-content">
          <div class="card-top">
            <div class="task-name-row">
              <v-icon class="task-icon" icon="mdi-map-marker-outline" size="18" />
              <span class="task-name" @click.stop="showFullText(task.copyTaskSrc, '源目录')">{{ task.copyTaskSrc }}</span>
            </div>
            <v-chip :color="task.copyTaskStatus === '1' ? 'success' : 'error'" size="small" variant="tonal">
              {{ task.copyTaskStatus === '1' ? '启用' : '停用' }}
            </v-chip>
          </div>
          <div class="task-path" @click.stop="showFullText(task.copyTaskDst, '目标目录')">
            <v-icon class="path-icon" icon="mdi-map-marker-outline" size="14" />
            <span class="path-text">{{ task.copyTaskDst }}</span>
          </div>
          <div class="task-path monitor-path" v-if="task.monitorDir" @click.stop="showFullText(task.monitorDir, '监控目录')">
            <v-icon class="path-icon" icon="mdi-filter-outline" size="14" />
            <span class="path-text">{{ task.monitorDir }}</span>
          </div>
          <div class="card-time">
            <v-icon icon="mdi-clock-outline" size="12" />
            {{ task.createTime }}
          </div>
          <div class="card-actions" @click.stop>
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-play-outline" @click="handleExecuteOne(task, `是否确认执行同步任务“${task.copyTaskSrc}”？`)">
              执行
            </v-btn>
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(task, '修改文件同步任务')">
              修改
            </v-btn>
            <v-btn class="action-more" variant="text" color="default" size="small" icon="mdi-dots-horizontal" @click="openActionDrawer(task)" />
          </div>
        </div>
      </div>

      <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无同步任务" />
    </div>

    <!-- 操作抽屉 -->
    <v-bottom-sheet v-model="actionDrawerOpen">
      <v-card v-if="actionDrawerTarget" title="更多操作">
        <v-card-text>
          <div class="drawer-actions">
            <v-btn color="error" block prepend-icon="mdi-delete-outline" @click="handleDelete(actionDrawerTarget); actionDrawerOpen = false">删除</v-btn>
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

    <!-- Add/Edit Dialog -->
    <v-dialog v-model="open" width="90%" class="modern-dialog">
      <v-card :title="dialogTitle">
        <v-card-text>
          <v-form ref="formRef">
            <div class="form-item">
              <label class="form-label">源目录</label>
              <DirectoryTreeSelect v-model="form.copyTaskSrc" type="openlist" placeholder="请选择源目录" />
            </div>
            <div class="form-item">
              <label class="form-label">目标目录</label>
              <DirectoryTreeSelect v-model="form.copyTaskDst" type="openlist" placeholder="请选择目标目录" />
            </div>
            <div class="form-item">
              <label class="form-label">监控目录</label>
              <DirectoryTreeSelect v-model="form.monitorDir" type="local" placeholder="请选择监控目录（可选）" />
            </div>
            <div class="form-item">
              <label class="form-label">状态</label>
              <v-radio-group v-model="form.copyTaskStatus" inline hide-details>
                <v-radio label="启用" value="1" />
                <v-radio label="停用" value="0" />
              </v-radio-group>
            </div>
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="open = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="submitLoading" @click="handleSubmitClick">确定</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import DirectoryTreeSelect from '@/components/DirectoryTreeSelect/index.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import FullTextDialog from '@/components/mobile/FullTextDialog.vue'
import { useCopyTask } from '@/composables/useCopyTask'
import { useDebounce } from '@/composables/useDebounce'
import { message } from '@/composables/useMessage'

const {
  taskList, loading, total, queryParams,
  getList, handleQuery, resetQuery,
  selectedIds,
  open, dialogTitle, submitLoading, formRef, form,
  handleAdd, handleUpdate, submitForm,
  handleDelete, handleExecuteOne,
  toggleSelect, handleCardClick, clearSelection,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed, handleBatchExecute
} = useCopyTask()

const fullTextRef = ref<InstanceType<typeof FullTextDialog>>()
const showFullText = (content: string, title: string) => fullTextRef.value?.show(content, title)

/** 更多操作抽屉 */
const actionDrawerOpen = ref(false)
const actionDrawerTarget = ref<any>(null)
const openActionDrawer = (row: any) => {
  actionDrawerTarget.value = row
  actionDrawerOpen.value = true
}

// DirectoryTreeSelect 不支持 v-form 的 :rules 校验，改为提交前手动校验必填项
const handleSubmitClick = () => {
  if (!form.value.copyTaskSrc) {
    message.warning('源目录不能为空')
    return
  }
  if (!form.value.copyTaskDst) {
    message.warning('目标目录不能为空')
    return
  }
  submitForm()
}

// 搜索输入防抖：输入停止 300ms 后自动触发搜索
const debouncedSearch = useDebounce(() => {
  queryParams.pageNum = 1
  getList()
}, 300)

watch(
  () => [queryParams.copyTaskSrc, queryParams.copyTaskDst, queryParams.monitorDir, queryParams.copyTaskStatus],
  () => debouncedSearch()
)
</script>

<style scoped lang="scss">
.mobile-page {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: calc(100vh - 120px);
  padding-bottom: 8px;

  .task-list {
    flex: 1;
  }
}

/* ============================================
   Batch Action Bar
   ============================================ */


/* ============================================
   Task List
   ============================================ */
.task-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 200px;
}

.task-card {
  .card-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;
    gap: 8px;
  }

  .task-name-row {
    display: flex;
    align-items: center;
    gap: 5px;
    min-width: 0;
    flex: 1;

    .task-icon {
      color: var(--osr-primary);
      flex-shrink: 0;
    }

    .task-name {
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

  .task-path {
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

    &.monitor-path .path-text {
      color: var(--osr-warning);
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

}

/* ============================================
   FAB Add Button
   ============================================ */


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
}
</style>
