<template>
  <div class="page-container">
    <!-- Header -->
    <PageHeader icon="list" title="字典数据">
      <template #desc>字典类型：<code class="dict-type-code">{{ currentDictType || '—' }}</code></template>
    </PageHeader>

    <!-- Table Card -->
    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <v-btn variant="outlined" prepend-icon="arrow-left" @click="handleBack">
            返回
          </v-btn>
          <v-btn color="primary" prepend-icon="plus" @click="handleAdd">
            新增
          </v-btn>
        </div>
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
        :sort-by="sortBy"
        class="modern-table"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
        @update:sort-by="onSortChange"
      >
        <template #item.status="{ item }">
          <StatusChip :value="item.status" enabled-value="0" on-text="正常" off-text="停用" />
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" prepend-icon="square-pen" @click="handleUpdate(item)">
            编辑
          </v-btn>
          <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(item, `是否确认删除字典编码为“${item.dictCode}”的数据项？`)">
            删除
          </v-btn>
        </template>
      </v-data-table-server>

      <!-- Mobile Card List -->
      <div v-if="appStore.device === 'mobile'" class="mobile-card-list">
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <v-card v-for="item in taskList" :key="item.dictCode" variant="outlined" class="mobile-card">
          <div class="mobile-card-header">
            <span class="mobile-card-title">{{ item.dictLabel }}</span>
            <StatusChip :value="item.status" enabled-value="0" on-text="正常" off-text="停用" />
          </div>
          <div class="mobile-card-body">
            <div class="mobile-card-row">
              <span class="mobile-card-label">字典键值</span>
              <span class="mobile-card-value">{{ item.dictValue }}</span>
            </div>
            <div class="mobile-card-row">
              <span class="mobile-card-label">创建时间</span>
              <span class="mobile-card-value mobile-card-value-light">{{ item.createTime }}</span>
            </div>
          </div>
          <div class="mobile-card-actions">
            <v-btn variant="text" color="primary" size="small" prepend-icon="square-pen" @click="handleUpdate(item)">
              编辑
            </v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(item, `是否确认删除字典编码为“${item.dictCode}”的数据项？`)">
              删除
            </v-btn>
          </div>
        </v-card>
        <v-empty-state v-if="!loading && !taskList.length" icon="inbox" title="暂无数据" />
      </div>

      <!-- Pagination (mobile; desktop paginates via v-data-table-server) -->
      <MobilePager
        v-if="appStore.device === 'mobile'"
        v-model:page-size="queryParams.pageSize"
        :page-num="queryParams.pageNum"
        :total="total"
        :total-pages="totalPages"
        @prev="prevPage"
        @next="nextPage"
        @size-change="handleSizeChange"
      />
    </v-card>

    <!-- Dialog -->
    <v-dialog
      v-model="open"
      :width="appStore.device === 'mobile' ? '92%' : undefined"
      :max-width="appStore.device === 'mobile' ? undefined : '600'"
    >
      <v-card :title="dialogTitle">
        <v-card-text>
          <v-form ref="formRef">
            <v-text-field
              v-model="form.dictType"
              label="字典类型"
              disabled
              placeholder="未选择字典类型"
              variant="outlined"
              density="comfortable"
              class="mb-2"
            />
            <v-text-field
              v-model="form.dictLabel"
              label="字典标签"
              placeholder="请输入字典标签"
              variant="outlined"
              density="comfortable"
              class="mb-2"
              :rules="[(v: any) => !!v || '字典标签不能为空']"
            />
            <v-text-field
              v-model="form.dictValue"
              label="字典键值"
              placeholder="请输入字典键值"
              variant="outlined"
              density="comfortable"
              class="mb-2"
              :rules="[(v: any) => !!v || '字典键值不能为空']"
            />
            <v-radio-group v-model="form.status" label="状态" inline hide-details>
              <v-radio label="正常" value="0" />
              <v-radio label="停用" value="1" />
            </v-radio-group>
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="cancel">取消</v-btn>
          <v-btn color="primary" variant="flat" @click="submitForm">确定</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getDictDataListApi, addDictDataApi, deleteDictDataApi, updateDictDataApi } from '@/api/system/dict'
import { useAppStore } from '@/stores/app'
import PageHeader from '@/components/PageHeader.vue'
import { getRoutePathForComponent } from '@/router'
import { useTaskList } from '@/composables/useTaskList'
import StatusChip from '@/components/StatusChip.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import type { SearchParams } from '@/types'
import { useDataTable } from '@/composables/useDataTable'

const appStore = useAppStore()

const router = useRouter()
const route = useRoute()

const currentDictType = computed(() => (route.query.dictType as string) || '')

const handleBack = () => {
  // 不要写死 path：system 模块菜单 path 历史上有多种前缀，按 meta.componentKey 反查
  const path = getRoutePathForComponent('system/dict/type/index') || '/system/dict/type'
  router.push(path)
}

const headers = [
  { title: '字典标签', key: 'dictLabel', minWidth: '120' },
  { title: '字典键值', key: 'dictValue', align: 'center' as const, width: '120' },
  { title: '状态', key: 'status', align: 'center' as const, width: '90' },
  { title: '备注', key: 'remark', minWidth: '120' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '150', sortable: false }
]

const {
  taskList, loading, total, queryParams,
  open, dialogTitle, formRef, form,
  getList: baseGetList,
  handleAdd: baseHandleAdd, handleUpdate, handleDelete, submitForm
} = useTaskList<SearchParams>({
  listApi: getDictDataListApi,
  addApi: addDictDataApi,
  updateApi: updateDictDataApi,
  deleteApi: deleteDictDataApi,
  idField: 'dictCode',
  initForm: () => ({
    dictCode: undefined,
    dictLabel: undefined,
    dictValue: undefined,
    dictType: undefined,
    dictSort: undefined,
    listClass: 'default',
    cssClass: '',
    isDefault: 'N',
    status: '0'
  }),
  rules: {},
  defaultQuery: {}
})

/** 每次拉取前同步路由的 dictType（从字典类型页带 query 跳转而来，或直接访问带 query 的 URL） */
const getList = async () => {
  queryParams.dictType = (route.query.dictType as string) || undefined
  await baseGetList()
}

// 翻页 / 换页长 / 表头排序的接线统一在 useDataTable 里（这张表没有勾选，不取 selectedRows）
const { onPageChange, onSizeChange, sortBy, onSortChange } = useDataTable({ queryParams, getList })

// ---------- 移动端 - 分页辅助 ----------
const totalPages = computed(() => Math.ceil(total.value / queryParams.pageSize) || 1)

const prevPage = () => {
  if (queryParams.pageNum > 1) {
    queryParams.pageNum--
    getList()
  }
}

const nextPage = () => {
  if (queryParams.pageNum < totalPages.value) {
    queryParams.pageNum++
    getList()
  }
}

const handleSizeChange = () => {
  queryParams.pageNum = 1
  getList()
}

/** 新增时把路由带过来的 dictType 注入表单（useTaskList.handleAdd 会用 initForm 重置） */
const handleAdd = () => {
  baseHandleAdd('新增字典数据')
  const dictType = route.query.dictType as string
  if (dictType) form.value.dictType = dictType
}

const cancel = () => {
  open.value = false
}

getList()
</script>

<style scoped lang="scss">
/* ============================================
   Page Header
   ============================================ */
.dict-type-code {
  font-family: var(--osr-font-mono);
  font-size: 12px;
  color: var(--osr-primary);
  background: var(--osr-primary-subtle);
  padding: 1px 8px;
  border-radius: 5px;
}
</style>
