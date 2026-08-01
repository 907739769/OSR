<template>
  <div class="mobile-page">
    <!-- 搜索 -->
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <v-form ref="queryRef">
        <v-text-field
          v-model="queryParams.title"
          label="标题"
          placeholder="请输入种子标题"
          clearable
          density="comfortable"
          variant="outlined"
          hide-details
          @keyup.enter="handleQuery"
        />
        <v-select
          v-model="queryParams.state"
          :items="stateOptions"
          label="状态"
          placeholder="全部状态"
          clearable
          density="comfortable"
          variant="outlined"
          hide-details
        />
      </v-form>
    </MobileSearchPanel>

    <!-- 批量选择 -->
    <div class="list-toolbar">
      <v-btn variant="text" size="small" class="batch-toggle-btn" @click="selectionMode = !selectionMode">
        {{ selectionMode ? '退出批量操作' : '批量操作' }}
      </v-btn>
    </div>

    <div class="batch-bar" v-if="selectionMode">
      <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
      <v-btn variant="text" color="primary" size="small" class="batch-retry-btn" :disabled="!selectedIds.length" @click="handleBatchRetry">批量重试</v-btn>
      <v-btn variant="text" size="small" @click="selectionMode = false">取消</v-btn>
    </div>

    <!-- 列表 -->
    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" class="list-loading" />
      <v-card
        v-for="item in taskList"
        :key="item.id"
        class="task-card"
        :class="{ selected: selectionMode && item.state === 'FAILED' && selectedIds.includes(item.id) }"
      >
        <div class="card-checkbox" v-if="selectionMode && item.state === 'FAILED'">
          <v-checkbox
            :model-value="selectedIds.includes(item.id)"
            density="compact"
            hide-details
            @click.stop="toggleRecordSelect(item)"
          />
        </div>
        <div class="card-content">
          <div class="card-top">
            <span class="card-title">{{ item.title }}</span>
            <v-chip :color="stateTagType(item.state)" size="small" variant="tonal">
              {{ stateLabel(item.state) }}
            </v-chip>
          </div>
          <div class="card-sub">
            <router-link
              v-if="item.subId"
              :to="{ path: '/openlist/ptSubscription', query: { id: item.subId } }"
              class="card-sub-link"
              @click.stop
            >
              {{ item.subTitle || '订阅已删除' }}
            </router-link>
            <span v-else>{{ item.subTitle || '订阅已删除' }}</span>
            <span v-if="item.episodeLabel">· {{ item.episodeLabel }}</span>
          </div>
          <v-progress-linear
            v-if="item.state === 'DOWNLOADING' || item.state === 'COMPLETED'"
            :model-value="Math.round((item.progress || 0) * 100)"
            :color="item.state === 'COMPLETED' ? 'success' : 'primary'"
            height="6"
            rounded
          />
          <div class="card-detail">
            <div class="detail-row">
              <span class="label">索引器</span>
              <span class="value">{{ item.indexerName || '-' }}</span>
            </div>
            <div class="detail-row">
              <span class="label">下载器</span>
              <span class="value">{{ item.downloaderName || '-' }}</span>
            </div>
            <div class="detail-row">
              <span class="label">体积/做种</span>
              <span class="value">{{ formatSize(item.size) }} / {{ item.seeders ?? '-' }}</span>
            </div>
            <div class="detail-row">
              <span class="label">推送时间</span>
              <span class="value">{{ item.pushedTime || '-' }}</span>
            </div>
            <div class="detail-row" v-if="item.state === 'COMPLETED'">
              <span class="label">完成时间</span>
              <span class="value">{{ item.completedTime || '-' }}</span>
            </div>
          </div>
          <div class="card-fail" v-if="item.state === 'FAILED'">
            <v-icon icon="mdi-alert-circle" size="16" />
            <v-chip v-if="item.failReasonCode" size="small" :color="failReasonTagType(item.failReasonCode)" variant="tonal">
              {{ failReasonCodeLabel(item.failReasonCode) }}
            </v-chip>
            <span>{{ item.failReason || '未知原因' }}</span>
          </div>
          <div class="card-actions" @click.stop>
            <v-btn
              v-if="item.state === 'FAILED'"
              variant="text"
              color="primary"
              size="small"
              prepend-icon="mdi-refresh"
              :loading="retryingIds.has(item.id)"
              @click="handleRetry(item)"
            >
              重试
            </v-btn>
            <v-btn
              class="action-more"
              variant="text"
              color="default"
              size="small"
              icon="mdi-dots-horizontal"
              @click="openActionDrawer(item)"
            />
          </div>
        </div>
      </v-card>

      <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无下载记录" />
    </div>

    <!-- 操作抽屉 -->
    <v-bottom-sheet v-model="actionDrawerOpen">
      <v-card v-if="actionDrawerTarget" title="更多操作">
        <v-card-text>
          <div class="drawer-actions">
            <v-btn
              color="warning"
              block
              prepend-icon="mdi-cancel"
              :loading="blacklistingIds.has(actionDrawerTarget.id)"
              @click="handleBlacklistGuid(actionDrawerTarget); actionDrawerOpen = false"
            >
              拉黑该种子
            </v-btn>
            <v-btn
              color="error"
              block
              prepend-icon="mdi-account-cancel-outline"
              :loading="blacklistingIds.has(actionDrawerTarget.id)"
              @click="handleBlacklistReleaseGroup(actionDrawerTarget); actionDrawerOpen = false"
            >
              拉黑该发布组
            </v-btn>
          </div>
        </v-card-text>
      </v-card>
    </v-bottom-sheet>

    <!-- 分页 -->
    <MobilePager
      v-model:page-size="queryParams.pageSize"
      :page-sizes="[12, 24, 48]"
      :page-num="queryParams.pageNum"
      :total="total"
      :total-pages="totalPages"
      @prev="prevPage"
      @next="nextPage"
      @size-change="handleSizeChange"
    />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtDownloadRecord } from '@/composables/usePtDownloadRecord'

