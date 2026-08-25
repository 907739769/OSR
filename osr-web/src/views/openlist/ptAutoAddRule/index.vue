<template>
  <div class="page-container">
    <PageHeader
      icon="wand-sparkles"
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
          <v-btn color="primary" prepend-icon="plus" @click="handleAdd('新增热门自动订阅规则')">
            新增规则
          </v-btn>
        </div>
        <v-btn variant="text" prepend-icon="funnel" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <v-data-table-server
        :loading="loading"
        :items="taskList"
        :items-length="total"
        :headers="headers"
        :items-per-page="queryParams.pageSize"
        :items-per-page-options="itemsPerPageOptions"
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
              <v-btn v-bind="menuProps" class="more-actions-trigger" variant="text" color="info" size="small" append-icon="chevron-down">更多</v-btn>
            </template>
            <v-list density="compact">
              <v-list-item prepend-icon="file-text" @click="handleShowLogs(item)">日志</v-list-item>
              <v-list-item prepend-icon="square-pen" @click="handleUpdate(item, '编辑规则')">编辑</v-list-item>
              <v-divider class="my-1" />
              <v-list-item class="more-actions-danger" prepend-icon="trash-2" @click="handleDelete(item)">删除</v-list-item>
            </v-list>
          </v-menu>
        </template>
      </v-data-table-server>
    </v-card>

    <PtAutoAddRuleFormDialog />

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
            <template #item.title="{ item }">
              <a
                v-if="item.sourceItemUrl"
                :href="item.sourceItemUrl"
                target="_blank"
                rel="noopener noreferrer"
                class="source-link"
              >{{ item.title }}</a>
              <span v-else>{{ item.title }}</span>
            </template>
          </v-data-table>
          <v-empty-state v-if="!logLoading && logList.length === 0" icon="inbox" title="暂无日志" />
        </v-card-text>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import PageHeader from '@/components/PageHeader.vue'
import { usePtAutoAddRule } from '@/composables/usePtAutoAddRule'
import { usePageStateProvider } from '@/composables/pageStateContext'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'
import { useDataTable } from '@/composables/useDataTable'
import PtAutoAddRuleFormDialog from '@/components/dialogs/PtAutoAddRuleFormDialog.vue'

const { showSearch } = useSearchPanel()

// 表单弹窗与移动端共用一份（components/dialogs/），它靠 usePageStateProvider 取同一份状态
const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  handleAdd, handleUpdate, handleDelete,
  runningIds, handleRun,
  logDialogVisible, logLoading, logList, handleShowLogs,
  filterText, sourceLabel, resultLabel, resultTagType
} = usePageStateProvider(usePtAutoAddRule())

// 翻页 / 换页长 / 表头排序的接线统一在 useDataTable 里（这张表没有勾选，不取 selectedRows）
const { onPageChange, onSizeChange, sortBy, onSortChange, itemsPerPageOptions } = useDataTable({ queryParams, getList })

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

</script>

<style scoped lang="scss">
.text-muted {
  color: var(--osr-text-secondary);
}

/* 豆瓣源的日志条目挂原条目链接：匹配对不对，点开看一眼最快 */
.source-link {
  color: var(--osr-primary);
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }
}

</style>
