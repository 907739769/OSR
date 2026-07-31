<template>
  <div class="page-container">
    <!-- 搜索 -->
    <v-card class="search-card" v-if="showSearch">
      <v-card-text>
        <v-form ref="queryRef" class="search-form">
          <v-row dense>
            <v-col cols="12" sm="4" md="3">
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
            </v-col>
            <v-col cols="12" sm="4" md="3">
              <v-select
                v-model="queryParams.state"
                :items="stateOptions"
                label="状态"
                placeholder="状态"
                clearable
                density="comfortable"
                variant="outlined"
                hide-details
              />
            </v-col>
            <v-col cols="12" sm="4" md="3" class="search-actions">
              <v-btn color="primary" prepend-icon="mdi-magnify" @click="handleQuery">搜索</v-btn>
              <v-btn variant="outlined" prepend-icon="mdi-refresh" @click="resetQuery">重置</v-btn>
            </v-col>
          </v-row>
        </v-form>
      </v-card-text>
    </v-card>

    <!-- 列表 -->
    <v-card class="table-card">
      <v-card-text>
        <div class="action-bar">
          <div class="action-left">
            <v-btn variant="text" size="small" @click="selectionMode = !selectionMode">
              {{ selectionMode ? '退出批量操作' : '批量操作' }}
            </v-btn>
          </div>
          <v-btn variant="text" size="small" prepend-icon="mdi-filter-outline" @click="showSearch = !showSearch">
            {{ showSearch ? '隐藏搜索' : '显示搜索' }}
          </v-btn>
        </div>

        <div class="batch-toolbar" v-if="selectionMode">
          已选 {{ selectedIds.length }} 项
          <v-btn variant="text" color="primary" size="small" class="batch-retry-btn" :disabled="!selectedIds.length" @click="handleBatchRetry">批量重试</v-btn>
          <v-btn variant="text" size="small" class="batch-cancel-btn" @click="selectionMode = false">取消</v-btn>
        </div>

        <div class="card-grid" v-if="loading && taskList.length === 0">
          <div v-for="n in skeletonCount" :key="n" class="record-card-skeleton">
            <v-skeleton-loader type="article" />
          </div>
        </div>
        <div class="card-grid" v-else>
          <v-progress-linear v-if="loading" indeterminate color="primary" class="list-loading" />
          <div
            v-for="item in taskList"
            :key="item.id"
            class="record-card"
            :class="{ 'record-card--failed': item.state === 'FAILED', selectable: selectionMode && item.state === 'FAILED' }"
            @click="selectionMode && item.state === 'FAILED' && toggleRecordSelect(item)"
          >
            <v-checkbox
              v-if="selectionMode && item.state === 'FAILED'"
              class="record-card-checkbox"
              :model-value="selectedIds.includes(item.id)"
              density="compact"
              hide-details
              @click.stop="toggleRecordSelect(item)"
            />
            <div class="record-header">
              <span class="record-title" :title="item.title">{{ item.title }}</span>
              <v-chip :color="stateTagType(item.state)" size="small" variant="tonal">{{ stateLabel(item.state) }}</v-chip>
            </div>
            <div class="record-sub">
              <router-link
                v-if="item.subId"
                :to="{ path: '/openlist/ptSubscription', query: { id: item.subId } }"
                class="record-sub-link"
              >
                {{ item.subTitle || '订阅已删除' }}
              </router-link>
              <span v-else>{{ item.subTitle || '订阅已删除' }}</span>
              <span v-if="item.episodeLabel" class="record-episode">· {{ item.episodeLabel }}</span>
            </div>
            <v-progress-linear
              v-if="item.state === 'DOWNLOADING' || item.state === 'COMPLETED'"
              :model-value="Math.round((item.progress || 0) * 100)"
              :color="item.state === 'COMPLETED' ? 'success' : 'primary'"
              height="6"
              rounded
            />
            <div class="record-row">
              <span class="label">来源索引器</span>
              <span class="value">{{ item.indexerName || '-' }}</span>
            </div>
            <div class="record-row">
              <span class="label">下载器</span>
              <span class="value">{{ item.downloaderName || '-' }}</span>
            </div>
            <div class="record-row">
              <span class="label">体积 / 做种</span>
              <span class="value">{{ formatSize(item.size) }} / {{ item.seeders ?? '-' }}</span>
            </div>
            <div class="record-row">
              <span class="label">推送时间</span>
              <span class="value">{{ item.pushedTime || '-' }}</span>
            </div>
            <div class="record-row" v-if="item.state === 'COMPLETED'">
              <span class="label">完成时间</span>
              <span class="value">{{ item.completedTime || '-' }}</span>
            </div>
            <div class="record-fail" v-if="item.state === 'FAILED'">
              <v-icon icon="mdi-alert-circle" size="16" />
              <v-chip v-if="item.failReasonCode" size="small" :color="failReasonTagType(item.failReasonCode)" variant="tonal">
                {{ failReasonCodeLabel(item.failReasonCode) }}
              </v-chip>
              <span>{{ item.failReason || '未知原因' }}</span>
            </div>
            <div class="record-actions">
              <v-btn
                v-if="item.state === 'FAILED'"
                variant="text"
                color="primary"
                size="small"
                :loading="retryingIds.has(item.id)"
                @click="handleRetry(item)"
              >
                立即重试
              </v-btn>
              <v-btn
                variant="text"
                color="warning"
                size="small"
                class="blacklist-guid-btn"
                :loading="blacklistingIds.has(item.id)"
                @click="handleBlacklistGuid(item)"
              >
                拉黑该种子
              </v-btn>
              <v-btn
                variant="text"
                color="error"
                size="small"
                class="blacklist-group-btn"
                :loading="blacklistingIds.has(item.id)"
                @click="handleBlacklistReleaseGroup(item)"
              >
                拉黑该发布组
              </v-btn>
            </div>
          </div>
          <v-empty-state v-if="!loading && taskList.length === 0" icon="mdi-inbox-outline" title="暂无下载记录" />
        </div>

        <div class="pagination-wrapper">
          <v-pagination
            v-model="queryParams.pageNum"
            :length="Math.ceil(total / queryParams.pageSize) || 1"
            density="comfortable"
            @update:model-value="getList"
          />
          <v-select
            :model-value="queryParams.pageSize"
            :items="[12, 24, 48]"
            label="每页条数"
            density="compact"
            variant="outlined"
            hide-details
            class="page-size-select"
            @update:model-value="onSizeChange"
          />
        </div>
      </v-card-text>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { usePtDownloadRecord } from '@/composables/usePtDownloadRecord'

