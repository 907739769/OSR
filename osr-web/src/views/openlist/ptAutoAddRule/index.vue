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
              :items="SOURCE_OPTIONS"
              item-title="title"
              item-value="value"
              class="mb-3"
            />
            <template v-if="isRssSource(form.source)">
              <v-select
                :model-value="null"
                label="常用榜单"
                :items="DOUBAN_ROUTE_PRESETS"
                item-title="label"
                item-value="path"
                placeholder="选一个填入下方地址"
                persistent-hint
                hint="预设按 RSSHub 官方路由填写，你的实例版本不同的话直接改下方地址即可"
                class="mb-3"
                @update:model-value="applyRoutePreset"
              />
              <v-text-field
                v-model="form.sourceUrl"
                label="RSS 地址"
                placeholder="/douban/movie/weekly/movie_real_time_hotest"
                :rules="sourceUrlRules"
                persistent-hint
                hint="填路由路径则与「参数设置 → RSSHub 服务地址」拼接；填完整 http(s) 地址则直接使用"
                class="mb-3"
              />
              <v-alert type="info" variant="tonal" density="compact" class="mb-3">
                豆瓣条目要按标题搜 TMDb 才能建订阅，搜不到同名作品的会跳过并记进执行日志。
                榜单里电影剧集常混在一起，只有与上方「媒体类型」一致的才会被订阅。
              </v-alert>
            </template>
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
              persistent-hint
              :hint="isRssSource(form.source) ? '按 TMDb 评分过滤（不是豆瓣评分），与其它数据源口径一致' : undefined"
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
import FormField from '@/components/FormField.vue'
import {
  usePtAutoAddRule,
  REGION_OPTIONS,
  SOURCE_OPTIONS,
  DOUBAN_ROUTE_PRESETS,
  isRssSource
} from '@/composables/usePtAutoAddRule'
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
  filterText, sourceLabel, resultLabel, resultTagType
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

// RSS 地址只在豆瓣源下必填：选了这个源却不填地址的话，规则能保存、执行时静静地一条都拉不到，
// 而用户要翻到执行日志才看得见那句 warn
const sourceUrlRules = [
  (value: string) => (!isRssSource(form.value.source) || !!value) || 'RSS 地址不能为空'
]

/** 预设下拉只负责把路径填进地址框，本身不参与提交（所以 model-value 恒为 null） */
const applyRoutePreset = (path: string | null) => {
  if (path) form.value.sourceUrl = path
}

const handleSubmitClick = async () => {
  if (!formRef.value) return
  const { valid } = await formRef.value.validate()
  if (!valid) return
  submitForm()
}

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
