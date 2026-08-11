<template>
  <div class="mobile-page">
    <!-- 搜索 -->
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <v-form ref="queryRef">
        <v-text-field
          v-model="queryParams.strmTaskPath"
          label="STRM目录"
          placeholder="请输入STRM目录"
          clearable
          density="compact"
          variant="outlined"
          hide-details
          @keyup.enter="handleQuery"
        />
        <v-select
          v-model="queryParams.strmTaskStatus"
          label="状态"
          placeholder="全部状态"
          :items="[{ title: '启用', value: '1' }, { title: '停用', value: '0' }]"
          clearable
          density="compact"
          variant="outlined"
          hide-details
        />
      </v-form>
    </MobileSearchPanel>

    <!-- 全选本页：卡片上的勾选框常驻，但批量条要选中一项才出现，全选框只能放在列表上方 -->
    <MobileSelectAll
      :all-selected="isAllPageSelected"
      :indeterminate="isIndeterminate"
      @toggle="toggleSelectAllPage"
    />

    <!-- Batch Actions -->
    <div class="batch-bar" v-if="selectedIds.length > 0">
      <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
      <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-play-circle-outline" @click="handleBatchExecute">
        批量执行
      </v-btn>
      <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(undefined, `是否确认删除选中的 ${selectedIds.length} 个STRM任务？`)">
        批量删除
      </v-btn>
      <v-btn variant="text" size="small" @click="clearSelection">
        取消
      </v-btn>
    </div>

    <!-- Add Button (FAB) -->
    <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="mdi-plus" @click="handleAdd('新增STRM任务')">
      新增
    </v-btn>

    <!-- Task List -->
    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <v-card
        v-for="task in taskList"
        :key="task.strmTaskId"
        class="task-card"
        :class="{ selected: selectedIds.includes(task.strmTaskId) }"
        @click="handleCardClick($event, task.strmTaskId)"
      >
        <div class="card-checkbox">
          <v-checkbox
            :model-value="selectedIds.includes(task.strmTaskId)"
            density="compact"
            hide-details
            @click.stop="toggleSelect(task.strmTaskId)"
          />
        </div>
        <div class="card-content">
          <div class="card-top">
            <div class="card-title-row">
              <v-icon class="card-title-icon" icon="mdi-file-video-outline" size="18" />
              <span class="card-title card-title--link" @click.stop="showFullText(task.strmTaskPath, 'STRM目录')">{{ task.strmTaskPath }}</span>
            </div>
            <StatusChip :value="task.strmTaskStatus" />
          </div>
          <div class="card-time">
            <v-icon icon="mdi-clock-outline" size="14" />
            {{ task.createTime }}
          </div>
          <div class="card-actions" @click.stop>
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-play-circle-outline" @click="handleExecuteOne(task, `是否确认执行STRM任务“${task.strmTaskPath}”？`)">执行</v-btn>
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(task, '修改STRM任务')">修改</v-btn>
            <v-btn class="action-more" variant="text" color="default" size="small" icon="mdi-dots-horizontal" @click="openActionDrawer(task)" />
          </div>
        </div>
      </v-card>

      <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无STRM任务" />
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
    <v-dialog v-model="open" width="92%">
      <v-card :title="dialogTitle">
        <v-card-text>
          <v-form ref="formRef">
            <FormField label="STRM目录">
              <DirectoryTreeSelect v-model="form.strmTaskPath" type="openlist" placeholder="请选择STRM目录" />
            </FormField>
            <FormField label="状态">
              <v-radio-group v-model="form.strmTaskStatus" inline hide-details>
                <v-radio label="停用" value="0" />
                <v-radio label="启用" value="1" />
              </v-radio-group>
            </FormField>
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
import StatusChip from '@/components/StatusChip.vue'
import FormField from '@/components/FormField.vue'
import { ref, watch } from 'vue'
import DirectoryTreeSelect from '@/components/DirectoryTreeSelect/index.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import FullTextDialog from '@/components/mobile/FullTextDialog.vue'
import MobileSelectAll from '@/components/mobile/MobileSelectAll.vue'
import { useStrmTask } from '@/composables/useStrmTask'
import { useDebounce } from '@/composables/useDebounce'
import { message } from '@/composables/useMessage'

const {
  taskList, loading, total, queryParams, queryRef,
  getList, handleQuery, resetQuery,
  selectedIds,
  open, dialogTitle, submitLoading, formRef, form,
  handleAdd, handleUpdate, submitForm,
  handleDelete, handleExecuteOne,
  toggleSelect, handleCardClick, clearSelection,
  isAllPageSelected, isIndeterminate, toggleSelectAllPage,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed, handleBatchExecute
} = useStrmTask()

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
  if (!form.value.strmTaskPath) {
    message.warning('STRM目录不能为空')
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
  () => [queryParams.strmTaskPath, queryParams.strmTaskStatus],
  () => debouncedSearch()
)
</script>
