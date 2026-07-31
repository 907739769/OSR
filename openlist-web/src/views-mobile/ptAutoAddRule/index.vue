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
          class="mb-3"
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
        />
      </v-form>
    </MobileSearchPanel>

    <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="mdi-plus" @click="handleAdd('新增热门自动订阅规则')">
      新增
    </v-btn>

    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <div v-for="item in taskList" :key="item.id" class="task-card">
        <div class="card-content">
          <div class="card-top">
            <span class="task-name" :title="item.name">{{ item.name }}</span>
            <v-chip :color="item.enabled === '1' ? 'success' : 'info'" size="small" variant="tonal">
              {{ item.enabled === '1' ? '启用' : '停用' }}
            </v-chip>
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
          </div>
        </div>
        <div class="card-actions">
          <v-btn variant="text" color="primary" size="small" :loading="runningIds.has(item.id)" @click="handleRun(item)">执行</v-btn>
          <v-btn variant="text" color="primary" size="small" @click="handleShowLogs(item)">日志</v-btn>
          <v-btn variant="text" color="primary" size="small" @click="handleUpdate(item, '编辑规则')">编辑</v-btn>
          <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleDelete(item)">删除</v-btn>
        </div>
      </div>

      <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无规则" />
    </div>

    <MobilePager
      v-model:page-size="queryParams.pageSize"
      :page-num="queryParams.pageNum"
      :total="total"
      :total-pages="totalPages"
      @prev="prevPage"
      @next="nextPage"
      @size-change="handleSizeChange"
    />

    <v-dialog v-model="open" max-width="90%">
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
            <div class="form-item">
              <label class="form-label">是否启用</label>
              <v-switch v-model="form.enabled" true-value="1" false-value="0" color="primary" hide-details />
            </div>
            <div class="form-item">
              <label class="form-label">媒体类型</label>
              <v-radio-group v-model="form.mediaType" inline hide-details>
                <v-radio label="电影" value="MOVIE" />
                <v-radio label="剧集" value="TV" />
              </v-radio-group>
            </div>
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

    <v-dialog v-model="logDialogVisible" max-width="90%">
      <v-card title="执行日志">
        <v-card-text>
          <div class="log-list">
            <v-progress-linear v-if="logLoading" indeterminate color="primary" />
            <div v-for="log in logList" :key="log.id" class="log-item">
              <div class="log-top">
                <span class="log-title" :title="log.title">{{ log.title || '-' }}</span>
                <v-chip :color="resultTagType(log.result)" size="small" variant="tonal">{{ resultLabel(log.result) }}</v-chip>
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
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtAutoAddRule, REGION_OPTIONS } from '@/composables/usePtAutoAddRule'

const {
  taskList, loading, total, queryParams, queryRef,
  handleQuery, resetQuery,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed,
  runningIds, handleRun,
  logDialogVisible, logLoading, logList, handleShowLogs,
  genreOptions, genreExcludeArr, downloaderOptions
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
.mobile-page {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: calc(100vh - 120px);
  padding-bottom: 8px;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 200px;
  flex: 1;
}

.task-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--osr-surface);
  border-radius: var(--osr-radius-lg);
  padding: 12px;
  box-shadow: var(--osr-shadow-base);

  .card-content {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .card-top {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;

    .task-name {
      font-size: 14px;
      font-weight: 600;
      color: var(--osr-text-primary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .card-detail {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .detail-row {
    display: flex;
    gap: 8px;
    font-size: 12px;
    line-height: 1.6;

    .label {
      flex-shrink: 0;
      width: 62px;
      color: var(--osr-text-secondary);
    }

    .value {
      flex: 1;
      min-width: 0;
      color: var(--osr-text-primary);
    }
  }

  .card-actions {
    display: flex;
    justify-content: flex-end;
    gap: 4px;
    padding-top: 6px;
    border-top: 1px solid var(--osr-border-light);
  }
}

.form-item {
  margin-bottom: 16px;

  .form-label {
    display: block;
    margin-bottom: 6px;
    font-size: 13px;
    color: var(--osr-text-secondary);
  }
}

.fab-add {
  position: fixed;
  right: 20px;
  bottom: calc(56px + 16px + env(safe-area-inset-bottom, 0px));
  z-index: 1000;
  padding: 12px 20px;
  font-size: 14px;
  font-weight: 500;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);

  @media (min-width: 768px) {
    right: 40px;
    bottom: calc(56px + 24px);
    padding: 14px 24px;
    font-size: 15px;
  }
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
