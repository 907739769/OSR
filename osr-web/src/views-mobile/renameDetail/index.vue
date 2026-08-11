<template>
  <div class="mobile-page">
    <!-- 搜索 -->
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <v-form ref="queryRef">
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
          placeholder="全部状态"
          :items="[{ title: '成功', value: '1' }, { title: '失败', value: '0' }]"
          clearable
          density="compact"
          variant="outlined"
          hide-details
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
      </v-form>
    </MobileSearchPanel>

    <!-- Batch Actions -->
    <div class="batch-bar" v-if="selectedIds.length > 0">
      <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
      <v-btn variant="text" color="primary" size="small" prepend-icon="mdi-refresh" @click="handleBatchExecute">
        执行
      </v-btn>
      <v-btn variant="text" color="error" size="small" prepend-icon="mdi-broom" @click="handleBatchPurge">
        清产物
      </v-btn>
      <v-btn variant="text" color="error" size="small" prepend-icon="mdi-database-remove-outline" @click="handleBatchDelete">
        删记录
      </v-btn>
      <v-btn variant="text" color="warning" size="small" prepend-icon="mdi-refresh" @click="handleBatchScrape">
        刮削
      </v-btn>
      <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleBatchDeleteScrape">
        删刮削
      </v-btn>
      <v-btn variant="text" size="small" class="batch-select-all-btn" @click="toggleSelectAllPage(!isAllPageSelected)">
        {{ isAllPageSelected ? '取消全选' : '全选' }}
      </v-btn>
      <v-btn variant="text" size="small" @click="clearSelection">
        取消
      </v-btn>
    </div>

    <!-- Record List -->
    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <v-card
        v-for="record in recordList"
        :key="record.id"
        class="task-card"
        :class="{ selected: selectedIds.includes(record.id) }"
        @click="handleCardClick($event, record.id)"
      >
        <div class="card-checkbox">
          <v-checkbox
            :model-value="selectedIds.includes(record.id)"
            density="compact"
            hide-details
            @click.stop="toggleSelect(record.id)"
          />
        </div>
        <div class="card-content">
          <!-- Rename comparison header -->
          <div class="rename-compare-header">
            <div class="rename-side rename-original-side">
              <span class="rename-label rename-label-original">原</span>
              <span class="rename-filename rename-filename-original" @click.stop="showFullText(record.originalName, '原文件名')" :title="record.originalName">
                {{ record.originalName }}
              </span>
            </div>
            <v-icon class="rename-arrow-icon" icon="mdi-arrow-right" size="16" />
            <div class="rename-side rename-new-side">
              <span class="rename-label rename-label-new">新</span>
              <span class="rename-filename rename-filename-new" @click.stop="showFullText(record.newName, '新文件名')" :title="record.newName">
                {{ record.newName }}
              </span>
            </div>
          </div>
          <!-- Path comparison -->
          <div class="rename-paths">
            <div class="rename-path-item rename-path-original" @click.stop="showFullText(record.originalPath, '原路径')">
              <v-icon class="card-path-icon" icon="mdi-map-marker-outline" size="12" />
              <span class="card-path-text">{{ record.originalPath }}</span>
            </div>
            <v-icon class="rename-path-arrow" icon="mdi-arrow-right" size="12" />
            <div class="rename-path-item rename-path-new" @click.stop="showFullText(record.newPath, '新路径')">
              <v-icon class="card-path-icon" icon="mdi-map-marker-outline" size="12" />
              <span class="card-path-text">{{ record.newPath }}</span>
            </div>
          </div>
          <div class="mobile-status-row">
            <StatusChip :type="record.status === '1' ? 'success' : 'error'" :text="record.status === '1' ? '成功' : '失败'" class="status-tag" />
            <StatusChip v-if="record.scrapeStatus === '1'" type="success" text="NFO" class="scrape-tag" />
            <StatusChip v-else-if="record.scrapeStatus === '2'" type="error" text="刮削失败" class="scrape-tag" />
            <StatusChip v-else-if="record.scrapeStatus === '0'" type="info" text="未刮削" class="scrape-tag" />
          </div>
          <div class="card-time">
            <v-icon icon="mdi-clock-outline" size="12" />
            {{ record.createTime }}
          </div>
          <div class="card-actions" @click.stop>
            <v-btn variant="text" color="warning" size="small" @click="handleScrapeOne(record)">
              刮削
            </v-btn>
            <v-btn variant="text" color="primary" size="small" @click="handleRetryOne(record)">
              重试
            </v-btn>
            <v-btn class="action-more" variant="text" color="default" size="small" icon="mdi-dots-horizontal" @click="openActionDrawer(record)" />
          </div>
        </div>
      </v-card>

      <v-empty-state v-if="!loading && recordList.length === 0" icon="mdi-inbox-outline" title="暂无重命名记录" />
    </div>

    <!-- 操作抽屉 -->
    <v-bottom-sheet v-model="actionDrawerOpen">
      <v-card v-if="actionDrawerTarget" title="更多操作">
        <v-card-text>
          <div class="drawer-actions">
            <v-btn v-if="actionDrawerTarget.scrapeStatus === '1'" color="error" block @click="handleDeleteScrapeOne(actionDrawerTarget); actionDrawerOpen = false">删刮削</v-btn>
            <v-btn color="error" block prepend-icon="mdi-broom" @click="handlePurgeOne(actionDrawerTarget); actionDrawerOpen = false">清理产物</v-btn>
            <v-btn color="error" variant="outlined" block prepend-icon="mdi-database-remove-outline" @click="handleDeleteOne(actionDrawerTarget); actionDrawerOpen = false">仅删记录</v-btn>
          </div>
        </v-card-text>
      </v-card>
    </v-bottom-sheet>

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

    <!-- 全文查看 -->
    <FullTextDialog ref="fullTextRef" />

    <!-- Retry Dialog -->
    <v-dialog v-model="retryDialogVisible" width="92%" @update:model-value="onRetryDialogUpdate">
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
    <v-dialog v-model="batchDialogVisible" width="92%" @update:model-value="onBatchDialogUpdate">
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
    <v-dialog v-model="purgeDialogVisible" width="92%">
      <v-card title="清理重命名产物">
        <v-card-text>
          <v-alert type="info" variant="tonal" density="compact" class="mb-3">
            只删目标库副本（STRM / 视频、NFO、图片）并回收空目录，源文件不动。
          </v-alert>
          <v-progress-linear v-if="purgePreviewLoading" indeterminate color="primary" class="mb-3" />
          <div v-else-if="!purgeFiles.length" class="purge-empty">磁盘上没有找到对应文件，只需处理数据库记录。</div>
          <template v-else>
            <div class="purge-count">将删除以下 {{ purgeFiles.length }} 个文件：</div>
            <div class="purge-list">
              <div v-for="file in purgeFiles" :key="file" class="purge-item" @click="showFullText(file, '待删除文件')">{{ file }}</div>
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
              ? '记录一并删除，此后一致性检查不再跟踪；下次执行任务会重新处理源文件。'
              : '记录保留，一致性检查下一轮会标成「本地文件丢失」。' }}
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
import { ref } from 'vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import FullTextDialog from '@/components/mobile/FullTextDialog.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useRenameDetailList } from '@/composables/useRenameDetailList'

