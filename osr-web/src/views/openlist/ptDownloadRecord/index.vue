<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-history"
      title="PT 下载记录"
      desc="每次推送下载器的记录与结果，失败项可重试或拉黑"
    />

    <!-- 搜索 -->
    <v-card class="search-card" v-if="showSearch">
      <v-form ref="queryRef" @submit.prevent="handleQuery">
        <div class="search-fields">
          <v-text-field
            v-model="queryParams.title"
            label="标题"
            placeholder="请输入种子标题"
            clearable
            density="compact"
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
            density="compact"
            variant="outlined"
            hide-details
            class="status-select"
          />
          <div class="search-actions">
            <v-btn color="primary" prepend-icon="mdi-magnify" @click="handleQuery">搜索</v-btn>
            <v-btn variant="outlined" prepend-icon="mdi-refresh" @click="resetQuery">重置</v-btn>
          </div>
        </div>
      </v-form>
    </v-card>

    <!-- 列表 -->
    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <v-btn variant="text" size="small" class="selection-mode-btn" @click="toggleSelectionMode">
            {{ selectionMode ? '退出批量操作' : '批量操作' }}
          </v-btn>
        </div>
        <v-btn variant="text" size="small" prepend-icon="mdi-filter-outline" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <div class="batch-toolbar" v-if="selectionMode">
        已选 {{ selectedIds.length }} 项
        <!-- 重试只对失败记录成立，按钮上直接标出生效条数，免得点完才发现大半被跳过 -->
        <v-btn
          variant="text"
          color="primary"
          size="small"
          class="batch-retry-btn"
          :disabled="!retryableSelectedIds.length"
          @click="handleBatchRetry"
        >
          批量重试{{ retryableSelectedIds.length ? `（${retryableSelectedIds.length} 条失败）` : '' }}
        </v-btn>
        <v-btn variant="text" color="warning" size="small" class="batch-blacklist-guid-btn" :disabled="!selectedIds.length" @click="handleBatchBlacklistGuid">批量拉黑种子</v-btn>
        <v-btn variant="text" color="error" size="small" class="batch-blacklist-group-btn" :disabled="!selectedIds.length" @click="handleBatchBlacklistReleaseGroup">批量拉黑发布组</v-btn>
        <v-btn variant="text" size="small" class="batch-select-all-btn" @click="toggleSelectAllPage(!isAllPageSelected)">
          {{ isAllPageSelected ? '取消全选' : '全选' }}
        </v-btn>
        <v-btn variant="text" size="small" class="batch-cancel-btn" @click="toggleSelectionMode">取消</v-btn>
      </div>

      <!-- 两个分支都要挂 gridRef：每页条数按网格实际列数取整到整行，骨架屏阶段就得量得到 -->
      <div class="card-grid" ref="gridRef" v-if="loading && taskList.length === 0">
        <div v-for="n in skeletonCount" :key="n" class="item-card-skeleton">
          <v-skeleton-loader type="article" />
        </div>
      </div>
      <div class="card-grid" ref="gridRef" v-else>
        <v-progress-linear v-if="loading" indeterminate color="primary" class="list-loading" />
        <div
          v-for="item in taskList"
          :key="item.id"
          class="item-card item-card--compact"
          :class="{
            'item-card--failed': item.state === 'FAILED',
            'item-card--selectable': selectionMode
          }"
          @click="selectionMode && handleCardClick($event, item.id)"
        >
          <v-checkbox
            v-if="selectionMode"
            class="item-card-checkbox card-checkbox"
            :model-value="selectedIds.includes(item.id)"
            density="compact"
            hide-details
            @click.stop="toggleRecordSelect(item)"
          />
          <div class="card-header card-header--top">
            <span class="card-title card-title--clamp2" :title="item.title">{{ item.title }}</span>
            <StatusChip :type="stateTagType(item.state)" :text="stateLabel(item.state)" />
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
          <div class="card-row">
            <span class="label">来源索引器</span>
            <span class="value">{{ item.indexerName || '-' }}</span>
          </div>
          <div class="card-row">
            <span class="label">下载器</span>
            <span class="value">{{ item.downloaderName || '-' }}</span>
          </div>
          <div class="card-row">
            <span class="label">体积 / 做种</span>
            <span class="value">{{ formatSize(item.size) }} / {{ item.seeders ?? '-' }}</span>
          </div>
          <div class="card-row">
            <span class="label">推送时间</span>
            <span class="value">{{ item.pushedTime || '-' }}</span>
          </div>
          <div class="card-row" v-if="item.state === 'COMPLETED'">
            <span class="label">完成时间</span>
            <span class="value">{{ item.completedTime || '-' }}</span>
          </div>
          <div class="card-row" v-if="item.hrState">
            <span class="label">H&amp;R 保种</span>
            <span class="value hr-value">
              <StatusChip :type="hrTagType(item.hrState)" :text="hrStateLabel(item.hrState)" />
              <span class="hr-progress">{{ hrProgress(item) }}</span>
            </span>
          </div>
          <div class="record-fail" v-if="item.state === 'FAILED'">
            <v-icon icon="mdi-alert-circle" size="16" />
            <StatusChip v-if="item.failReasonCode" :type="failReasonTagType(item.failReasonCode)" :text="failReasonCodeLabel(item.failReasonCode)" />
            <span>{{ item.failReason || '未知原因' }}</span>
          </div>
          <div class="card-footer">
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
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import StatusChip from '@/components/StatusChip.vue'
import { ref, onMounted, onUnmounted } from 'vue'
import { usePtDownloadRecord } from '@/composables/usePtDownloadRecord'
import { useGridPageSize } from '@/composables/useGridPageSize'

