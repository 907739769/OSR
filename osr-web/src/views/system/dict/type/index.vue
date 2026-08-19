<template>
  <div class="page-container">
    <!-- Header -->
    <PageHeader icon="mdi-book-open-variant" title="字典管理" desc="维护系统枚举字典（视频格式、字幕格式、媒体类型等），点击「数据」查看字典项" />

    <!-- Table Card -->
    <v-card class="table-card">
      <!-- Desktop Table -->
      <v-data-table-server
        v-if="appStore.device === 'desktop'"
        :loading="loading"
        :items="taskList"
        :items-length="total"
        :headers="headers"
        :items-per-page="queryParams.pageSize"
        :page="queryParams.pageNum"
        class="modern-table"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
      >
        <template #item.status="{ item }">
          <StatusChip :value="item.status" enabled-value="0" on-text="正常" off-text="停用" />
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-format-list-bulleted" @click="handleData(item)">
            数据
          </v-btn>
        </template>
      </v-data-table-server>

      <!-- Mobile Card List -->
      <div v-if="appStore.device === 'mobile'" class="mobile-card-list">
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <v-card v-for="item in taskList" :key="item.dictId" variant="outlined" class="mobile-card">
          <div class="mobile-card-header">
            <span class="mobile-card-title">{{ item.dictName }}</span>
            <StatusChip :value="item.status" enabled-value="0" on-text="正常" off-text="停用" />
          </div>
          <div class="mobile-card-body">
            <div v-if="item.remark" class="mobile-card-row">
              <span class="mobile-card-label">备注</span>
              <span class="mobile-card-value">{{ item.remark }}</span>
            </div>
            <div class="mobile-card-row">
              <span class="mobile-card-label">创建时间</span>
              <span class="mobile-card-value mobile-card-value-light">{{ item.createTime }}</span>
            </div>
          </div>
          <div class="mobile-card-actions">
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-format-list-bulleted" @click="handleData(item)">
              数据
            </v-btn>
          </div>
        </v-card>
        <v-empty-state v-if="!loading && !taskList.length" icon="mdi-inbox-outline" title="暂无数据" />
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
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { getDictTypeListApi, addDictTypeApi, updateDictTypeApi, deleteDictTypeApi } from '@/api/system/dict'
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

const headers = [
  { title: '字典名称', key: 'dictName', minWidth: '140' },
  { title: '状态', key: 'status', align: 'center' as const, width: '90' },
  { title: '备注', key: 'remark', minWidth: '140' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '100', sortable: false }
]

const { taskList, loading, total, queryParams, getList } = useTaskList<SearchParams>({
  listApi: getDictTypeListApi,
  // 本页只读（新增/编辑/删除在 dict/data 侧），但 useTaskList 的 CRUD API 为必填，
  // 传真实 API 保证底座完整，后续若在类型页加操作无需再改结构
  addApi: addDictTypeApi,
  updateApi: updateDictTypeApi,
  deleteApi: deleteDictTypeApi,
  idField: 'dictId',
  initForm: () => ({ dictName: '', dictType: '', status: '0', remark: '' }),
  rules: {},
  defaultQuery: {}
})

// 翻页 / 换页长的接线统一在 useDataTable 里（这张表没有勾选，不取 selectedRows）
const { onPageChange, onSizeChange } = useDataTable({ queryParams, getList })

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

const handleData = (row: any) => {
  // 不要写死 path：system 模块菜单 path 历史上有多种前缀，按 meta.componentKey 反查
  const path = getRoutePathForComponent('system/dict/data/index') || '/system/dict/data'
  router.push({ path, query: { dictType: row.dictType } })
}

getList()
</script>