const showSearch = ref(window.innerWidth >= 768)

const skeletonCount = ref(6)

function updateSkeletonCount() {
  const cardMinWidth = 320 + 14
  const containerWidth = window.innerWidth - 32 - 32
  skeletonCount.value = Math.max(3, Math.min(12, Math.floor(containerWidth / cardMinWidth)))
}

onMounted(() => { updateSkeletonCount(); window.addEventListener('resize', updateSkeletonCount) })
onUnmounted(() => { window.removeEventListener('resize', updateSkeletonCount) })

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  retryingIds, handleRetry,
  selectionMode, selectedIds, toggleRecordSelect, handleBatchRetry,
  blacklistingIds, handleBlacklistGuid, handleBlacklistReleaseGroup
} = usePtDownloadRecord()

const stateOptions = [
  { title: '已推送', value: 'PUSHED' },
  { title: '下载中', value: 'DOWNLOADING' },
  { title: '已完成', value: 'COMPLETED' },
  { title: '失败', value: 'FAILED' }
]

const onSizeChange = (size: number) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
}

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
.page-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.search-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);
}

.search-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.table-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);
}

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
  gap: 10px;
}

.batch-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: var(--osr-radius-sm);
  background: var(--osr-bg-page);
  font-size: 13px;
  color: var(--osr-text-secondary);
  position: sticky;
  top: 0;
  z-index: 2;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
  margin-top: auto;
  padding-top: 12px;

  .page-size-select {
    width: 110px;
    flex: none;
  }
}

.list-loading {
  grid-column: 1 / -1;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 14px;
  min-height: 120px;
}

.record-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
  transition: box-shadow var(--osr-transition-fast), border-color var(--osr-transition-fast);

  &:hover {
    box-shadow: var(--osr-shadow-md);
    border-color: var(--osr-border-base);
  }

  &.selectable {
    cursor: pointer;
    &:hover {
      border-color: var(--osr-primary-light-5);
    }
  }
}

.record-card-checkbox {
  position: absolute;
  top: 4px;
  left: 4px;
  z-index: 1;
}

.record-card--failed {
  border-left: 3px solid var(--osr-danger);
}

.record-card-skeleton {
  padding: 14px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
}

.record-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;

  .record-title {
    flex: 1;
    min-width: 0;
    font-size: 15px;
    font-weight: 600;
    color: var(--osr-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    line-height: 1.4;
  }
}

.record-sub {
  font-size: 12px;
  color: var(--osr-text-secondary);

  .record-episode {
    margin-left: 2px;
  }
}

.record-sub-link {
  color: var(--osr-primary);
  text-decoration: none;
  &:hover {
    text-decoration: underline;
    color: var(--osr-primary-light-3);
  }
}

.record-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;

  .label {
    flex-shrink: 0;
    width: 58px;
    color: var(--osr-text-secondary);
  }

  .value {
    flex: 1;
    min-width: 0;
    color: var(--osr-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.record-fail {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--osr-radius-sm);
  background: var(--osr-danger-light);
  color: var(--osr-danger);
  font-size: 12px;
  font-weight: 500;
  line-height: 1.5;
}

.record-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 6px;
  border-top: 1px solid var(--osr-border-light);
}

@media (max-width: 768px) {
  .page-container {
    gap: 10px;
  }

  .action-bar {
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 10px;

    .action-left {
      gap: 4px;
    }
  }

  .card-grid {
    grid-template-columns: 1fr;
  }

  .record-card-checkbox {
    left: 0;
    top: 0;
  }
}
</style>