const showSearch = ref(window.innerWidth >= 768)

const skeletonCount = ref(6)

function updateSkeletonCount() {
  const cardMinWidth = 320 + 14
  const containerWidth = window.innerWidth - 32 - 32
  skeletonCount.value = Math.max(3, Math.min(12, Math.floor(containerWidth / cardMinWidth)))
}

onMounted(() => { updateSkeletonCount(); window.addEventListener('resize', updateSkeletonCount) })
onUnmounted(() => { window.removeEventListener('resize', updateSkeletonCount) })

// 首次加载交给 useGridPageSize（autoLoad: false）：每页条数要按网格实际列数取整到整行，
// 列数得挂载后才量得准，先按兜底值发一次再改口是白跑一趟请求
const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  retryingIds, handleRetry,
  selectionMode, toggleSelectionMode, selectedIds, toggleRecordSelect, handleCardClick,
  isAllPageSelected, toggleSelectAllPage,
  retryableSelectedIds, handleBatchRetry,
  handleBatchBlacklistGuid, handleBatchBlacklistReleaseGroup,
  blacklistingIds, handleBlacklistGuid, handleBlacklistReleaseGroup
} = usePtDownloadRecord({ autoLoad: false })

// 每页条数按网格实际列数取整到整行，窗口宽度变了跟着重算
const { gridRef, pageSizeOptions, setPageSize } = useGridPageSize((size) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
})

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

const hrStateLabel = (state: string) => {
  switch (state) {
    case 'PENDING': return '保种中'
    case 'SATISFIED': return '已达标'
    case 'VIOLATED': return '可能已 H&R'
    default: return state
  }
}
const hrTagType = (state: string): 'success' | 'warning' | 'error' => {
  switch (state) {
    case 'SATISFIED': return 'success'
    case 'VIOLATED': return 'error'
    default: return 'warning'
  }
}

/**
 * 保种进度摘要。要求是「做满 N 小时 或 分享率达到 R」的或关系，两项都展示，
 * 让用户自己看哪一项先够；站点没配的那一项（阈值 0）不显示目标值。
 */
const hrProgress = (item: any) => {
  const hours = ((item.hrSeedSeconds ?? 0) / 3600).toFixed(1)
  const ratio = (item.hrRatio ?? 0).toFixed(2)
  const parts: string[] = []
  parts.push(item.hrSeedHoursRequired > 0 ? `做种 ${hours}/${item.hrSeedHoursRequired}h` : `做种 ${hours}h`)
  parts.push(item.hrRatioRequired > 0 ? `分享率 ${ratio}/${item.hrRatioRequired}` : `分享率 ${ratio}`)
  return parts.join('，')
}

const failReasonCodeLabel = (code: string) => {
  switch (code) {
    case 'TORRENT_NOT_FOUND': return '种子丢失'
    case 'ZOMBIE_TIMEOUT': return '下载超时'
    case 'NO_TARGET_EPISODE': return '无目标集'
    case 'METADATA_TIMEOUT': return '种子无响应'
    default: return '其他原因'
  }
}
// 这三类都不是错误：超时、包选错、种子没人做种，占位集都已退回并会继续搜索，不该用红色吓人
const WARNING_FAIL_CODES = ['ZOMBIE_TIMEOUT', 'NO_TARGET_EPISODE', 'METADATA_TIMEOUT']
const failReasonTagType = (code: string): 'warning' | 'error' => {
  return WARNING_FAIL_CODES.includes(code) ? 'warning' : 'error'
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
.list-loading {
  grid-column: 1 / -1;
}

.item-card-checkbox {
  position: absolute;
  top: 4px;
  left: 4px;
  z-index: 1;
}

.item-card-skeleton {
  padding: 14px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
}

/* 所属订阅 */
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
  }
}

/* 失败原因块 */
/* 保种状态徽章与进度文字同行，窄屏时进度换到下一行而不是把徽章挤变形 */
.hr-value {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.hr-progress {
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.record-fail {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--osr-radius-sm);
  background: rgba(var(--v-theme-error), 0.1);
  color: rgb(var(--v-theme-error));
  font-size: 12px;
  font-weight: 500;
  line-height: 1.5;
}

@media (max-width: 768px) {
  .item-card-checkbox {
    left: 0;
    top: 0;
  }
}
</style>
