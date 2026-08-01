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
      <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleBatchDelete">
        删记录
      </v-btn>
      <v-btn variant="text" color="warning" size="small" prepend-icon="mdi-refresh" @click="handleBatchScrape">
        刮削
      </v-btn>
      <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleBatchDeleteScrape">
        删刮削
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
          <div class="mobile-status-row">
            <StatusChip :type="record.status === '1' ? 'success' : 'error'" :text="record.status === '1' ? '成功' : '失败'" class="status-tag" />
            <StatusChip v-if="record.scrapeStatus === '1'" type="success" text="NFO" class="scrape-tag" />
            <StatusChip v-else-if="record.scrapeStatus === '2'" type="error" text="刮削失败" class="scrape-tag" />
            <StatusChip v-else-if="record.scrapeStatus === '0'" type="info" text="未刮削" class="scrape-tag" />
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
            <v-btn color="error" block prepend-icon="mdi-delete-outline" @click="handleDeleteOne(actionDrawerTarget); actionDrawerOpen = false">删记录</v-btn>
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
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
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
  queryRef, dateRange, handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  handleDeleteOne, handleBatchDelete,
  retryDialogVisible, retryLoading, retryFormRef, retryForm,
  handleRetryOne, handleRetryClose, handleRetrySubmit,
  handleBatchExecute, handleScrapeOne, handleBatchScrape,
  handleDeleteScrapeOne, handleBatchDeleteScrape
} = useRenameDetailList()

getList()

// dateRange 是 [start, end] 数组结构（从 el-date-picker daterange 迁移而来），
// 拆成两个独立日期输入框绑定，写回时仍保持数组形状供 handleQuery 组装 params
const dateStart = computed({
  get: () => dateRange.value?.[0] ?? '',
  set: (val: string) => {
    dateRange.value = [val || '', dateRange.value?.[1] ?? '']
    if (!dateRange.value[0] && !dateRange.value[1]) dateRange.value = null
  }
})
const dateEnd = computed({
  get: () => dateRange.value?.[1] ?? '',
  set: (val: string) => {
    dateRange.value = [dateRange.value?.[0] ?? '', val || '']
    if (!dateRange.value[0] && !dateRange.value[1]) dateRange.value = null
  }
})

const onRetryDialogUpdate = (val: boolean) => {
  if (!val) handleRetryClose()
}

// 重试弹窗字段校验，规则与原 retryRules 保持一致
const titleRule = (v: string) => !v || v.length <= 100 || '最多 100 个字符'
const yearRule = (v: string) => !v || /^\d{0,4}$/.test(v) || '年份为 4 位数字'
const seasonRule = (v: string) => !v || /^\d{1,2}$/.test(v) || '季为 1-2 位数字'
const episodeRule = (v: string) => !v || /^\d{1,4}$/.test(v) || '集为 1-4 位数字'
</script>

<style scoped lang="scss">
</style>
