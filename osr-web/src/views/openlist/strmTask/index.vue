<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-movie-cog-outline"
      title="STRM 任务配置"
      desc="配置需要生成 STRM 文件的网盘目录，可手动执行或由定时任务触发"
    />

    <!-- Search Panel -->
    <v-card v-if="showSearch" class="search-card">
      <v-form ref="queryRef" @submit.prevent="handleQuery">
        <div class="search-fields">
          <v-text-field
            v-model="queryParams.strmTaskPath"
            label="STRM目录"
            placeholder="请输入strm目录"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-select
            v-model="queryParams.strmTaskStatus"
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
          <v-btn color="primary" prepend-icon="mdi-plus" @click="handleAdd('新增STRM任务')">
            新增
          </v-btn>
          <v-btn color="success" prepend-icon="mdi-pencil-outline" :disabled="single" @click="handleUpdate(undefined, '修改STRM任务')">
            修改
          </v-btn>
          <v-btn color="error" prepend-icon="mdi-delete-outline" :disabled="multiple" @click="handleDelete(undefined, `是否确认删除STRM任务编号为“${selectedIds}”的数据项？`)">
            批量删除
          </v-btn>
          <v-btn color="warning" prepend-icon="mdi-play-circle-outline" :disabled="multiple" @click="handleExecute('是否确认执行选中的STRM任务？')">
            批量执行
          </v-btn>
        </div>
        <v-btn variant="text" prepend-icon="mdi-filter-outline" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <!-- Desktop Table -->
      <v-data-table-server
        :loading="loading"
        :items="taskList"
        :items-length="total"
        :headers="headers"
        :items-per-page="queryParams.pageSize"
        :page="queryParams.pageNum"
        show-select
        item-value="strmTaskId"
        return-object
        :model-value="selectedRows"
        class="modern-table"
        @update:model-value="onSelectionChange"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
      >
        <template #item.strmTaskPath="{ item }">
          <div class="path-text" :title="item.strmTaskPath">
            <v-icon icon="mdi-folder-open-outline" size="16" />
            {{ item.strmTaskPath }}
          </div>
        </template>
        <template #item.strmTaskStatus="{ item }">
          <StatusChip :value="item.strmTaskStatus" />
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item, '修改STRM任务')">
            修改
          </v-btn>
          <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">
            删除
          </v-btn>
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-play-circle-outline" @click="handleExecuteOne(item, `是否确认执行STRM任务“${item.strmTaskPath}”？`)">
            执行
          </v-btn>
        </template>
      </v-data-table-server>
    </v-card>

    <!-- Add/Edit Dialog -->
    <v-dialog v-model="open" max-width="600">
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
import PageHeader from '@/components/PageHeader.vue'
import FormField from '@/components/FormField.vue'
import { ref, watch } from 'vue'
import { useStrmTask } from '@/composables/useStrmTask'
import { useDebounce } from '@/composables/useDebounce'
import { message } from '@/composables/useMessage'
import DirectoryTreeSelect from '@/components/DirectoryTreeSelect/index.vue'

const showSearch = ref(window.innerWidth >= 768)

const {
  taskList, loading, total, queryParams, queryRef,
  getList, handleQuery, resetQuery,
  selectedIds, single, multiple, handleSelectionChange,
  open, dialogTitle, submitLoading, formRef, form,
  handleAdd, handleUpdate, submitForm,
  handleDelete, handleExecuteOne, handleExecute
} = useStrmTask()

// DirectoryTreeSelect 不支持 v-form 的 :rules 校验，改为提交前手动校验必填项
const handleSubmitClick = () => {
  if (!form.value.strmTaskPath) {
    message.warning('STRM目录不能为空')
    return
  }
  submitForm()
}

const headers = [
  { title: 'STRM 目录路径', key: 'strmTaskPath', minWidth: '300' },
  { title: '状态', key: 'strmTaskStatus', align: 'center' as const, width: '80' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '220', sortable: false }
]

// v-data-table-server 的多选需要一个本地 ref 承接当前选中的行对象，
// 再转给 useTaskList 的 handleSelectionChange 去派生 selectedIds/single/multiple
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
