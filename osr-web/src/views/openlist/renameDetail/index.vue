<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-format-list-checks"
      title="重命名明细"
      desc="逐文件的重命名与刮削结果，可重试改名、清理产物或仅删除记录"
    />

    <!-- Search Panel -->
    <v-card v-if="showSearch" class="search-card">
      <v-form ref="queryRef" @submit.prevent="handleQuery">
        <div class="search-fields">
          <v-text-field
            v-model="queryParams.originalName"
            label="原文件名"
            placeholder="请输入原文件名"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-text-field
            v-model="queryParams.newName"
            label="新文件名"
            placeholder="请输入新文件名"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-text-field
            v-model="queryParams.originalPath"
            label="原目录"
            placeholder="请输入原目录"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-text-field
            v-model="queryParams.newPath"
            label="新目录"
            placeholder="请输入新目录"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-text-field
            v-model="queryParams.title"
            label="影视名称"
            placeholder="请输入影视名称"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="handleQuery"
          />
          <v-select
            v-model="queryParams.status"
            label="状态"
            :items="[{ title: '成功', value: '1' }, { title: '失败', value: '0' }]"
            clearable
            density="compact"
            variant="outlined"
            hide-details
            class="status-select"
          />
          <div class="date-range-fields">
            <v-text-field
              v-model="dateStart"
              label="开始日期"
              type="date"
              density="compact"
              variant="outlined"
              hide-details
              class="date-field"
            />
            <span class="date-range-sep">-</span>
            <v-text-field
              v-model="dateEnd"
              label="结束日期"
              type="date"
              density="compact"
              variant="outlined"
              hide-details
              class="date-field"
            />
          </div>
          <div class="search-actions">
            <v-btn color="primary" prepend-icon="mdi-magnify" @click="handleQuery">搜索</v-btn>
            <v-btn variant="outlined" prepend-icon="mdi-refresh" @click="resetQuery">重置</v-btn>
          </div>
        </div>
      </v-form>
    </v-card>

    <!-- Table Card -->
    <v-card class="table-card">
      <!-- Action Bar -->
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="error" prepend-icon="mdi-broom" :disabled="multiple" @click="handleBatchPurge()">
            批量清理产物
          </v-btn>
          <v-btn color="error" variant="outlined" prepend-icon="mdi-database-remove-outline" :disabled="multiple" @click="handleBatchDelete()">
            仅删记录
          </v-btn>
          <v-btn color="info" prepend-icon="mdi-refresh" :disabled="multiple" @click="handleBatchExecute()">
            批量执行
          </v-btn>
          <v-btn color="warning" prepend-icon="mdi-refresh" :disabled="multiple" @click="handleBatchScrape()">
            批量刮削
          </v-btn>
          <v-btn color="error" variant="outlined" prepend-icon="mdi-delete-outline" :disabled="multiple" @click="handleBatchDeleteScrape()">
            批量删除刮削
          </v-btn>
        </div>
        <v-btn variant="text" prepend-icon="mdi-filter-outline" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <!-- Desktop Table -->
      <v-data-table-server
        :loading="loading"
        :items="recordList"
        :items-length="total"
        :headers="headers"
        :items-per-page="queryParams.pageSize"
        :page="queryParams.pageNum"
        show-select
        item-value="id"
        return-object
        :model-value="selectedRows"
        class="modern-table"
        @update:model-value="onSelectionChange"
        @update:page="onPageChange"
        @update:items-per-page="onSizeChange"
      >
        <template #item.detail="{ item }">
          <div class="path-box rename-box">
            <div class="path-row">
              <span class="path-label path-label--src">原</span>
              <span class="path-name" :title="item.originalName">{{ item.originalName }}</span>
              <span class="path-text path-text--muted" :title="item.originalPath">{{ item.originalPath }}</span>
            </div>
            <div class="path-row">
              <span class="path-label path-label--dst">新</span>
              <span class="path-name" :title="item.newName">{{ item.newName }}</span>
              <span class="path-text path-text--muted" :title="item.newPath">{{ item.newPath }}</span>
            </div>
          </div>
        </template>
        <template #item.status="{ item }">
          <StatusChip :type="item.status === '0' ? 'error' : 'success'" :text="item.status === '0' ? '失败' : '成功'" />
        </template>
        <template #item.scrapeStatus="{ item }">
          <StatusChip v-if="item.scrapeStatus === '1'" type="success" text="成功" />
          <StatusChip v-else-if="item.scrapeStatus === '2'" type="error" text="失败" />
          <StatusChip v-else-if="item.scrapeStatus === '0'" type="info" text="未执行" />
          <span v-else class="scrape-none">-</span>
        </template>
        <template #item.actions="{ item }">
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-refresh" @click="handleRetryOne(item)">
            重试
          </v-btn>
          <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-movie-search-outline" @click="handleScrapeOne(item)">
            刮削
          </v-btn>
          <v-menu>
            <template #activator="{ props: menuProps }">
              <v-btn v-bind="menuProps" variant="text" color="info" size="small" append-icon="mdi-chevron-down">
                更多
              </v-btn>
            </template>
            <v-list density="compact">
              <v-list-item
                v-if="item.scrapeStatus === '1'"
                base-color="error"
                prepend-icon="mdi-delete-outline"
                title="删除刮削"
                @click="handleDeleteScrapeOne(item)"
              />
              <v-list-item
                base-color="error"
                prepend-icon="mdi-broom"
                title="清理产物"
                @click="handlePurgeOne(item)"
              />
              <v-list-item
                base-color="error"
                prepend-icon="mdi-database-remove-outline"
                title="仅删记录"
                @click="handleDeleteOne(item)"
              />
            </v-list>
          </v-menu>
        </template>
      </v-data-table-server>

    </v-card>

    <!-- Retry Dialog -->
    <v-dialog v-model="retryDialogVisible" max-width="480" @update:model-value="onRetryDialogUpdate">
      <v-card title="重试重命名">
        <v-card-text>
          <v-form ref="retryFormRef">
            <v-text-field
              v-model="retryForm.title"
              label="标题"
              placeholder="留空则使用原值"
              maxlength="100"
              clearable
              :rules="[titleRule]"
              class="mb-2"
            />
            <v-text-field
              v-model="retryForm.year"
              label="年份"
              placeholder="留空则使用原值"
              maxlength="4"
              clearable
              :rules="[yearRule]"
              class="mb-2"
            />
            <v-text-field
              v-if="retryForm.mediaType === 'tv'"
              v-model="retryForm.season"
              label="季"
              placeholder="如 01"
              maxlength="4"
              clearable
              :rules="[seasonRule]"
              class="mb-2"
            />
            <v-text-field
              v-if="retryForm.mediaType === 'tv'"
              v-model="retryForm.episode"
              label="集"
              placeholder="如 05"
              maxlength="6"
              clearable
              :rules="[episodeRule]"
            />
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="retryDialogVisible = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="retryLoading" @click="handleRetrySubmit">确定</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Batch Execute Dialog -->
    <v-dialog v-model="batchDialogVisible" max-width="480" @update:model-value="onBatchDialogUpdate">
      <v-card title="批量重试重命名">
        <v-card-text>
          <div class="batch-tip">
            将按填写的标题与年份重新执行选中的 {{ selectedIds.length }} 条记录（留空则使用原值）
          </div>
          <v-form ref="batchFormRef">
            <v-text-field
              v-model="batchForm.title"
              label="标题"
              placeholder="留空则使用原值"
              maxlength="100"
              clearable
              :rules="[titleRule]"
              class="mb-2"
            />
            <v-text-field
              v-model="batchForm.year"
              label="年份"
              placeholder="留空则使用原值"
              maxlength="4"
              clearable
              :rules="[yearRule]"
            />
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="batchDialogVisible = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="batchLoading" @click="handleBatchSubmit">确定</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Purge Dialog -->
    <v-dialog v-model="purgeDialogVisible" max-width="900">
      <v-card title="清理重命名产物">
        <v-card-text>
          <v-alert type="info" variant="tonal" density="compact" class="mb-3">
            只删除目标库里的副本（STRM / 视频、NFO、图片），并回收变空的目录。
            源目录里的原始文件不会被动——重跑一次任务就能重新生成。
          </v-alert>
          <v-progress-linear v-if="purgePreviewLoading" indeterminate color="primary" class="mb-3" />
          <div v-else-if="!purgeFiles.length" class="purge-empty">磁盘上没有找到对应文件，只需处理数据库记录。</div>
          <template v-else>
            <div class="purge-count">将删除以下 {{ purgeFiles.length }} 个文件：</div>
            <div class="purge-list">
              <div v-for="file in purgeFiles" :key="file" class="purge-item" :title="file">{{ file }}</div>
            </div>
          </template>
          <v-checkbox
            v-model="purgeDeleteRecord"
            label="同时删除重命名记录"
            density="compact"
            hide-details
            class="mt-2"
          />
          <div class="purge-hint">
            {{ purgeDeleteRecord
              ? '记录一并删除，此后一致性检查不再跟踪；下次执行重命名任务会把源文件重新处理一遍。'
              : '记录保留，一致性检查下一轮会把它标成「本地文件丢失」，仍在你的视野里。' }}
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="outlined" @click="purgeDialogVisible = false">取消</v-btn>
          <v-btn color="error" variant="flat" :loading="purgeLoading" :disabled="purgePreviewLoading" @click="handlePurgeSubmit">
            确认清理
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import StatusChip from '@/components/StatusChip.vue'
import { ref } from 'vue'
import { useRenameDetailList } from '@/composables/useRenameDetailList'

