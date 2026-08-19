<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && taskList.length === 0"
    empty-icon="mdi-inbox-outline"
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

      <!-- 新增 FAB -->
      <v-btn class="fab-add" color="primary" size="large" rounded="pill" prepend-icon="mdi-plus" @click="handleAdd('新增转移规则')">
        新增
      </v-btn>

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
          <StatusChip :value="item.enabled" />
        </div>
        <div class="card-path">
          <v-icon class="card-path-icon" size="14">mdi-swap-horizontal</v-icon>
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
        </div>
        <div class="card-actions" @click.stop>
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-eye-outline" @click="handlePreview(item)">预览</v-btn>
          <v-btn variant="text" color="success" size="small" prepend-icon="mdi-play-outline" :loading="runLoading" @click="handleRun(item)">执行</v-btn>
          <v-menu>
            <template #activator="{ props }">
              <v-btn class="action-more" variant="text" size="small" icon="mdi-dots-horizontal" v-bind="props" />
            </template>
            <v-list density="compact">
              <v-list-item prepend-icon="mdi-pencil-outline" title="修改" @click="handleUpdate(item, '修改转移规则')" />
              <v-list-item prepend-icon="mdi-history" title="转移记录" @click="loadRecords(item.id)" />
              <v-list-item prepend-icon="mdi-delete-outline" title="删除" @click="handleDelete(item)" />
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
      <v-dialog v-model="open" width="92%">
        <v-card :title="dialogTitle">
          <v-card-text>
            <v-form ref="formRef">
              <v-text-field v-model="form.name" label="规则名" placeholder="如「qB 下完搬到 TR 保种」" :rules="toRules(rules.name)" class="mb-2" />
              <v-select
                v-model="form.sourceDownloaderId"
                :items="sourceOptions"
                label="源下载器"
                :rules="toRules(rules.sourceDownloaderId)"
                hint="Transmission 无法导出种子文件，不能作为来源"
                persistent-hint
                class="mb-3"
              />
              <v-select
                v-model="form.targetDownloaderId"
                :items="downloaderOptions"
                label="目标下载器"
                :rules="toRules(rules.targetDownloaderId)"
                class="mb-2"
              />
              <v-text-field v-model.number="form.minSeedHours" label="最短做种时长(小时)" type="number" hint="0 表示不限" persistent-hint class="mb-3" />
              <div class="inline-fields mb-2">
                <v-text-field v-model.number="form.minSizeGb" label="体积下限(GB)" type="number" />
                <v-text-field v-model.number="form.maxSizeGb" label="体积上限(GB)" type="number" placeholder="不限" />
              </div>
              <v-text-field v-model="form.includeTags" label="仅转移带这些标签的种子" placeholder="逗号分隔，留空不限" class="mb-2" />
              <v-text-field v-model="form.excludeTags" label="排除标签" placeholder="逗号分隔" class="mb-2" />
              <v-text-field v-model="form.targetTag" label="目标端标签" class="mb-2" />
              <v-textarea
                v-model="form.pathMapping"
                label="保存路径映射"
                rows="2"
                placeholder="挂载一致时留空；否则 [{&quot;from&quot;:&quot;/downloads&quot;,&quot;to&quot;:&quot;/data/downloads&quot;}]"
                hint="目标下载器要能在映射后的路径下找到同一份文件"
                persistent-hint
                class="mb-3"
              />
              <div class="inline-fields mb-2">
                <v-text-field v-model.number="form.maxPerRound" label="单轮上限" type="number" />
                <v-text-field v-model.number="form.verifyTimeoutMinutes" label="校验超时(分钟)" type="number" />
              </div>
              <FormField label="转移成功后" tip="删除的只是源下载器里的种子任务，数据文件永远保留">
                <v-radio-group v-model="form.deleteSource" inline hide-details>
                  <v-radio label="删除源种子" value="1" />
                  <v-radio label="保留源种子" value="0" />
                </v-radio-group>
              </FormField>
              <FormField label="状态" tip="新建规则默认停用，确认预览结果符合预期后再启用">
                <v-radio-group v-model="form.enabled" inline hide-details>
                  <v-radio label="启用" value="1" />
                  <v-radio label="停用" value="0" />
                </v-radio-group>
              </FormField>
              <v-text-field v-model="form.remark" label="备注" class="mb-2" />
            </v-form>
          </v-card-text>
          <v-card-actions>
            <v-spacer />
            <v-btn variant="outlined" @click="open = false">取消</v-btn>
            <v-btn color="primary" variant="flat" :loading="submitLoading" @click="submitForm">确定</v-btn>
          </v-card-actions>
        </v-card>
      </v-dialog>

      <!-- 预览弹窗 -->
      <v-dialog v-model="previewOpen" width="92%">
        <v-card :title="`转移预览 - ${previewRuleName}`">
          <v-card-text class="scroll-body">
            <v-progress-linear v-if="previewLoading" indeterminate color="primary" />
            <v-alert v-if="!previewLoading && previewRows.length === 0" type="info" variant="tonal" text="源下载器上没有种子" />
            <div v-for="row in previewRows" :key="row.hash" class="preview-item">
              <div class="preview-title">{{ row.name }}</div>
              <div class="preview-meta">
                <span>{{ formatSize(row.sizeBytes) }}</span>
                <StatusChip v-if="row.transferable" type="success" text="会转移" />
                <StatusChip v-else type="warning" :text="row.skipReason" />
              </div>
              <div class="preview-path">{{ row.sourceSavePath }} → {{ row.targetSavePath }}</div>
            </div>
          </v-card-text>
          <v-card-actions>
            <v-spacer />
            <v-btn variant="outlined" @click="previewOpen = false">关闭</v-btn>
          </v-card-actions>
        </v-card>
      </v-dialog>

      <!-- 转移记录弹窗 -->
      <v-dialog v-model="recordOpen" width="92%">
        <v-card title="转移记录">
          <v-card-text class="scroll-body">
            <v-progress-linear v-if="recordLoading" indeterminate color="primary" />
            <v-alert v-if="!recordLoading && records.length === 0" type="info" variant="tonal" text="暂无转移记录" />
            <div v-for="row in records" :key="row.id" class="preview-item">
              <div class="preview-title">{{ row.torrentName }}</div>
              <div class="preview-meta">
                <span>{{ formatSize(row.sizeBytes) }}</span>
                <StatusChip :type="stateType(row.state)" :text="stateText(row.state)" />
                <span class="preview-time">{{ row.finishTime || row.createTime }}</span>
              </div>
              <div v-if="row.failReason" class="preview-reason">{{ row.failReason }}</div>
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
import FormField from '@/components/FormField.vue'
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileBatchBar from '@/components/mobile/MobileBatchBar.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtTransferRule } from '@/composables/usePtTransferRule'

