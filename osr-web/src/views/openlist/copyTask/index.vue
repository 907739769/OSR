<template>
  <div class="page-container">
    <PageHeader
      icon="folder-sync"
      title="文件同步任务"
      desc="配置源目录到目标目录的同步规则，可选监控本地目录变化自动触发"
    />

    <!-- Search Panel -->
    <SearchPanel ref="queryRef" :visible="showSearch" @search="handleQuery" @reset="resetQuery">
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
        :items="[{ title: '启用', value: '1' }, { title: '停用', value: '0' }]"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="status-select"
      />
    </SearchPanel>

    <!-- Table Card -->
    <v-card class="table-card">
      <!-- Action Bar -->
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="primary" prepend-icon="plus" @click="handleAdd('新增文件同步任务')">
            新增
          </v-btn>
        </div>
        <v-btn variant="text" prepend-icon="funnel" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <!-- 选中后才出现：给出「已选 N 项」这个此前完全缺失的反馈。
           批量按钮从 action-bar 挪到这里 —— 常驻一排灰按钮既占地方，又要靠用户
           猜「为什么点不动」；卡片型列表页（订阅/下载记录）本来就是这个形态。 -->
      <div v-if="selectedRows.length" class="batch-toolbar">
        已选 {{ selectedRows.length }} 项
        <v-btn variant="text" size="small" color="success" :disabled="notOneSelected" @click="handleUpdate(undefined, '修改文件同步任务')">
          修改
        </v-btn>
        <v-btn variant="text" size="small" color="error" :disabled="noneSelected" @click="handleDelete(undefined, `是否确认删除文件同步任务编号为“${selectedIds}”的数据项？`)">
          批量删除
        </v-btn>
        <v-btn variant="text" size="small" color="warning" :disabled="noneSelected" @click="handleExecute('是否确认执行选中的文件同步任务？')">
          批量执行
        </v-btn>
        <v-spacer />
        <v-btn variant="text" size="small" class="batch-clear-btn" @click="clearSelection">清空选择</v-btn>
      </div>

      <!-- Desktop Table -->
      <v-data-table-server
        :loading="loading"
        :items="taskList"
        :items-length="total"
        :headers="headers"
        :items-per-page="queryParams.pageSize"
        :page="queryParams.pageNum"
        :sort-by="sortBy"
        show-select
        item-value="copyTaskId"
        return-object
        :model-value="selectedRows"
        class="modern-table modern-table--fixed"
        @update:model-value="onSelectionChange"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
        @update:sort-by="onSortChange"
      >
        <template #item.config="{ item }">
          <div class="path-box">
            <div class="path-row"><span class="path-label path-label--src">源</span> <span class="path-text">{{ item.copyTaskSrc }}</span></div>
            <div class="path-row"><span class="path-label path-label--dst">目</span> <span class="path-text">{{ item.copyTaskDst }}</span></div>
            <div class="path-row" v-if="item.monitorDir"><span class="path-label path-label--mon">监</span> <span class="path-text">{{ item.monitorDir }}</span></div>
          </div>
        </template>
        <template #item.copyTaskStatus="{ item }">
          <StatusChip :value="item.copyTaskStatus" />
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" prepend-icon="square-pen" @click="handleUpdate(item, '修改文件同步任务')">
            修改
          </v-btn>
          <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(item)">
            删除
          </v-btn>
          <v-btn variant="text" color="primary" size="small" prepend-icon="play" @click="handleExecuteOne(item, `是否确认执行文件同步任务“${item.copyTaskSrc} → ${item.copyTaskDst}”？`)">
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
  </div>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import PageHeader from '@/components/PageHeader.vue'
import FormField from '@/components/FormField.vue'
import { useCopyTask } from '@/composables/useCopyTask'
import { message } from '@/composables/useMessage'
import DirectoryTreeSelect from '@/components/DirectoryTreeSelect/index.vue'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'
import { useDataTable } from '@/composables/useDataTable'

const { showSearch } = useSearchPanel()

const {
  taskList, loading, total, queryParams, queryRef,
  getList, handleQuery, resetQuery,
  selectedIds, notOneSelected, noneSelected, handleSelectionChange,
  open, dialogTitle, submitLoading, formRef, form,
  handleAdd, handleUpdate, submitForm,
  handleDelete, handleExecuteOne, handleExecute
} = useCopyTask()

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

const headers = [
  { title: '同步配置', key: 'config', minWidth: '300', sortable: false },
  { title: '状态', key: 'copyTaskStatus', align: 'center' as const, width: '80' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '220', sortable: false }
]

// 表格接线（选中承接 / 翻页 / 换页长 / 表头排序）统一在 useDataTable 里，见该文件注释
const { selectedRows, onSelectionChange, clearSelection, onPageChange, onSizeChange, sortBy, onSortChange } =
  useDataTable({ queryParams, getList, handleSelectionChange })

</script>
