<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-auto-fix"
      title="PT 热门自动订阅"
      desc="按 TMDb 热门榜或评分条件定时自动建订阅"
    />

    <SearchPanel ref="queryRef" :visible="showSearch" @search="handleQuery" @reset="resetQuery">
      <v-text-field
        v-model="queryParams.name"
        label="规则名称"
        placeholder="规则名称"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        @keyup.enter="handleQuery"
      />
      <v-select
        v-model="queryParams.mediaType"
        label="媒体类型"
        :items="[{ title: '电影', value: 'MOVIE' }, { title: '剧集', value: 'TV' }]"
        placeholder="全部类型"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="field-sm"
      />
      <v-select
        v-model="queryParams.enabled"
        label="启用状态"
        :items="[{ title: '启用', value: '1' }, { title: '停用', value: '0' }]"
        placeholder="全部"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="status-select"
      />
    </SearchPanel>

    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="primary" prepend-icon="mdi-plus" @click="handleAdd('新增热门自动订阅规则')">
            新增规则
          </v-btn>
        </div>
        <v-btn variant="text" prepend-icon="mdi-filter-outline" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <v-data-table-server
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
        <template #item.mediaType="{ item }">{{ item.mediaType === 'MOVIE' ? '电影' : '剧集' }}</template>
        <template #item.source="{ item }">{{ sourceLabel(item.source) }}</template>
        <template #item.filter="{ item }">
          <span :class="{ 'text-muted': !filterText(item) }">{{ filterText(item) || '无' }}</span>
        </template>
        <template #item.intervalHours="{ item }">{{ item.intervalHours }}h</template>
        <template #item.lastRunTime="{ item }">{{ item.lastRunTime || '未执行' }}</template>
        <template #item.enabled="{ item }">
          <StatusChip :value="item.enabled" />
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" :loading="runningIds.has(item.id)" @click="handleRun(item)">立即执行</v-btn>
          <v-menu>
            <template #activator="{ props: menuProps }">
              <v-btn v-bind="menuProps" class="more-actions-trigger" variant="text" color="info" size="small" append-icon="mdi-chevron-down">更多</v-btn>
            </template>
            <v-list density="compact">
              <v-list-item prepend-icon="mdi-text-box-outline" @click="handleShowLogs(item)">日志</v-list-item>
              <v-list-item prepend-icon="mdi-pencil-outline" @click="handleUpdate(item, '编辑规则')">编辑</v-list-item>
              <v-divider class="my-1" />
              <v-list-item class="more-actions-danger" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">删除</v-list-item>
            </v-list>
          </v-menu>
        </template>
      </v-data-table-server>
    </v-card>

    <v-dialog v-model="open" max-width="600">
      <v-card :title="dialogTitle">
        <v-card-text>
          <v-form ref="formRef">
            <v-text-field
              v-model="form.name"
              label="规则名称"
              placeholder="如：每周热门电影"
              :rules="nameRules"
              class="mb-3"
            />
            <FormField label="是否启用">
              <v-switch v-model="form.enabled" true-value="1" false-value="0" color="primary" hide-details />
            </FormField>
            <FormField label="媒体类型">
              <v-radio-group v-model="form.mediaType" inline hide-details>
                <v-radio label="电影" value="MOVIE" />
                <v-radio label="剧集" value="TV" />
              </v-radio-group>
            </FormField>
            <v-select
              v-model="form.source"
              label="数据源"
              :items="[
                { title: 'TMDb 每日热门', value: 'TMDB_TRENDING_DAY' },
                { title: 'TMDb 每周热门', value: 'TMDB_TRENDING_WEEK' },
                { title: 'TMDb 条件发现（按评分/地区）', value: 'TMDB_DISCOVER' }
              ]"
              class="mb-3"
            />
            <v-select
              v-model="genreExcludeArr"
              label="排除类型"
              :items="genreOptions"
              item-title="label"
              item-value="id"
              multiple
              chips
              closable-chips
              clearable
              placeholder="不排除任何类型"
              class="mb-3"
            />
            <v-text-field
              v-model.number="form.minVoteAverage"
              type="number"
              label="最低评分"
              :min="0"
              :max="10"
              step="0.5"
              placeholder="不限"
              class="mb-3"
            />
            <v-text-field
              v-model.number="form.minVoteCount"
              type="number"
              label="最低评分人数"
              :min="0"
              placeholder="不限"
              class="mb-3"
            />
            <v-select
              v-if="form.source === 'TMDB_DISCOVER'"
              v-model="form.region"
              label="地区"
              :items="REGION_OPTIONS"
              item-title="label"
              item-value="code"
              clearable
              placeholder="不限地区"
              class="mb-3"
            />
            <v-text-field
              v-model.number="form.maxAddPerRun"
              type="number"
              label="单轮上限"
              :min="1"
              :max="50"
              class="mb-3"
            />
            <v-text-field
              v-model.number="form.intervalHours"
              type="number"
              label="执行间隔"
              :min="1"
              :max="720"
              suffix="小时"
              class="mb-3"
            />
            <v-select
              v-model="form.downloaderId"
              label="指定下载器"
              :items="downloaderOptions"
              item-title="name"
              item-value="id"
              clearable
              placeholder="空则用唯一启用的下载器"
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

    <v-dialog v-model="logDialogVisible" max-width="600">
      <v-card title="执行日志">
        <v-card-text>
          <v-data-table
            :loading="logLoading"
            :items="logList"
            :headers="logHeaders"
            :items-per-page="-1"
            hide-default-footer
            height="480"
          >
            <template #item.season="{ item }">{{ item.season ?? '-' }}</template>
            <template #item.result="{ item }">
              <StatusChip :type="resultTagType(item.result)" :text="resultLabel(item.result)" />
            </template>
          </v-data-table>
          <v-empty-state v-if="!logLoading && logList.length === 0" icon="mdi-inbox-outline" title="暂无日志" />
        </v-card-text>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import PageHeader from '@/components/PageHeader.vue'
