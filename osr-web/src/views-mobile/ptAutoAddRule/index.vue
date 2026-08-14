<template>
  <div class="mobile-page">
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <v-form ref="queryRef">
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
        />
      </v-form>
    </MobileSearchPanel>

    <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="mdi-plus" @click="handleAdd('新增热门自动订阅规则')">
      新增
    </v-btn>

    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <v-card v-for="item in taskList" :key="item.id" class="task-card">
        <div class="card-content">
          <div class="card-top">
            <span class="card-title" :title="item.name">{{ item.name }}</span>
            <StatusChip :value="item.enabled" />
          </div>
          <div class="card-detail">
            <div class="detail-row">
              <span class="label">类型</span>
              <span class="value">{{ item.mediaType === 'MOVIE' ? '电影' : '剧集' }} · {{ sourceLabel(item.source) }}</span>
            </div>
            <div class="detail-row">
              <span class="label">单轮/间隔</span>
              <span class="value">{{ item.maxAddPerRun }}部 / {{ item.intervalHours }}h</span>
            </div>
            <div class="detail-row">
              <span class="label">上次执行</span>
              <span class="value">{{ item.lastRunTime || '未执行' }}</span>
            </div>
            <div class="detail-row">
              <span class="label">过滤条件</span>
              <span class="value" :class="{ 'text-muted': !filterText(item) }">{{ filterText(item) || '无' }}</span>
            </div>
          </div>
          <div class="card-actions">
            <v-btn variant="text" color="primary" size="small" :loading="runningIds.has(item.id)" @click="handleRun(item)">执行</v-btn>
            <v-btn variant="text" color="primary" size="small" @click="handleUpdate(item, '编辑规则')">编辑</v-btn>
            <v-btn class="action-more" variant="text" color="default" size="small" icon="mdi-dots-horizontal" @click="openActionDrawer(item)" />
          </div>
        </div>
      </v-card>

      <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无规则" />
    </div>

    <!-- 操作抽屉 -->
    <v-bottom-sheet v-model="actionDrawerOpen">
      <v-card v-if="actionDrawerTarget" title="更多操作">
        <v-card-text>
          <div class="drawer-actions">
            <v-btn block prepend-icon="mdi-text-box-outline" @click="handleShowLogs(actionDrawerTarget); actionDrawerOpen = false">日志</v-btn>
            <v-btn color="error" block prepend-icon="mdi-delete-outline" @click="handleDelete(actionDrawerTarget); actionDrawerOpen = false">删除</v-btn>
          </div>
        </v-card-text>
      </v-card>
    </v-bottom-sheet>

    <MobilePager
      v-model:page-size="queryParams.pageSize"
      :page-num="queryParams.pageNum"
      :total="total"
      :total-pages="totalPages"
      @prev="prevPage"
      @next="nextPage"
      @size-change="handleSizeChange"
    />

    <v-dialog v-model="open" width="92%">
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
                { title: 'TMDb 条件发现', value: 'TMDB_DISCOVER' }
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
              class="mb-3"
            />
            <v-text-field
              v-model.number="form.minVoteCount"
              type="number"
              label="最低人数"
              :min="0"
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
              class="mb-3"
            />
            <v-select
              v-model="form.downloaderId"
              label="下载器"
              :items="downloaderOptions"
              item-title="name"
              item-value="id"
              clearable
              placeholder="空则用默认"
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

    <v-dialog v-model="logDialogVisible" width="92%">
      <v-card title="执行日志">
        <v-card-text>
          <div class="log-list">
            <v-progress-linear v-if="logLoading" indeterminate color="primary" />
            <div v-for="log in logList" :key="log.id" class="log-item">
              <div class="log-top">
                <span class="log-title" :title="log.title">{{ log.title || '-' }}</span>
                <StatusChip :type="resultTagType(log.result)" :text="resultLabel(log.result)" />
              </div>
              <div class="log-meta">{{ log.createTime }}<span v-if="log.season"> · 第{{ log.season }}季</span></div>
              <div class="log-message" v-if="log.message">{{ log.message }}</div>
            </div>
            <v-empty-state v-if="!logLoading && logList.length === 0" icon="mdi-inbox-outline" title="暂无日志" />
          </div>
        </v-card-text>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import FormField from '@/components/FormField.vue'
import { ref } from 'vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtAutoAddRule, REGION_OPTIONS } from '@/composables/usePtAutoAddRule'

/** 更多操作抽屉 */
const actionDrawerOpen = ref(false)
const actionDrawerTarget = ref<any>(null)
const openActionDrawer = (row: any) => {
  actionDrawerTarget.value = row
  actionDrawerOpen.value = true
}

const {
  taskList, loading, total, queryParams, queryRef,
  handleQuery, resetQuery,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed,
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

const sourceLabel = (source: string) => {
  const map: Record<string, string> = {
    TMDB_TRENDING_DAY: '每日热门',
    TMDB_TRENDING_WEEK: '每周热门',
    TMDB_DISCOVER: '条件发现'
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
  color: var(--osr-text-placeholder);
}

.log-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 60vh;
  overflow-y: auto;
}

.log-item {
  padding: 10px 12px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
  display: flex;
  flex-direction: column;
  gap: 4px;

  .log-top {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;

    .log-title {
      font-size: 13px;
      font-weight: 600;
      color: var(--osr-text-primary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .log-meta {
    font-size: 12px;
    color: var(--osr-text-secondary);
  }

  .log-message {
    font-size: 12px;
    color: var(--osr-text-primary);
  }
}
</style>
