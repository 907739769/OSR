<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-rename-outline"
      title="重命名任务配置"
      desc="配置需要按 TMDb 刮削结果重命名的目录，支持同时生成 NFO 与图片"
    />

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
        item-value="id"
        return-object
        :model-value="selectedRows"
        class="modern-table modern-table--fixed"
        @update:model-value="onSelectionChange"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
      >
        <template #item.config="{ item }">
          <div class="path-box">
            <div class="path-row"><span class="path-label path-label--src">源</span> <span class="path-text">{{ item.sourceFolder }}</span></div>
            <div class="path-row"><span class="path-label path-label--dst">目</span> <span class="path-text">{{ item.targetRoot }}</span></div>
          </div>
        </template>
        <template #item.status="{ item }">
          <StatusChip :value="item.status" />
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
        </template>
      </v-data-table-server>
    </v-card>

    <!-- Add/Edit Dialog -->
    <v-dialog v-model="open" max-width="600">
      <v-card :title="dialogTitle">
        <v-card-text>
          <v-form ref="formRef">
            <FormField label="源目录">
              <DirectoryTreeSelect v-model="form.sourceFolder" type="local" placeholder="请选择源目录" />
            </FormField>
            <FormField label="目标目录">
              <DirectoryTreeSelect v-model="form.targetRoot" type="local" placeholder="请选择目标目录" />
            </FormField>
            <FormField label="状态">
              <v-radio-group v-model="form.status" inline hide-details>
                <v-radio label="停用" value="0" />
                <v-radio label="启用" value="1" />
              </v-radio-group>
            </FormField>
            <div class="section-label">刮削配置</div>
            <v-divider class="mb-3" />
            <FormField label="启用刮削" inline>
              <v-switch v-model="form.scrapeEnabled" true-value="1" false-value="0" color="primary" hide-details density="compact" />
            </FormField>
            <FormField v-if="form.scrapeEnabled === '1'" label="生成NFO" inline>
              <v-switch v-model="form.scrapeNfo" true-value="1" false-value="0" color="primary" hide-details density="compact" />
            </FormField>
            <FormField v-if="form.scrapeEnabled === '1'" label="下载图片" inline>
              <v-switch v-model="form.scrapeImages" true-value="1" false-value="0" color="primary" hide-details density="compact" />
            </FormField>
            <FormField v-if="form.scrapeEnabled === '1'" label="强制覆盖" inline>
              <v-switch v-model="form.scrapeForceOverwrite" true-value="1" false-value="0" color="primary" hide-details density="compact" />
            </FormField>
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="open = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="submitLoading" @click="submitForm">确定</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

  </div>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import PageHeader from '@/components/PageHeader.vue'
import FormField from '@/components/FormField.vue'
import { ref } from 'vue'
import DirectoryTreeSelect from '@/components/DirectoryTreeSelect/index.vue'
import { useRenameTask } from '@/composables/useRenameTask'

const showSearch = ref(window.innerWidth >= 768)

const {
  taskList, loading, total, queryParams,
  getList, queryRef, handleQuery, resetQuery,
  single, multiple, handleSelectionChange,
  open, dialogTitle, submitLoading, formRef, form,
  handleAdd, handleUpdate, submitForm, handleDelete,
  handleExecuteOne, handleBatchExecute: handleExecute
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
/* 公共布局（.page-container/.search-card/.table-card 等）由全局 styles/list.scss 统一提供 */

.section-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--osr-text-primary);
  margin-bottom: 8px;
}
</style>
