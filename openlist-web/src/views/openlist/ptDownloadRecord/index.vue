<template>
  <div class="page-container">
    <!-- 搜索 -->
    <el-card class="search-card" v-if="showSearch">
      <el-form :model="queryParams" ref="queryRef" :inline="true" label-width="80px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="queryParams.title" placeholder="请输入种子标题" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="状态" prop="state">
          <el-select v-model="queryParams.state" placeholder="状态" clearable :style="{ width: '140px' }">
            <el-option label="已推送" value="PUSHED" />
            <el-option label="下载中" value="DOWNLOADING" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="失败" value="FAILED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleQuery">
            <el-icon><Search /></el-icon> 搜索
          </el-button>
          <el-button @click="resetQuery">
            <el-icon><Refresh /></el-icon> 重置
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 列表 -->
    <el-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <el-button text @click="selectionMode = !selectionMode">
            {{ selectionMode ? '退出批量操作' : '批量操作' }}
          </el-button>
        </div>
        <el-button text @click="showSearch = !showSearch">
          <el-icon><Filter /></el-icon>
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </el-button>
      </div>

      <div class="batch-toolbar" v-if="selectionMode">
        已选 {{ selectedIds.length }} 项
        <el-button link type="primary" class="batch-retry-btn" :disabled="!selectedIds.length" @click="handleBatchRetry">批量重试</el-button>
        <el-button link class="batch-cancel-btn" @click="selectionMode = false">取消</el-button>
      </div>

      <div class="card-grid" v-if="loading && taskList.length === 0">
        <div v-for="n in skeletonCount" :key="n" class="record-card-skeleton">
          <el-skeleton animated>
            <template #template>
              <el-skeleton-item variant="text" style="width: 60%; height: 16px; margin-bottom: 10px" />
              <el-skeleton-item variant="text" style="width: 40%; margin-bottom: 10px" />
              <el-skeleton-item variant="text" style="width: 100%; margin-bottom: 6px" />
              <el-skeleton-item variant="text" style="width: 100%; margin-bottom: 6px" />
              <el-skeleton-item variant="text" style="width: 80%" />
            </template>
          </el-skeleton>
        </div>
      </div>
      <div class="card-grid" v-else v-loading="loading">
        <div
          v-for="item in taskList"
          :key="item.id"
          class="record-card"
          :class="{ 'record-card--failed': item.state === 'FAILED', selectable: selectionMode && item.state === 'FAILED' }"
          @click="selectionMode && item.state === 'FAILED' && toggleRecordSelect(item)"
        >
          <el-checkbox
            v-if="selectionMode && item.state === 'FAILED'"
            class="record-card-checkbox"
            :model-value="selectedIds.includes(item.id)"
            @change="toggleRecordSelect(item)"
            @click.stop
          />
          <div class="record-header">
            <span class="record-title" :title="item.title">{{ item.title }}</span>
            <el-tag :type="stateTagType(item.state)" size="small">{{ stateLabel(item.state) }}</el-tag>
          </div>
          <div class="record-sub">
            {{ item.subTitle || '订阅已删除' }}
            <span v-if="item.episodeLabel" class="record-episode">· {{ item.episodeLabel }}</span>
          </div>
          <el-progress
            v-if="item.state === 'DOWNLOADING' || item.state === 'COMPLETED'"
            :percentage="Math.round((item.progress || 0) * 100)"
            :status="item.state === 'COMPLETED' ? 'success' : undefined"
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
            <el-icon><WarningFilled /></el-icon>
            <el-tag v-if="item.failReasonCode" size="small" :type="failReasonTagType(item.failReasonCode)">
              {{ failReasonCodeLabel(item.failReasonCode) }}
            </el-tag>
            <span>{{ item.failReason || '未知原因' }}</span>
          </div>
          <div class="record-actions" v-if="item.state === 'FAILED'">
            <el-button
              link
              type="primary"
              :loading="retryingIds.has(item.id)"
              @click="handleRetry(item)"
            >
              立即重试
            </el-button>
          </div>
        </div>
        <el-empty v-if="!loading && taskList.length === 0" description="暂无下载记录" />
      </div>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="queryParams.pageNum"
          v-model:page-size="queryParams.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="getList"
          @size-change="getList"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { WarningFilled } from '@element-plus/icons-vue'
import { usePtDownloadRecord } from '@/composables/usePtDownloadRecord'

const showSearch = ref(window.innerWidth >= 768)

/** 骨架屏数量根据页面宽度动态计算 */
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
  selectionMode, selectedIds, toggleRecordSelect, handleBatchRetry
} = usePtDownloadRecord()

const stateLabel = (state: string) => {
  switch (state) {
    case 'PUSHED': return '已推送'
    case 'DOWNLOADING': return '下载中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    default: return state
  }
}

const stateTagType = (state: string): 'success' | 'warning' | 'danger' | 'info' => {
  switch (state) {
    case 'COMPLETED': return 'success'
    case 'DOWNLOADING': return 'warning'
    case 'FAILED': return 'danger'
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
const failReasonTagType = (code: string): 'warning' | 'danger' => {
  return code === 'ZOMBIE_TIMEOUT' ? 'warning' : 'danger'
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

  :deep(.el-card__body) {
    padding: 14px 16px;
  }
}

.table-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);

  :deep(.el-card__body) {
    padding: 16px;
    display: flex;
    flex-direction: column;
  }
}

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
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
  margin-top: auto;
  padding-top: 12px;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 480px));
  gap: 14px;
  min-height: 120px;
}

.record-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px;
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
  top: 10px;
  right: 10px;
  z-index: 1;
}

.record-card--failed {
  border-left: 3px solid var(--osr-danger);
  padding-left: 13px;
}

.record-card-skeleton {
  --el-skeleton-color: var(--osr-border-light);
  --el-skeleton-to-color: var(--osr-bg-page);
  padding: 14px 16px;
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
    font-size: 14px;
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

.record-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;

  .label {
    flex-shrink: 0;
    width: 78px;
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

  .el-icon {
    font-size: 16px;
    flex-shrink: 0;
  }
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

  .search-card :deep(.el-form) {
    .el-form-item {
      margin-right: 0;
    }

    .el-input,
    .el-select {
      width: 100% !important;
    }
  }

  .table-card :deep(.el-card__body) {
    padding: 12px;
  }

  .card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