const showSearch = ref(window.innerWidth >= 768)

const {
  recordList, loading, total, queryParams,
  getList, queryRef, dateStart, dateEnd, handleQuery, resetQuery,
  selectedIds, multiple, handleSelectionChange,
  handleDeleteOne, handleBatchDelete,
  retryDialogVisible, retryLoading, retryFormRef, retryForm,
  handleRetryOne, handleRetryClose, handleRetrySubmit,
  batchDialogVisible, batchLoading, batchFormRef, batchForm,
  handleBatchExecute, handleBatchClose, handleBatchSubmit,
  handleScrapeOne, handleBatchScrape,
  handleDeleteScrapeOne, handleBatchDeleteScrape,
  purgeDialogVisible, purgeLoading, purgePreviewLoading, purgeFiles, purgeDeleteRecord,
  handlePurgeOne, handleBatchPurge, handlePurgeSubmit
} = useRenameDetailList()

getList()

// 表格最小总宽 = 48(勾选) + 340 + 80 + 80 + 170 + 260 ≈ 978，与 strmRecord/copyRecord 同一量级。
// 「操作」列此前是 460（5 个平铺按钮），把整张表撑到 1248px，1280 宽的屏幕上必然横向溢出——
// 全站其余列表页的操作列上限就是 260，多出来的动作收进「更多」菜单。
// detail 是唯一的弹性列，minWidth 只是下限，窗口变宽时多余空间都归它。
const headers = [
  { title: '重命名详情', key: 'detail', minWidth: '340' },
  { title: '状态', key: 'status', align: 'center' as const, width: '80' },
  { title: '刮削', key: 'scrapeStatus', align: 'center' as const, width: '80' },
  { title: '创建时间', key: 'createTime', width: '170', align: 'center' as const },
  { title: '操作', key: 'actions', align: 'center' as const, width: '260', sortable: false }
]

