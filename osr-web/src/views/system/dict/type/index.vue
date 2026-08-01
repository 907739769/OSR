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
        :items="typeList"
        :items-length="total"
        :headers="headers"
        :items-per-page="queryParams.pageSize"
        :page="queryParams.pageNum"
        class="modern-table"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
      >
        <template #item.status="{ item }">
          <v-chip size="small" :color="item.status === '0' ? 'success' : 'error'" variant="tonal">
            {{ item.status === '0' ? '正常' : '停用' }}
          </v-chip>
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
        <v-card v-for="item in typeList" :key="item.dictId" variant="outlined" class="mobile-card">
          <div class="mobile-card-header">
            <span class="mobile-card-title">{{ item.dictName }}</span>
            <v-chip size="small" :color="item.status === '0' ? 'success' : 'error'" variant="tonal">
              {{ item.status === '0' ? '正常' : '停用' }}
            </v-chip>
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
        <v-empty-state v-if="!loading && !typeList.length" icon="mdi-inbox-outline" title="暂无数据" />
      </div>

      <!-- Pagination (mobile; desktop paginates via v-data-table-server) -->
      <div v-if="appStore.device === 'mobile'" class="pagination-wrapper">
        <v-pagination
          v-model="queryParams.pageNum"
          :length="Math.ceil(total / queryParams.pageSize) || 1"
          density="comfortable"
          @update:model-value="getList"
        />
      </div>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { getDictTypeListApi } from '@/api/system/dict'
import { useAppStore } from '@/stores/app'
import PageHeader from '@/components/PageHeader.vue'
import type { SearchParams, PageResult } from '@/types'

const appStore = useAppStore()
const router = useRouter()

const typeList = ref<any[]>([])
const loading = ref(true)
const total = ref(0)

const headers = [
  { title: '字典名称', key: 'dictName', minWidth: '140' },
  { title: '状态', key: 'status', align: 'center' as const, width: '90' },
  { title: '备注', key: 'remark', minWidth: '140' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '100', sortable: false }
]

const queryParams = reactive<SearchParams>({
  pageNum: 1,
  pageSize: 10
})

const getList = async () => {
  loading.value = true
  try {
    const res = await getDictTypeListApi(queryParams) as PageResult
    typeList.value = res.records
    total.value = res.total
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
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

const handleData = (row: any) => {
  router.push({ path: '/system/dict/data', query: { dictType: row.dictType } })
}

getList()
</script>
