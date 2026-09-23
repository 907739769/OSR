<template>
  <div class="page-container">
    <PageHeader
      icon="arrow-left-right"
      title="转移做种"
      desc="把下载器里已完成的种子搬到另一个下载器继续做种，数据文件原地不动"
    />

    <!-- Search Panel -->
    <SearchPanel :visible="showSearch" @search="handleQuery" @reset="resetQuery">
      <v-select
        v-model="queryParams.sourceDownloaderId"
        :items="downloaderOptions"
        label="源下载器"
        placeholder="全部"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="field-md"
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
        class="field-md"
      />
    </SearchPanel>

    <!-- Table Card -->
    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="primary" prepend-icon="plus" @click="handleAdd('新增转移规则')">新增</v-btn>
          <v-btn color="success" prepend-icon="square-pen" :disabled="notOneSelected" @click="handleUpdate(undefined, '修改转移规则')">
            修改
          </v-btn>
          <v-btn color="error" prepend-icon="trash-2" :disabled="noneSelected" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的转移规则？`)">
            批量删除
          </v-btn>
          <v-btn variant="text" prepend-icon="history" @click="loadRecords()">转移记录</v-btn>
          <v-btn variant="text" class="batch-select-all-btn" @click="toggleSelectAllPage(!isAllPageSelected)">
            {{ isAllPageSelected ? '取消全选' : '全选' }}
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
            <div class="card-checkbox">
              <v-checkbox
                :model-value="selectedIds.includes(item.id)"
                density="compact"
                hide-details
                @update:model-value="toggleSelect(item.id)"
              />
            </div>
            <span class="card-title" :title="item.name">{{ item.name }}</span>
            <!-- 卡片上直接启停：新建规则默认停用，确认预览无误后在这里打开 -->
            <v-switch
              :model-value="item.enabled === '1'"
              :loading="toggleLoadingIds.has(item.id)"
              :disabled="toggleLoadingIds.has(item.id)"
              color="success"
              density="compact"
              inset
              hide-details
              class="enabled-switch"
              :title="item.enabled === '1' ? '已启用，点击停用' : '已停用，点击启用'"
              @update:model-value="toggleEnabled(item, !!$event)"
            />
          </div>
          <div class="card-body">
            <div class="path-box">
              <div class="path-row">
                <span class="path-label path-label--src">源</span>
                <span class="path-text">{{ downloaderName(item.sourceDownloaderId) }}</span>
              </div>
              <div class="path-row">
                <span class="path-label path-label--dst">目标</span>
                <span class="path-text">{{ downloaderName(item.targetDownloaderId) }}</span>
              </div>
            </div>
            <div class="card-row">
              <span class="label">条件</span>
              <span class="value">{{ conditionText(item) }}</span>
            </div>
            <div class="card-row">
              <span class="label">转移后</span>
              <span class="value">{{ item.deleteSource === '0' ? '保留源种子' : '删除源种子（保留文件）' }}</span>
            </div>
            <div class="card-row">
              <span class="label">单轮上限</span>
              <span class="value">{{ item.maxPerRound || '不限' }}</span>
            </div>
            <!-- 运行情况：只看配置看不出这条规则是在正常干活还是一直在失败，点数字直达对应记录 -->
            <div class="card-row">
              <span class="label">运行情况</span>
              <span v-if="summaryOf(item.id)" class="value run-stats">
                <a class="run-stat run-stat--success" @click="loadRecords(item.id, 'COMPLETED')">完成 {{ summaryOf(item.id)!.COMPLETED }}</a>
                <a v-if="summaryOf(item.id)!.VERIFYING" class="run-stat run-stat--warning" @click="loadRecords(item.id, 'VERIFYING')">校验中 {{ summaryOf(item.id)!.VERIFYING }}</a>
                <a v-if="summaryOf(item.id)!.FAILED" class="run-stat run-stat--error" @click="loadRecords(item.id, 'FAILED')">失败 {{ summaryOf(item.id)!.FAILED }}</a>
              </span>
              <span v-else class="value">尚未转移过</span>
            </div>
            <div v-if="summaryOf(item.id)?.lastTime" class="card-row">
              <span class="label">最近转移</span>
              <span class="value">{{ summaryOf(item.id)!.lastTime }}</span>
            </div>
          </div>
          <div class="card-footer">
            <v-btn variant="text" color="primary" size="small" prepend-icon="eye" @click="handlePreview(item)">
              预览
            </v-btn>
            <v-btn variant="text" color="success" size="small" prepend-icon="play" :loading="runningIds.has(item.id)" @click="handleRun(item)">
              立即执行
            </v-btn>
            <v-menu>
              <template #activator="{ props }">
                <v-btn variant="text" size="small" append-icon="chevron-down" v-bind="props">更多</v-btn>
              </template>
              <v-list density="compact">
                <v-list-item prepend-icon="square-pen" title="修改" @click="handleUpdate(item, '修改转移规则')" />
                <v-list-item prepend-icon="history" title="转移记录" @click="loadRecords(item.id)" />
                <v-list-item prepend-icon="trash-2" title="删除" @click="handleDelete(item)" />
              </v-list>
            </v-menu>
          </div>
        </div>
        <v-empty-state v-if="!loading && taskList.length === 0" icon="inbox" title="暂无转移规则" />
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
          :length="Math.ceil(total / queryParams.pageSize) || 1"
          density="comfortable"
          @update:model-value="getList"
        />
      </div>
    </v-card>

    <!-- Add/Edit Dialog -->
    <PtTransferRuleFormDialog />

    <!-- 预览弹窗 -->
    <v-dialog v-model="previewOpen" max-width="900">
      <v-card :title="`转移预览 - ${previewRuleName}`">
        <v-card-text>
          <v-progress-linear v-if="previewLoading" indeterminate color="primary" />
          <v-alert v-if="!previewLoading && previewRows.length === 0" type="info" variant="tonal" text="源下载器上没有种子" />
          <template v-else-if="!previewLoading">
            <!-- 汇总：种子多时一屏跳过项里找不到「会转移」的那几条，先把数字摆出来 -->
            <div class="preview-summary">
              <span>源下载器共 {{ previewSummary.total }} 个种子，本轮会转移 <b>{{ previewSummary.transferable }}</b> 个</span>
              <span v-for="s in previewSummary.skipped" :key="s.reason" class="preview-skip">{{ s.reason }} {{ s.count }}</span>
              <v-switch
                v-model="previewOnlyTransferable"
                label="只看会转移"
                color="primary"
                density="compact"
                inset
                hide-details
                class="preview-only-switch"
              />
            </div>
            <v-alert v-if="visiblePreviewRows.length === 0" type="info" variant="tonal" text="本轮没有会转移的种子" />
            <v-table v-else density="compact">
              <thead>
                <tr>
                  <th>种子</th>
                  <th class="col-size">体积</th>
                  <th class="col-path">路径对照</th>
                  <th class="col-result">本轮</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in visiblePreviewRows" :key="row.hash">
                  <td class="cell-name" :title="row.name">{{ row.name }}</td>
                  <td>{{ formatTransferSize(row.sizeBytes) }}</td>
                  <td>
                    <div class="path-box">
                      <div class="path-row">
                        <span class="path-label path-label--src">源</span>
                        <span class="path-text">{{ row.sourceSavePath }}</span>
                      </div>
                      <div class="path-row">
                        <span class="path-label path-label--dst">目标</span>
                        <span class="path-text">{{ row.targetSavePath }}</span>
                      </div>
                    </div>
                  </td>
                  <td>
                    <StatusChip v-if="row.transferable" type="success" text="会转移" />
                    <StatusChip v-else type="warning" :text="row.skipReason" />
                  </td>
                </tr>
              </tbody>
            </v-table>
          </template>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="previewOpen = false">关闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 转移记录弹窗 -->
    <v-dialog v-model="recordOpen" max-width="1100">
      <v-card :title="recordRuleName ? `转移记录 - ${recordRuleName}` : '转移记录（全部规则）'">
        <v-card-text>
          <div class="record-toolbar">
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
              class="field-sm"
              @update:model-value="onRecordStateChange"
            />
            <span class="total-text">共 {{ recordTotal }} 条</span>
          </div>
          <v-progress-linear v-if="recordLoading" indeterminate color="primary" />
          <v-alert v-if="!recordLoading && records.length === 0" type="info" variant="tonal" text="暂无转移记录" />
          <v-table v-else density="compact">
            <thead>
              <tr>
                <th>种子</th>
                <th class="col-size">体积</th>
                <!-- 路径对照是转移失败时唯一有诊断价值的信息：映射配错的表现是「校验后进度 0%」 -->
                <th class="col-path">路径对照</th>
                <th class="col-result">状态</th>
                <th>说明</th>
                <th class="col-time">时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in records" :key="row.id">
                <td class="cell-name" :title="row.torrentName">{{ row.torrentName }}</td>
                <td>{{ formatTransferSize(row.sizeBytes) }}</td>
                <td>
                  <div class="path-box">
                    <div class="path-row">
                      <span class="path-label path-label--src">源</span>
                      <span class="path-text" :title="row.sourceSavePath">{{ row.sourceSavePath || '-' }}</span>
                    </div>
                    <div class="path-row">
                      <span class="path-label path-label--dst">目标</span>
                      <span class="path-text" :title="row.targetSavePath">{{ row.targetSavePath || '-' }}</span>
                    </div>
                  </div>
                </td>
                <td><StatusChip :type="transferStateType(row.state)" :text="transferStateText(row.state)" /></td>
                <td class="cell-reason" :title="row.failReason">
                  {{ row.failReason || (row.sourceDeleted === '1' ? '源种子已移除（文件保留）' : '') }}
                </td>
                <td>{{ row.finishTime || row.createTime }}</td>
              </tr>
            </tbody>
          </v-table>
          <v-pagination
            v-if="recordTotalPages > 1"
            v-model="recordQuery.pageNum"
            :length="recordTotalPages"
            density="comfortable"
            class="mt-2"
            @update:model-value="fetchRecords"
          />
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
  </div>
</template>

<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import PageHeader from '@/components/PageHeader.vue'
import {
  usePtTransferRule, TRANSFER_STATE_OPTIONS, transferStateText, transferStateType,
  formatTransferSize, transferConditionText as conditionText
} from '@/composables/usePtTransferRule'
import { usePageStateProvider } from '@/composables/pageStateContext'
import { useGridPageSize } from '@/composables/useGridPageSize'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'
import PtTransferRuleFormDialog from '@/components/dialogs/PtTransferRuleFormDialog.vue'

const { showSearch } = useSearchPanel()

// 表单弹窗与移动端共用一份（components/dialogs/），它靠 usePageStateProvider 取同一份状态。
// 预览与记录两个弹窗两端形态不同（PC 是数据表、移动端是卡片列表），各留一套
const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery,
  selectedIds, notOneSelected, noneSelected, toggleSelect,
  isAllPageSelected, toggleSelectAllPage,
  handleAdd, handleUpdate, handleDelete,
  downloaderOptions, downloaderName,
  summaryOf, toggleLoadingIds, toggleEnabled,
  previewOpen, previewLoading, previewRows, previewRuleName, previewOnlyTransferable,
  previewSummary, visiblePreviewRows, handlePreview,
  runningIds, handleRun,
  recordOpen, recordLoading, records, recordTotal, recordQuery, recordTotalPages, recordRuleName,
  loadRecords, fetchRecords, onRecordStateChange,
  clearLoading, handleClearFailed
} = usePageStateProvider(usePtTransferRule({ autoLoad: false }))

// 每页条数按网格实际列数取整到整行，窗口宽度变了跟着重算
const { gridRef, pageSizeOptions, setPageSize } = useGridPageSize((size) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
})
</script>

<style scoped lang="scss">
.enabled-switch {
  flex: none;
  margin-left: auto;
}

.run-stats {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 8px;
}

.run-stat {
  cursor: pointer;
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }

  &--success { color: var(--osr-success); }
  &--warning { color: var(--osr-warning); }
  &--error { color: var(--osr-error); }
}

.preview-summary,
.record-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-bottom: 12px;
  font-size: 13px;
}

.preview-skip {
  color: var(--osr-text-secondary);
}

.preview-only-switch {
  flex: none;
  margin-left: auto;
}

.record-toolbar .total-text {
  margin-left: auto;
  color: var(--osr-text-secondary);
}

// 表格列宽收敛：不定宽的话「种子名」会把路径对照挤成一条竖线，
// 而路径对照正是转移失败时唯一有诊断价值的那一列
.col-size {
  width: 90px;
}

.col-result {
  width: 110px;
}

.col-path {
  width: 300px;
}

.col-time {
  width: 170px;
}

.cell-name,
.cell-reason {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
