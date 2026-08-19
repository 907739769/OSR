<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-swap-horizontal"
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
          <v-btn color="primary" prepend-icon="mdi-plus" @click="handleAdd('新增转移规则')">新增</v-btn>
          <v-btn color="success" prepend-icon="mdi-pencil-outline" :disabled="notOneSelected" @click="handleUpdate(undefined, '修改转移规则')">
            修改
          </v-btn>
          <v-btn color="error" prepend-icon="mdi-delete-outline" :disabled="noneSelected" @click="handleDelete(undefined, `是否确认删除编号为“${selectedIds}”的转移规则？`)">
            批量删除
          </v-btn>
          <v-btn variant="text" prepend-icon="mdi-history" @click="loadRecords()">转移记录</v-btn>
          <v-btn variant="text" class="batch-select-all-btn" @click="toggleSelectAllPage(!isAllPageSelected)">
            {{ isAllPageSelected ? '取消全选' : '全选' }}
          </v-btn>
        </div>
        <v-btn variant="text" prepend-icon="mdi-filter-outline" @click="showSearch = !showSearch">
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
            <StatusChip :value="item.enabled" />
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
          </div>
          <div class="card-footer">
            <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-eye-outline" @click="handlePreview(item)">
              预览
            </v-btn>
            <v-btn variant="text" color="success" size="small" prepend-icon="mdi-play-outline" :loading="runLoading" @click="handleRun(item)">
              立即执行
            </v-btn>
            <v-menu>
              <template #activator="{ props }">
                <v-btn variant="text" size="small" append-icon="mdi-chevron-down" v-bind="props">更多</v-btn>
              </template>
              <v-list density="compact">
                <v-list-item prepend-icon="mdi-pencil-outline" title="修改" @click="handleUpdate(item, '修改转移规则')" />
                <v-list-item prepend-icon="mdi-history" title="转移记录" @click="loadRecords(item.id)" />
                <v-list-item prepend-icon="mdi-delete-outline" title="删除" @click="handleDelete(item)" />
              </v-list>
            </v-menu>
          </div>
        </div>
        <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无转移规则" />
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
    <v-dialog v-model="open" max-width="600">
      <v-card :title="dialogTitle">
        <v-card-text>
          <v-form ref="formRef">
            <v-text-field v-model="form.name" label="规则名" placeholder="如「qB 下完搬到 TR 保种」" :rules="toRules(rules.name)" class="mb-2" />
            <v-select
              v-model="form.sourceDownloaderId"
              :items="sourceOptions"
              label="源下载器"
              :rules="toRules(rules.sourceDownloaderId)"
              hint="Transmission 无法导出种子文件，不能作为来源，因此不在此列出"
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
            <div class="inline-fields mb-2">
              <v-text-field v-model.number="form.minSeedHours" label="最短做种时长(小时)" type="number" hint="在源下载器上做满这么久才搬，0 表示不限" persistent-hint />
              <v-text-field v-model.number="form.maxPerRound" label="单轮上限" type="number" hint="每轮最多发起多少个转移" persistent-hint />
            </div>
            <div class="inline-fields mb-2">
              <v-text-field v-model.number="form.minSizeGb" label="体积下限(GB)" type="number" />
              <v-text-field v-model.number="form.maxSizeGb" label="体积上限(GB)" type="number" placeholder="留空表示不限" />
            </div>
            <v-text-field v-model="form.includeTags" label="仅转移带这些标签的种子" placeholder="逗号分隔，留空表示不限" class="mb-2" />
            <v-text-field v-model="form.excludeTags" label="排除标签" placeholder="逗号分隔，带其中任一标签的种子永不转移" class="mb-2" />
            <v-text-field v-model="form.targetTag" label="目标端标签" placeholder="加到目标下载器时打的标签" class="mb-2" />
            <v-textarea
              v-model="form.pathMapping"
              label="保存路径映射"
              rows="2"
              placeholder="两个下载器挂载一致时留空；不一致填 [{&quot;from&quot;:&quot;/downloads&quot;,&quot;to&quot;:&quot;/data/downloads&quot;}]"
              hint="目标下载器要能在映射后的路径下找到同一份文件，否则校验不通过、转移会被撤销"
              persistent-hint
              class="mb-3"
            />
            <v-text-field v-model.number="form.verifyTimeoutMinutes" label="校验超时(分钟)" type="number" hint="目标端校验超过这个时间仍未完成即判失败并撤销" persistent-hint class="mb-3" />
            <FormField label="转移成功后" tip="删除的只是源下载器里的种子任务，数据文件永远保留——那正是目标下载器接手做种的数据">
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
    <v-dialog v-model="previewOpen" max-width="900">
      <v-card :title="`转移预览 - ${previewRuleName}`">
        <v-card-text>
          <v-progress-linear v-if="previewLoading" indeterminate color="primary" />
          <v-alert v-if="!previewLoading && previewRows.length === 0" type="info" variant="tonal" text="源下载器上没有种子" />
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
              <tr v-for="row in previewRows" :key="row.hash">
                <td class="cell-name" :title="row.name">{{ row.name }}</td>
                <td>{{ formatSize(row.sizeBytes) }}</td>
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
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="previewOpen = false">关闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 转移记录弹窗 -->
    <v-dialog v-model="recordOpen" max-width="900">
      <v-card title="转移记录">
        <v-card-text>
          <v-progress-linear v-if="recordLoading" indeterminate color="primary" />
          <v-alert v-if="!recordLoading && records.length === 0" type="info" variant="tonal" text="暂无转移记录" />
          <v-table v-else density="compact">
            <thead>
              <tr>
                <th>种子</th>
                <th class="col-size">体积</th>
                <th class="col-result">状态</th>
                <th>说明</th>
                <th class="col-time">时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in records" :key="row.id">
                <td class="cell-name" :title="row.torrentName">{{ row.torrentName }}</td>
                <td>{{ formatSize(row.sizeBytes) }}</td>
                <td><StatusChip :type="stateType(row.state)" :text="stateText(row.state)" /></td>
                <td class="cell-reason" :title="row.failReason">
                  {{ row.failReason || (row.sourceDeleted === '1' ? '源种子已移除（文件保留）' : '') }}
                </td>
                <td>{{ row.finishTime || row.createTime }}</td>
              </tr>
            </tbody>
          </v-table>
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
import FormField from '@/components/FormField.vue'
import { usePtTransferRule } from '@/composables/usePtTransferRule'
import { useGridPageSize } from '@/composables/useGridPageSize'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'

const { showSearch } = useSearchPanel()

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery,
  selectedIds, notOneSelected, noneSelected, toggleSelect,
  isAllPageSelected, toggleSelectAllPage,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, handleUpdate, submitForm, handleDelete,
  downloaderOptions, sourceOptions, downloaderName,
  previewOpen, previewLoading, previewRows, previewRuleName, handlePreview,
  runLoading, handleRun,
  recordOpen, recordLoading, records, loadRecords, clearLoading, handleClearFailed
} = usePtTransferRule({ autoLoad: false })

// 每页条数按网格实际列数取整到整行，窗口宽度变了跟着重算
const { gridRef, pageSizeOptions, setPageSize } = useGridPageSize((size) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
})

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