const searchCollapsed = ref(true)

const fullTextRef = ref<InstanceType<typeof FullTextDialog>>()
const showFullText = (content: string, title: string) => fullTextRef.value?.show(content, title)

/** 更多操作抽屉 */
const actionDrawerOpen = ref(false)
const actionDrawerTarget = ref<any>(null)
const openActionDrawer = (row: any) => {
  actionDrawerTarget.value = row
  actionDrawerOpen.value = true
}

const {
  recordList, loading, total, queryParams, totalPages,
  getList, prevPage, nextPage, handleSizeChange,
  queryRef, dateStart, dateEnd, handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  isAllPageSelected, toggleSelectAllPage,
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
.batch-tip {
  font-size: 13px;
  color: var(--osr-text-secondary);
  margin-bottom: 12px;
}

.purge-count {
  font-size: 13px;
  font-weight: 500;
  color: var(--osr-text-primary);
  margin-bottom: 6px;
}

.purge-list {
  max-height: 220px;
  overflow: auto;
  border: 1px solid var(--osr-border-base);
  border-radius: 6px;
  padding: 8px;
}

.purge-item {
  font-family: monospace;
  font-size: 11px;
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

.rename-compare-header {
  display: flex;
  align-items: center;
  gap: 6px;

  .rename-side {
    display: flex;
    align-items: center;
    gap: 4px;
    min-width: 0;
    flex: 1;
  }

  .rename-label {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    flex-shrink: 0;
    border-radius: 4px;
    font-size: 10px;
    font-weight: 700;

    &.rename-label-original {
      background: var(--osr-error-light);
      color: var(--osr-error);
      border: 1px solid color-mix(in srgb, var(--osr-error) 30%, transparent);
    }

    &.rename-label-new {
      background: var(--osr-success-light);
      color: var(--osr-success);
      border: 1px solid color-mix(in srgb, var(--osr-success) 30%, transparent);
    }
  }

  .rename-filename {
    font-size: 13px;
    font-weight: 500;
    color: var(--osr-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .rename-arrow-icon {
    flex-shrink: 0;
    color: var(--osr-text-disabled);
  }
}

.rename-paths {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;

  .rename-path-item {
    display: flex;
    align-items: center;
    gap: 3px;
    min-width: 0;
    flex: 1;
    cursor: pointer;

    .card-path-icon {
      flex-shrink: 0;
    }

    .card-path-text {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      display: block;
      -webkit-line-clamp: unset;
    }

    &:hover .card-path-text {
      color: var(--osr-primary);
    }
  }

  .rename-path-arrow {
    flex-shrink: 0;
    color: var(--osr-text-disabled);
  }
}
</style>
