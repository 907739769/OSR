<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && taskList.length === 0"
    empty-icon="inbox"
    empty-title="暂无规则"
  >
    <template #head>
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

      <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="plus" @click="handleAdd('新增热门自动订阅规则')">
        新增
      </v-btn>
    </template>

    <v-card v-for="item in taskList" :key="item.id" class="task-card">
      <div class="card-content">
        <div class="card-top">
          <span class="card-title" :title="item.name">{{ item.name }}</span>
          <StatusChip :value="item.enabled" />
        </div>
        <div class="card-detail">
          <div class="detail-row">
            <span class="label">类型</span>
            <span class="value">{{ item.mediaType === 'MOVIE' ? '电影' : '剧集' }} · {{ sourceLabel(item.source, true) }}</span>
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
          <v-btn class="action-more" variant="text" color="default" size="small" icon="ellipsis" @click="openSheet(item)" />
        </div>
      </div>
    </v-card>

    <template #foot>
      <!-- 操作抽屉 -->
      <MobileActionSheet v-model="sheetOpen" :target="sheetTarget">
        <v-btn block prepend-icon="file-text" @click="run(() => handleShowLogs(sheetTarget))">日志</v-btn>
        <v-btn color="error" block prepend-icon="trash-2" @click="run(() => handleDelete(sheetTarget))">删除</v-btn>
      </MobileActionSheet>

      <MobilePager
        v-model:page-size="queryParams.pageSize"
        :page-num="queryParams.pageNum"
        :total="total"
        :total-pages="totalPages"
        @prev="prevPage"
        @next="nextPage"
        @size-change="handleSizeChange"
      />

      <!-- 新增/编辑弹窗（两端共用） -->
      <PtAutoAddRuleFormDialog />

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
              <v-empty-state v-if="!logLoading && logList.length === 0" icon="inbox" title="暂无日志" />
            </div>
          </v-card-text>
        </v-card>
      </v-dialog>
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileActionSheet from '@/components/mobile/MobileActionSheet.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtAutoAddRule } from '@/composables/usePtAutoAddRule'
import { usePageStateProvider } from '@/composables/pageStateContext'
import { useActionSheet } from '@/composables/useActionSheet'
import PtAutoAddRuleFormDialog from '@/components/dialogs/PtAutoAddRuleFormDialog.vue'

/** 卡片「更多」动作面板：开关状态与「执行完自动关闭」都在 useActionSheet 里 */
const { sheetOpen, sheetTarget, openSheet, run } = useActionSheet()

// 表单弹窗与 PC 端共用一份（components/dialogs/），它靠 usePageStateProvider 取同一份状态
const {
  taskList, loading, total, queryParams, queryRef,
  handleQuery, resetQuery,
  handleAdd, handleUpdate, handleDelete,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed,
  runningIds, handleRun,
  logDialogVisible, logLoading, logList, handleShowLogs,
  filterText, sourceLabel, resultLabel, resultTagType
} = usePageStateProvider(usePtAutoAddRule())

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
