<template>
  <div class="page-container">
    <!-- Header -->
    <div class="page-header">
      <div class="page-header-left">
        <div class="page-header-icon">
          <v-icon icon="mdi-format-list-bulleted" />
        </div>
        <div>
          <h2 class="page-title">字典数据</h2>
          <p class="page-desc">
            字典类型：<code class="dict-type-code">{{ currentDictType || '—' }}</code>
          </p>
        </div>
      </div>
    </div>

    <!-- Table Card -->
    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <v-btn variant="outlined" prepend-icon="mdi-arrow-left" @click="handleBack">
            返回
          </v-btn>
          <v-btn color="primary" prepend-icon="mdi-plus" @click="handleAdd">
            新增
          </v-btn>
        </div>
      </div>

      <!-- Desktop Table -->
      <v-data-table-server
        v-if="appStore.device === 'desktop'"
        :loading="loading"
        :items="dataList"
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
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item)">
            编辑
          </v-btn>
          <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">
            删除
          </v-btn>
        </template>
      </v-data-table-server>

      <!-- Mobile Card List -->
      <div v-if="appStore.device === 'mobile'" class="mobile-card-list">
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <v-card v-for="item in dataList" :key="item.dictCode" variant="outlined" class="mobile-card">
          <div class="mobile-card-header">
            <span class="mobile-card-title">{{ item.dictLabel }}</span>
            <v-chip size="small" :color="item.status === '0' ? 'success' : 'error'" variant="tonal">
              {{ item.status === '0' ? '正常' : '停用' }}
            </v-chip>
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
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-pencil-outline" @click="handleUpdate(item)">
              编辑
            </v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">
              删除
            </v-btn>
          </div>
        </v-card>
        <v-empty-state v-if="!loading && !dataList.length" icon="mdi-inbox-outline" title="暂无数据" />
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

    <!-- Dialog -->
    <v-dialog v-model="open" :width="appStore.device === 'mobile' ? '90%' : '520px'" class="modern-dialog">
      <v-card :title="title">
        <v-card-text>
          <v-form ref="dataRef">
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
import { ref, reactive, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { getDictDataListApi, addDictDataApi, deleteDictDataApi, updateDictDataApi } from '@/api/system/dict'
import { useAppStore } from '@/stores/app'
import type { SearchParams } from '@/types'
import type { SysDictData } from '@/types/system'

const appStore = useAppStore()

const router = useRouter()
const route = useRoute()

const currentDictType = computed(() => (route.query.dictType as string) || '')

const handleBack = () => {
  router.push('/system/dict/type')
}

const dataList = ref<any[]>([])
const loading = ref(true)
const total = ref(0)
const title = ref('')
const open = ref(false)

const headers = [
  { title: '字典标签', key: 'dictLabel', minWidth: '120' },
  { title: '字典键值', key: 'dictValue', align: 'center' as const, width: '120' },
  { title: '状态', key: 'status', align: 'center' as const, width: '90' },
  { title: '备注', key: 'remark', minWidth: '120' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '150', sortable: false }
]

const queryParams = reactive<SearchParams>({
  pageNum: 1,
  pageSize: 10,
  dictType: undefined
})

const dataRef = ref<any>()

const form = reactive<Partial<SysDictData>>({ dictCode: undefined, dictLabel: undefined, dictValue: undefined, dictType: undefined, dictSort: undefined, listClass: 'default', cssClass: '', isDefault: 'N', status: '0' })

const getList = async () => {
  loading.value = true
  try {
    const dictType = route.query.dictType as string
    if (dictType) {
      queryParams.dictType = dictType
    } else {
      queryParams.dictType = undefined
    }
    const res = await getDictDataListApi(queryParams) as any
    if (res && typeof res === 'object') {
      dataList.value = res.records || res.list || []
      total.value = res.total || res.totalCount || 0
    } else {
      dataList.value = []
      total.value = 0
    }
  } catch (e: any) {
    console.error('[dict/data] error:', e)
    dataList.value = []
    total.value = 0
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

const handleAdd = () => {
  reset()
  open.value = true
  title.value = '新增字典数据'
  const dictType = route.query.dictType as string
  if (dictType) {
    form.dictType = dictType
  }
}

const handleUpdate = (row?: any) => {
  open.value = true
  title.value = row ? '修改字典数据' : '新增字典数据'
  if (row) {
    form.dictCode = row.dictCode
    form.dictLabel = row.dictLabel
    form.dictValue = row.dictValue
    form.dictType = row.dictType
    form.dictSort = row.dictSort
    form.listClass = row.listClass || 'default'
    form.cssClass = row.cssClass || ''
    form.isDefault = row.isDefault || 'N'
    form.status = row.status
  }
}

const handleDelete = async (row: any) => {
  try {
    await confirm({ message: `是否确认删除字典编码为"${row.dictCode}"的数据项？`, title: '警告', type: 'warning' })
    await deleteDictDataApi(row.dictCode)
    message.success('删除成功')
    getList()
  } catch (e) {
    if (e !== 'cancel') console.error(e)
  }
}

const reset = () => {
  form.dictCode = undefined
  form.dictLabel = undefined
  form.dictValue = undefined
  form.dictType = undefined
  form.dictSort = undefined
  form.listClass = 'default'
  form.cssClass = ''
  form.isDefault = 'N'
  form.status = '0'
  dataRef.value?.resetValidation()
}

const cancel = () => {
  open.value = false
  reset()
}

const submitForm = async () => {
  const formEl = dataRef.value
  if (!formEl) return
  const { valid } = await formEl.validate()
  if (!valid) return
  try {
    if (form.dictCode) {
      await updateDictDataApi(form as SysDictData)
    } else {
      await addDictDataApi(form as SysDictData)
    }
    message.success('操作成功')
    open.value = false
    getList()
  } catch (error) {
    console.error(error)
  }
}

getList()
</script>

<style scoped lang="scss">
.page-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ============================================
   Page Header
   ============================================ */
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
      background: linear-gradient(135deg, #0d9488, #14b8a6);
      color: white;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 24px;
      box-shadow: 0 4px 14px rgba(13, 148, 136, 0.35);
    }

    .page-title {
      margin: 0;
      font-size: 22px;
      font-weight: 700;
      color: var(--osr-text-primary);
      letter-spacing: 0.3px;
    }

    .page-desc {
      margin: 4px 0 0;
      font-size: 13px;
      color: var(--osr-text-secondary);

      .dict-type-code {
        font-family: 'SF Mono', 'Courier New', monospace;
        font-size: 12px;
        color: var(--osr-primary);
        background: var(--osr-primary-light-9);
        padding: 1px 8px;
        border-radius: 5px;
      }
    }
  }
}

/* ============================================
   Action Bar
   ============================================ */
.action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;

  .action-left {
    display: flex;
    gap: 8px;
  }
}

/* ============================================
   Table Card
   ============================================ */
.table-card {
  padding: 16px;
  display: flex;
  flex-direction: column;
}

/* ============================================
   Pagination
   ============================================ */
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: auto;
  padding-top: 12px;
}

/* ============================================
   Mobile Responsive
   ============================================ */
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
    .page-desc { font-size: 12px; }
  }

  .table-card {
    padding: 12px;
  }

  /* ============================================
     Mobile Card List
     ============================================ */
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
        color: var(--osr-text-primary);
        flex: 1;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        margin-right: 8px;
      }
    }

    .mobile-card-body {
      padding: 0;

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
          color: var(--osr-text-primary);
          font-size: 13px;
          line-height: 1.5;
          word-break: break-all;

          &.mobile-card-value-clip {
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
          }

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