import FormField from '@/components/FormField.vue'
import { usePtAutoAddRule, REGION_OPTIONS } from '@/composables/usePtAutoAddRule'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'
import { useDataTable } from '@/composables/useDataTable'

const { showSearch } = useSearchPanel()

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  runningIds, handleRun,
  logDialogVisible, logLoading, logList, handleShowLogs,
  genreOptions, genreExcludeArr, downloaderOptions,
  filterText
} = usePtAutoAddRule()

// 表单规则是 { required, message, trigger } 对象格式（composable 返回），
// Vuetify 的 v-text-field :rules 需要函数格式，这里就地转换，不改动 composable
const toRuleFns = (ruleList: any[]) =>
  (ruleList || []).map((rule: any) => (value: any) => {
    if (rule.required && (value === null || value === undefined || value === '')) {
      return rule.message || '不能为空'
    }
    return true
  })

const nameRules = toRuleFns(rules.name)

const handleSubmitClick = async () => {
  if (!formRef.value) return
  const { valid } = await formRef.value.validate()
  if (!valid) return
  submitForm()
}

// 翻页 / 换页长 / 表头排序的接线统一在 useDataTable 里（这张表没有勾选，不取 selectedRows）
const { onPageChange, onSizeChange, sortBy, onSortChange } = useDataTable({ queryParams, getList })

const headers = [
  { title: '规则名称', key: 'name', minWidth: '140' },
  { title: '媒体类型', key: 'mediaType', width: '90' },
  { title: '数据源', key: 'source', width: '150' },
  { title: '过滤条件', key: 'filter', minWidth: '180', sortable: false },
  { title: '单轮上限', key: 'maxAddPerRun', width: '90' },
  { title: '执行间隔', key: 'intervalHours', width: '100' },
  { title: '上次执行', key: 'lastRunTime', width: '160' },
  { title: '状态', key: 'enabled', width: '80' },
  // 这张表是 auto 布局（多表页没挂 .modern-table--fixed），width 只是建议值——
  // 9 列一挤就被压到 101px，按钮折了四行。auto 布局下要用 minWidth 才拦得住
  { title: '操作', key: 'actions', minWidth: '190', sortable: false }
]

const logHeaders = [
  { title: '时间', key: 'createTime', width: '160' },
  { title: '标题', key: 'title', minWidth: '160' },
  { title: '季', key: 'season', width: '60' },
  { title: '结果', key: 'result', width: '120' },
  { title: '说明', key: 'message', minWidth: '160' }
]

const sourceLabel = (source: string) => {
  const map: Record<string, string> = {
    TMDB_TRENDING_DAY: 'TMDb 每日热门',
    TMDB_TRENDING_WEEK: 'TMDb 每周热门',
    TMDB_DISCOVER: 'TMDb 条件发现'
  }
  return map[source] || source
}

const resultLabel = (result: string) => {
  const map: Record<string, string> = {
    ADDED: '已新增',
    SKIPPED_EXISTS: '已存在跳过',
    SKIPPED_FILTER: '过滤跳过',
    FAILED: '失败'
  }
  return map[result] || result
}

const resultTagType = (result: string): 'success' | 'info' | 'warning' | 'error' => {
  const map: Record<string, 'success' | 'info' | 'warning' | 'error'> = {
    ADDED: 'success',
    SKIPPED_EXISTS: 'info',
    SKIPPED_FILTER: 'warning',
    FAILED: 'error'
  }
  return map[result] || 'info'
}
</script>

<style scoped lang="scss">
.text-muted {
  color: var(--osr-text-secondary);
}

</style>
