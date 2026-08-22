<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && taskList.length === 0"
    empty-icon="inbox"
    empty-title="暂无同步任务"
  >
    <template #head>
      <!-- 搜索 -->
      <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
        <v-text-field
          v-model="queryParams.copyTaskSrc"
          label="源目录"
          placeholder="请输入源目录"
          clearable
          density="compact"
          variant="outlined"
          hide-details
          @keyup.enter="handleQuery"
        />
        <v-text-field
          v-model="queryParams.copyTaskDst"
          label="目标目录"
          placeholder="请输入目标目录"
          clearable
          density="compact"
          variant="outlined"
          hide-details
          @keyup.enter="handleQuery"
        />
        <v-text-field
          v-model="queryParams.monitorDir"
          label="监控目录"
          placeholder="请输入监控目录"
          clearable
          density="compact"
          variant="outlined"
          hide-details
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
          hide-details
        />
      </MobileSearchPanel>

      <!-- Batch Actions -->
      <MobileBatchBar
        :visible="selectedIds.length > 0"
        :count="selectedIds.length"
        :all-selected="isAllPageSelected"
        @toggle-all="toggleSelectAllPage"
        @cancel="clearSelection"
      >
        <v-btn variant="text" color="primary" size="small" prepend-icon="play" @click="handleBatchExecute">
          批量执行
        </v-btn>
        <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(undefined, `是否确认删除选中的 ${selectedIds.length} 个文件同步任务？`)">
          批量删除
        </v-btn>
      </MobileBatchBar>

      <!-- Add Button (FAB) -->
      <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="plus" @click="handleAdd('新增文件同步任务')">
        新增
      </v-btn>

      <!-- Task List -->
    </template>

    <v-card
      v-for="task in taskList"
      :key="task.copyTaskId"
      class="task-card"
      :class="{ selected: selectedIds.includes(task.copyTaskId) }"
      @click="handleCardClick($event, task.copyTaskId)"
    >
      <div class="card-checkbox">
        <v-checkbox-btn
          :model-value="selectedIds.includes(task.copyTaskId)"
          density="compact"
          @click.stop="toggleSelect(task.copyTaskId)"
        />
      </div>
      <div class="card-content">
        <div class="card-top">
          <div class="card-title-row">
            <v-icon class="card-title-icon" icon="map-pin" size="18" />
            <span class="card-title card-title--link" @click.stop="showFullText(task.copyTaskSrc, '源目录')">{{ task.copyTaskSrc }}</span>
          </div>
          <StatusChip :value="task.copyTaskStatus" />
        </div>
        <div class="card-path card-path--link" @click.stop="showFullText(task.copyTaskDst, '目标目录')">
          <v-icon class="card-path-icon" icon="map-pin" size="14" />
          <span class="card-path-text">{{ task.copyTaskDst }}</span>
        </div>
        <div class="card-path card-path--link card-path--warning" v-if="task.monitorDir" @click.stop="showFullText(task.monitorDir, '监控目录')">
          <v-icon class="card-path-icon" icon="funnel" size="14" />
          <span class="card-path-text">{{ task.monitorDir }}</span>
        </div>
        <div class="card-time">
          <v-icon icon="clock" size="12" />
          {{ task.createTime }}
        </div>
        <div class="card-actions" @click.stop>
          <v-btn variant="text" color="primary" size="small" prepend-icon="play" @click="handleExecuteOne(task, `是否确认执行同步任务“${task.copyTaskSrc}”？`)">
            执行
          </v-btn>
          <v-btn variant="text" color="primary" size="small" prepend-icon="square-pen" @click="handleUpdate(task, '修改文件同步任务')">
            修改
          </v-btn>
          <v-btn class="action-more" variant="text" color="default" size="small" icon="ellipsis" @click="openSheet(task)" />
        </div>
      </div>
    </v-card>

    <template #foot>
      <!-- 操作抽屉 -->
      <MobileActionSheet v-model="sheetOpen" :target="sheetTarget">
        <v-btn color="error" block prepend-icon="trash-2" @click="run(() => handleDelete(sheetTarget))">删除</v-btn>
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

      <!-- Add/Edit Dialog -->
      <v-dialog v-model="open" width="92%">
        <v-card :title="dialogTitle">
          <v-card-text>
            <v-form ref="formRef">
              <FormField label="源目录">
                <DirectoryTreeSelect v-model="form.copyTaskSrc" type="openlist" placeholder="请选择源目录" />
              </FormField>
              <FormField label="目标目录">
                <DirectoryTreeSelect v-model="form.copyTaskDst" type="openlist" placeholder="请选择目标目录" />
              </FormField>
              <FormField label="监控目录">
                <DirectoryTreeSelect v-model="form.monitorDir" type="local" placeholder="请选择监控目录（可选）" />
              </FormField>
              <FormField label="状态">
                <v-radio-group v-model="form.copyTaskStatus" inline hide-details>
                  <v-radio label="启用" value="1" />
                  <v-radio label="停用" value="0" />
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
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import FormField from '@/components/FormField.vue'
import { ref, watch } from 'vue'
import DirectoryTreeSelect from '@/components/DirectoryTreeSelect/index.vue'
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileActionSheet from '@/components/mobile/MobileActionSheet.vue'
import MobileBatchBar from '@/components/mobile/MobileBatchBar.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import FullTextDialog from '@/components/mobile/FullTextDialog.vue'
import { useCopyTask } from '@/composables/useCopyTask'
import { useDebounce } from '@/composables/useDebounce'
import { message } from '@/composables/useMessage'
import { useActionSheet } from '@/composables/useActionSheet'

const {
  taskList, loading, total, queryParams,
  getList, handleQuery, resetQuery,
  selectedIds,
  open, dialogTitle, submitLoading, formRef, form,
  handleAdd, handleUpdate, submitForm,
  handleDelete, handleExecuteOne,
  toggleSelect, handleCardClick, clearSelection,
  isAllPageSelected, toggleSelectAllPage,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed, handleBatchExecute
} = useCopyTask()

const fullTextRef = ref<InstanceType<typeof FullTextDialog>>()
const showFullText = (content: string, title: string) => fullTextRef.value?.show(content, title)

/** 更多操作抽屉 */
/** 卡片「更多」动作面板：开关状态与「执行完自动关闭」都在 useActionSheet 里 */
const { sheetOpen, sheetTarget, openSheet, run } = useActionSheet()

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
