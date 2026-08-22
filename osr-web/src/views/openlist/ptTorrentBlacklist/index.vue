<template>
  <div class="page-container">
    <PageHeader
      icon="ban"
      title="PT 黑名单"
      desc="被拉黑的种子与发布组，命中后不再推送下载"
    />

    <SearchPanel ref="queryRef" :visible="showSearch" @search="handleQuery" @reset="resetQuery">
      <v-select
        v-model="queryParams.type"
        label="类型"
        :items="[{ title: '种子(GUID)', value: 'GUID' }, { title: '发布组', value: 'RELEASE_GROUP' }]"
        placeholder="全部类型"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="field-md"
      />
      <v-text-field
        v-model="queryParams.displayValue"
        label="展示内容"
        placeholder="标题或发布组名"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        @keyup.enter="handleQuery"
      />
    </SearchPanel>

    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="primary" prepend-icon="plus" @click="handleAdd('新增发布组黑名单')">
            新增发布组规则
          </v-btn>
        </div>
        <v-btn variant="text" prepend-icon="funnel" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <div class="card-grid" ref="gridRef">
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <div v-for="item in taskList" :key="item.id" class="item-card">
          <div class="card-header">
            <span class="card-title" :title="item.displayValue">{{ item.displayValue || '(无展示内容)' }}</span>
            <StatusChip :type="item.type === 'GUID' ? 'error' : 'warning'" :text="item.type === 'GUID' ? '种子' : '发布组'" />
          </div>
          <div class="card-body">
            <div class="card-row">
              <span class="label">匹配键</span>
              <span class="value" :title="item.value">{{ item.type === 'GUID' ? shortHash(item.value) : item.value }}</span>
            </div>
            <div class="card-row">
              <span class="label">原因</span>
              <span class="value">{{ item.reason || '-' }}</span>
            </div>
            <div class="card-row">
              <span class="label">创建时间</span>
              <span class="value">{{ item.createTime || '-' }}</span>
            </div>
          </div>
          <div class="card-footer">
            <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(item)">
              删除
            </v-btn>
          </div>
        </div>
        <v-empty-state v-if="!loading && taskList.length === 0" icon="inbox" title="暂无黑名单规则" />
      </div>

      <div class="pagination-wrapper">
        <span class="total-text">共 {{ total }} 条</span>
        <v-select
          :model-value="queryParams.pageSize"
          :items="pageSizeOptions"
          density="compact"
          variant="outlined"
          hide-details
          class="page-size-select"
          @update:model-value="setPageSize"
        />
        <v-pagination
          v-model="queryParams.pageNum"
          :length="Math.ceil(total / queryParams.pageSize!) || 1"
          density="comfortable"
          @update:model-value="getList"
        />
      </div>
    </v-card>

    <v-dialog v-model="open" max-width="480">
      <v-card :title="dialogTitle">
        <v-card-text>
          <v-form ref="formRef">
            <v-text-field
              v-model="form.value"
              label="发布组名"
              placeholder="如 CHDWEB，大小写不敏感"
              :rules="valueRules"
              class="mb-3"
            />
            <v-textarea
              v-model="form.reason"
              label="原因"
              rows="2"
              placeholder="可选，如“转码质量差”"
            />
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
import PageHeader from '@/components/PageHeader.vue'
import StatusChip from '@/components/StatusChip.vue'
import { usePtTorrentBlacklist } from '@/composables/usePtTorrentBlacklist'
import { useGridPageSize } from '@/composables/useGridPageSize'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'

const { showSearch } = useSearchPanel()

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, submitForm, handleDelete
} = usePtTorrentBlacklist({ autoLoad: false })

// 每页条数按网格实际列数取整到整行，窗口宽度变了跟着重算
const { gridRef, pageSizeOptions, setPageSize } = useGridPageSize((size) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
})

// 表单规则是 { required, message, trigger } 对象格式（composable 返回），
// Vuetify 的 v-text-field :rules 需要函数格式，这里就地转换，不改动 composable
const toRuleFns = (ruleList: any[]) =>
  (ruleList || []).map((rule: any) => (value: any) => {
    if (rule.required && (value === null || value === undefined || value === '')) {
      return rule.message || '不能为空'
    }
    return true
  })

const valueRules = toRuleFns(rules.value)

const handleSubmitClick = async () => {
  if (!formRef.value) return
  const { valid } = await formRef.value.validate()
  if (!valid) return
  submitForm()
}

const shortHash = (value: string) => {
  if (!value) return '-'
  return value.length > 12 ? `${value.slice(0, 6)}...${value.slice(-4)}` : value
}
</script>