const {
  taskList, loading, total, queryParams, queryRef,
  handleQuery, resetQuery,
  retryingIds, handleRetry,
  selectionMode, selectedIds, toggleRecordSelect, handleBatchRetry,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed,
  blacklistingIds, handleBlacklistGuid, handleBlacklistReleaseGroup
} = usePtDownloadRecord()

/** 更多操作抽屉：拉黑动作不常用，收进抽屉避免卡片按钮过密 */
const actionDrawerOpen = ref(false)
const actionDrawerTarget = ref<any>(null)
const openActionDrawer = (row: any) => {
  actionDrawerTarget.value = row
  actionDrawerOpen.value = true
}

const stateOptions = [
  { title: '已推送', value: 'PUSHED' },
  { title: '下载中', value: 'DOWNLOADING' },
  { title: '已完成', value: 'COMPLETED' },
  { title: '失败', value: 'FAILED' }
]

const stateLabel = (state: string) => {
  switch (state) {
    case 'PUSHED': return '已推送'
    case 'DOWNLOADING': return '下载中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    default: return state
  }
}

const stateTagType = (state: string): 'success' | 'warning' | 'error' | 'info' => {
  switch (state) {
    case 'COMPLETED': return 'success'
    case 'DOWNLOADING': return 'warning'
    case 'FAILED': return 'error'
    default: return 'info'
  }
}

const failReasonCodeLabel = (code: string) => {
  switch (code) {
    case 'TORRENT_NOT_FOUND': return '种子丢失'
    case 'ZOMBIE_TIMEOUT': return '下载超时'
    default: return '其他原因'
  }
}

const failReasonTagType = (code: string): 'warning' | 'error' => {
  return code === 'ZOMBIE_TIMEOUT' ? 'warning' : 'error'
}

const formatSize = (bytes: number): string => {
  if (!bytes) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return (bytes / Math.pow(k, i)).toFixed(2) + ' ' + sizes[i]
}
</script>

<style scoped lang="scss">
.list-toolbar {
  display: flex;
  justify-content: flex-end;
}

.list-loading {
  border-radius: var(--osr-radius-md);
}

.task-card {

  .card-sub {
    font-size: 12px;
    color: var(--osr-text-secondary);

    .card-sub-link {
      color: var(--osr-primary);
      text-decoration: none;
    }
  }

  .card-fail {
    display: flex;
    align-items: flex-start;
    gap: 6px;
    padding: 6px 8px;
    border-radius: var(--osr-radius-sm);
    background: var(--osr-error-light);
    color: var(--osr-error);
    font-size: 11px;
    line-height: 1.5;
  }

}
</style>