const {
  taskList, loading, total, queryParams,
  handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  isAllPageSelected, toggleSelectAllPage,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  downloaderOptions, sourceOptions, downloaderName,
  previewOpen, previewLoading, previewRows, previewRuleName, handlePreview,
  runLoading, handleRun,
  recordOpen, recordLoading, records, loadRecords, clearLoading, handleClearFailed,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed
} = usePtTransferRule()

// 将对象格式的校验规则（composable 返回）转换为 Vuetify 的规则函数数组
const toRules = (fieldRules?: any[]) => {
  return (fieldRules || []).map((r: any) => (value: any) => {
    if (r.required && (value === undefined || value === null || value === '')) return r.message
    if (r.pattern && value && !r.pattern.test(value)) return r.message
    return true
  })
}

const formatSize = (bytes?: number) => {
  if (!bytes) return '-'
  const gb = bytes / 1024 ** 3
  return gb >= 1 ? `${gb.toFixed(2)} GB` : `${(bytes / 1024 ** 2).toFixed(0)} MB`
}

/** 把规则的几个筛选条件压成一行人话，卡片上只有一行的位置 */
const conditionText = (item: any) => {
  const parts: string[] = []
  if (item.minSeedHours > 0) parts.push(`做满 ${item.minSeedHours} 小时`)
  const min = Number(item.minSizeGb) || 0
  if (min > 0 || item.maxSizeGb) {
    parts.push(`${min}~${item.maxSizeGb ?? '∞'} GB`)
  }
  if (item.includeTags) parts.push(`含标签 ${item.includeTags}`)
  if (item.excludeTags) parts.push(`排除 ${item.excludeTags}`)
  return parts.length ? parts.join('，') : '全部已完成的种子'
}

const stateText = (state: string) =>
  ({ VERIFYING: '校验中', COMPLETED: '已完成', FAILED: '失败', SKIPPED: '已跳过' } as any)[state] || state

const stateType = (state: string) =>
  ({ VERIFYING: 'warning', COMPLETED: 'success', FAILED: 'error', SKIPPED: 'info' } as any)[state] || 'info'
</script>

<style scoped lang="scss">
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
