<template>
  <div class="page-container">
    <!-- Header -->
    <div class="page-header">
      <div class="page-header-left">
        <div class="page-header-icon">
          <v-icon icon="mdi-book-open-variant" />
        </div>
        <div>
          <h2 class="page-title">字典管理</h2>
          <p class="page-desc">维护系统枚举字典（视频格式、字幕格式、媒体类型等），点击「数据」查看字典项</p>
        </div>
      </div>
    </div>

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

<style scoped lang="scss">
.page-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  gap: 16px;

  .page-header-left {
    display: flex;
    align-items: center;
    gap: 16px;

    .page-header-icon {
      width: 48px;
      height: 48px;
      border-radius: 14px;
      background: rgb(var(--v-theme-primary));
      color: rgb(var(--v-theme-on-primary));
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 24px;
    }

    .page-title {
      margin: 0;
      font-size: 22px;
      font-weight: 700;
      letter-spacing: 0.3px;
    }

    .page-desc {
      margin: 4px 0 0;
      font-size: 13px;
      color: var(--osr-text-secondary);
    }
  }
}

.table-card {
  padding: 16px;
  display: flex;
  flex-direction: column;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: auto;
  padding-top: 12px;
}

@media (max-width: 768px) {
  .page-container {
    gap: 12px;
  }

  .page-header {
    padding: 0 4px;

    .page-header-icon {
      width: 42px;
      height: 42px;
      font-size: 20px;
    }

    .page-title { font-size: 19px; }
    .page-desc { display: none; }
  }

  .table-card {
    padding: 12px;
  }

  .mobile-card-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .mobile-card {
    overflow: hidden;

    .mobile-card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 10px 12px 8px;
      border-bottom: 1px solid var(--osr-border-light);
      background: var(--osr-bg-page);

      .mobile-card-title {
        font-size: 14px;
        font-weight: 600;
        flex: 1;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        margin-right: 8px;
      }
    }

    .mobile-card-body {
      .mobile-card-row {
        display: flex;
        align-items: flex-start;
        padding: 8px 12px;
        font-size: 13px;
        border-bottom: 1px solid var(--osr-border-light);

        &:last-child {
          border-bottom: none;
        }

        .mobile-card-label {
          width: 64px;
          color: var(--osr-text-secondary);
          flex-shrink: 0;
          font-size: 12px;
          line-height: 1.5;
          padding-top: 1px;
        }

        .mobile-card-value {
          flex: 1;
          min-width: 0;
          font-size: 13px;
          line-height: 1.5;
          word-break: break-all;

          &.mobile-card-value-light {
            color: var(--osr-text-secondary);
            font-size: 12px;
          }
        }
      }
    }

    .mobile-card-actions {
      display: flex;
      justify-content: flex-end;
      gap: 2px;
      padding: 8px 12px 10px;
      border-top: 1px solid var(--osr-border-light);
    }
  }
}
</style>