// v-data-table-server 的多选需要一个本地 ref 承接当前选中的行对象，
// 再转给 useRenameDetailList 的 handleSelectionChange 去派生 selectedIds/multiple
const selectedRows = ref<any[]>([])
const onSelectionChange = (rows: any[]) => {
  selectedRows.value = rows
  handleSelectionChange(rows)
}

const onPageChange = (page: number) => {
  queryParams.pageNum = page
  getList()
}

const onSizeChange = (size: number) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
}

const onRetryDialogUpdate = (val: boolean) => {
  if (!val) handleRetryClose()
}

const onBatchDialogUpdate = (val: boolean) => {
  if (!val) handleBatchClose()
}

// 重试弹窗字段校验，规则与原 retryRules 保持一致
const titleRule = (v: string) => !v || v.length <= 100 || '最多 100 个字符'
const yearRule = (v: string) => !v || /^\d{0,4}$/.test(v) || '年份为 4 位数字'
const seasonRule = (v: string) => !v || /^\d{1,2}$/.test(v) || '季为 1-2 位数字'
const episodeRule = (v: string) => !v || /^\d{1,4}$/.test(v) || '集为 1-4 位数字'
</script>

<style scoped lang="scss">
/* ============================================
   重命名详情列
   ============================================ */
/* 「原 / 新」对照直接复用 list.scss 的 .path-box（copyTask / copyRecord / renameTask 同款），
   不再自造一套左右并排的 .rename-compare —— 那种排法把一行的宽度对半劈，两侧都得省略，
   而竖排两行每行都能吃满列宽，是这张表能收窄到 340px 的前提。
   唯一的私有微调：这两行是「文件名 + 目录」而不是纯路径，文件名比目录重要，
   把公共类的固定 max-width: 200px 换成按列宽分成，窄屏时也留得住后半截目录。 */
.rename-box {
  :deep(.path-name) {
    max-width: 55%;
  }
}

/* ============================================
   Scrape Status
   ============================================ */
.batch-tip {
  font-size: 13px;
  color: var(--osr-text-secondary);
  margin-bottom: 12px;
}

.scrape-none {
  color: var(--osr-text-placeholder);
  font-size: 13px;
}

/* ============================================
   Purge Dialog
   ============================================ */
.purge-count {
  font-size: 13px;
  font-weight: 500;
  color: var(--osr-text-primary);
  margin-bottom: 6px;
}

.purge-list {
  max-height: 280px;
  overflow: auto;
  border: 1px solid var(--osr-border-base);
  border-radius: 6px;
  padding: 8px;
}

.purge-item {
  font-family: monospace;
  font-size: 12px;
  line-height: 1.7;
  color: var(--osr-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.purge-empty,
.purge-hint {
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.purge-hint {
  margin-top: 4px;
}
</style>
