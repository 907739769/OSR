<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && taskList.length === 0"
    empty-icon="inbox"
    empty-title="暂无转移规则"
  >
    <template #head>
      <!-- 搜索 -->
      <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
        <v-select
          v-model="queryParams.sourceDownloaderId"
          :items="downloaderOptions"
          label="源下载器"
          placeholder="全部"
          clearable
          density="compact"
          variant="outlined"
          hide-details
        />
        <v-select
          v-model="queryParams.targetDownloaderId"
          :items="downloaderOptions"
          label="目标下载器"
          placeholder="全部"
          clearable
          density="compact"
          variant="outlined"
          hide-details
        />
      </MobileSearchPanel>

      <!-- 批量操作 -->
      <MobileBatchBar
        :visible="selectedIds.length > 0"
        :count="selectedIds.length"
        :all-selected="isAllPageSelected"
        @toggle-all="toggleSelectAllPage"
        @cancel="clearSelection"
      >
        <v-btn variant="text" color="error" size="small" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的转移规则？`)">
          批量删除
        </v-btn>
      </MobileBatchBar>

      <!-- 列表 -->
    </template>

    <v-card
      v-for="item in taskList"
      :key="item.id"
      class="task-card"
      :class="{ selected: selectedIds.includes(item.id) }"
      @click="handleCardClick($event, item.id)"
    >
      <div class="card-checkbox">
        <v-checkbox-btn
          :model-value="selectedIds.includes(item.id)"
          density="compact"
          @click.stop="toggleSelect(item.id)"
        />
      </div>
      <div class="card-content">
        <div class="card-top">
          <span class="card-title">{{ item.name }}</span>
          <div class="enabled-switch-wrap" @click.stop>
            <v-switch
              :model-value="item.enabled === '1'"
              :loading="toggleLoadingIds.has(item.id)"
              :disabled="toggleLoadingIds.has(item.id)"
              color="success"
              density="compact"
              inset
              hide-details
              @update:model-value="toggleEnabled(item, !!$event)"
            />
          </div>
        </div>
        <div class="card-path">
          <v-icon class="card-path-icon" size="14">arrow-left-right</v-icon>
          <span class="card-path-text">
            {{ downloaderName(item.sourceDownloaderId) }} → {{ downloaderName(item.targetDownloaderId) }}
          </span>
        </div>
        <div class="card-detail">
          <div class="detail-row">
            <span class="label">条件</span>
            <span class="value">{{ conditionText(item) }}</span>
          </div>
          <div class="detail-row">
            <span class="label">转移后</span>
            <span class="value">{{ item.deleteSource === '0' ? '保留源种子' : '删除源种子（保留文件）' }}</span>
          </div>
          <div class="detail-row" @click.stop>
            <span class="label">运行情况</span>
            <span v-if="summaryOf(item.id)" class="value run-stats">
              <a class="run-stat run-stat--success" @click="loadRecords(item.id, 'COMPLETED')">完成 {{ summaryOf(item.id)!.COMPLETED }}</a>
              <a v-if="summaryOf(item.id)!.VERIFYING" class="run-stat run-stat--warning" @click="loadRecords(item.id, 'VERIFYING')">校验中 {{ summaryOf(item.id)!.VERIFYING }}</a>
              <a v-if="summaryOf(item.id)!.FAILED" class="run-stat run-stat--error" @click="loadRecords(item.id, 'FAILED')">失败 {{ summaryOf(item.id)!.FAILED }}</a>
            </span>
            <span v-else class="value">尚未转移过</span>
          </div>
        </div>
        <div v-if="summaryOf(item.id)?.lastTime" class="card-time">最近转移 {{ summaryOf(item.id)!.lastTime }}</div>
        <div class="card-actions" @click.stop>
          <v-btn variant="text" color="primary" size="small" prepend-icon="eye" @click="handlePreview(item)">预览</v-btn>
          <v-btn variant="text" color="success" size="small" prepend-icon="play" :loading="runningIds.has(item.id)" @click="handleRun(item)">执行</v-btn>
          <v-menu>
            <template #activator="{ props }">
              <v-btn class="action-more" variant="text" size="small" icon="ellipsis" v-bind="props" />
            </template>
            <v-list density="compact">
              <v-list-item prepend-icon="square-pen" title="修改" @click="handleUpdate(item, '修改转移规则')" />
              <v-list-item prepend-icon="history" title="转移记录" @click="loadRecords(item.id)" />
              <v-list-item prepend-icon="trash-2" title="删除" @click="handleDelete(item)" />
            </v-list>
          </v-menu>
        </div>
      </div>
    </v-card>

    <template #foot>
      <!-- 分页 -->
      <MobilePager
        v-model:page-size="queryParams.pageSize"
        :page-num="queryParams.pageNum"
        :total="total"
        :total-pages="totalPages"
        @prev="prevPage"
        @next="nextPage"
        @size-change="handleSizeChange"
      />

      <!-- 新增/编辑弹窗 -->
      <!-- 新增/编辑弹窗（两端共用） -->
      <PtTransferRuleFormDialog />

      <!-- 预览弹窗 -->
      <v-dialog v-model="previewOpen" width="92%">
        <v-card :title="`转移预览 - ${previewRuleName}`">
          <v-card-text class="scroll-body">
            <v-progress-linear v-if="previewLoading" indeterminate color="primary" />
            <v-alert v-if="!previewLoading && previewRows.length === 0" type="info" variant="tonal" text="源下载器上没有种子" />
            <template v-else-if="!previewLoading">
              <div class="preview-summary">
                <div>共 {{ previewSummary.total }} 个种子，本轮会转移 <b>{{ previewSummary.transferable }}</b> 个</div>
                <div v-if="previewSummary.skipped.length" class="preview-skip">
                  {{ previewSummary.skipped.map(s => `${s.reason} ${s.count}`).join('，') }}
                </div>
                <v-switch v-model="previewOnlyTransferable" label="只看会转移" color="primary" density="compact" inset hide-details />
              </div>
              <v-alert v-if="visiblePreviewRows.length === 0" type="info" variant="tonal" text="本轮没有会转移的种子" />
              <div v-for="row in visiblePreviewRows" :key="row.hash" class="preview-item">
                <div class="preview-title">{{ row.name }}</div>
                <div class="preview-meta">
                  <span>{{ formatTransferSize(row.sizeBytes) }}</span>
                  <StatusChip v-if="row.transferable" type="success" text="会转移" />
                  <StatusChip v-else type="warning" :text="row.skipReason" />
                </div>
                <div class="preview-path">{{ row.sourceSavePath }} → {{ row.targetSavePath }}</div>
              </div>
            </template>
          </v-card-text>
          <v-card-actions>
            <v-spacer />
            <v-btn variant="outlined" @click="previewOpen = false">关闭</v-btn>
          </v-card-actions>
        </v-card>
      </v-dialog>

      <!-- 转移记录弹窗 -->
      <v-dialog v-model="recordOpen" width="92%">
        <v-card :title="recordRuleName ? `转移记录 - ${recordRuleName}` : '转移记录（全部规则）'">
          <v-card-text class="scroll-body">
            <v-select
              v-model="recordQuery.state"
              :items="TRANSFER_STATE_OPTIONS"
              item-title="title"
              item-value="value"
              label="状态"
              placeholder="全部"
              clearable
              density="compact"
              variant="outlined"
              hide-details
              class="mb-2"
              @update:model-value="onRecordStateChange"
            />
            <v-progress-linear v-if="recordLoading" indeterminate color="primary" />
            <v-alert v-if="!recordLoading && records.length === 0" type="info" variant="tonal" text="暂无转移记录" />
            <div v-for="row in records" :key="row.id" class="preview-item">
              <div class="preview-title">{{ row.torrentName }}</div>
              <div class="preview-meta">
                <span>{{ formatTransferSize(row.sizeBytes) }}</span>
                <StatusChip :type="transferStateType(row.state)" :text="transferStateText(row.state)" />
                <span class="preview-time">{{ row.finishTime || row.createTime }}</span>
              </div>
              <!-- 路径对照是转移失败时唯一有诊断价值的信息 -->
              <div v-if="row.sourceSavePath || row.targetSavePath" class="preview-path">{{ row.sourceSavePath || '-' }} → {{ row.targetSavePath || '-' }}</div>
              <div v-if="row.failReason" class="preview-reason">{{ row.failReason }}</div>
            </div>
            <div v-if="recordTotalPages > 1" class="record-pager">
              <v-btn variant="text" size="small" :disabled="recordQuery.pageNum <= 1" @click="recordQuery.pageNum--; fetchRecords()">上一页</v-btn>
              <span>{{ recordQuery.pageNum }} / {{ recordTotalPages }}（共 {{ recordTotal }} 条）</span>
              <v-btn variant="text" size="small" :disabled="recordQuery.pageNum >= recordTotalPages" @click="recordQuery.pageNum++; fetchRecords()">下一页</v-btn>
            </div>
          </v-card-text>
          <v-card-actions>
            <!-- 失败太多次的种子后端会停止自动重试，改完配置后要靠这个按钮解除 -->
            <v-btn variant="text" color="warning" :loading="clearLoading" @click="handleClearFailed">
              清除失败记录
            </v-btn>
            <v-spacer />
            <v-btn variant="outlined" @click="recordOpen = false">关闭</v-btn>
          </v-card-actions>
        </v-card>
      </v-dialog>
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileBatchBar from '@/components/mobile/MobileBatchBar.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import {
  usePtTransferRule, TRANSFER_STATE_OPTIONS, transferStateText, transferStateType,
  formatTransferSize, transferConditionText as conditionText
} from '@/composables/usePtTransferRule'
import { usePageStateProvider } from '@/composables/pageStateContext'
import PtTransferRuleFormDialog from '@/components/dialogs/PtTransferRuleFormDialog.vue'
import { useMobilePageAction } from '@/composables/useMobilePageAction'

// 表单弹窗与 PC 端共用一份（components/dialogs/），它靠 usePageStateProvider 取同一份状态。
// 预览与记录两个弹窗两端形态不同（PC 是数据表、移动端是卡片列表），各留一套
const {
  taskList, loading, total, queryParams,
  handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  isAllPageSelected, toggleSelectAllPage,
  handleAdd, handleUpdate, handleDelete,
  downloaderOptions, downloaderName,
  summaryOf, toggleLoadingIds, toggleEnabled,
  previewOpen, previewLoading, previewRows, previewRuleName, previewOnlyTransferable,
  previewSummary, visiblePreviewRows, handlePreview,
  runningIds, handleRun,
  recordOpen, recordLoading, records, recordTotal, recordQuery, recordTotalPages, recordRuleName,
  loadRecords, fetchRecords, onRecordStateChange,
  clearLoading, handleClearFailed,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed
} = usePageStateProvider(usePtTransferRule())

// 新增按钮并在悬浮底栏右侧（原先是压在内容上的右下角悬浮按钮），见 useMobilePageAction
useMobilePageAction(() => ({ icon: 'plus', label: '新增转移规则', onClick: () => handleAdd('新增转移规则') }))
</script>

<style scoped lang="scss">
.enabled-switch-wrap {
  flex: none;
  margin-left: auto;
}

.run-stats {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 8px;
}

.run-stat {
  text-decoration: none;

  &--success { color: var(--osr-success); }
  &--warning { color: var(--osr-warning); }
  &--error { color: var(--osr-error); }
}

.preview-summary {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 8px;
  font-size: 13px;
}

.preview-skip {
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.record-pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

// 预览/记录在移动端排成卡片而不是表格：一行四列在 393px 宽度下每列只剩几十像素，
// 种子名和路径这两项恰恰是最长的
.scroll-body {
  max-height: 70vh;
  overflow-y: auto;
}

.preview-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--osr-border-light);

  &:last-child {
    border-bottom: none;
  }
}

.preview-title {
  font-size: 13px;
  font-weight: 500;
  word-break: break-all;
}

.preview-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.preview-time {
  margin-left: auto;
}

.preview-path,
.preview-reason {
  margin-top: 4px;
  font-size: 12px;
  color: var(--osr-text-secondary);
  word-break: break-all;
}
</style>
