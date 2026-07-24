<template>
  <div class="mobile-page">
    <!-- 搜索 -->
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <el-form ref="queryRef" :model="queryParams" label-width="72px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="queryParams.title" placeholder="请输入种子标题" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="状态" prop="state">
          <el-select v-model="queryParams.state" placeholder="全部状态" clearable style="width: 100%">
            <el-option label="已推送" value="PUSHED" />
            <el-option label="下载中" value="DOWNLOADING" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="失败" value="FAILED" />
          </el-select>
        </el-form-item>
      </el-form>
    </MobileSearchPanel>

    <!-- 列表 -->
    <div class="task-list" v-loading="loading">
      <div v-for="item in taskList" :key="item.id" class="task-card">
        <div class="card-content">
          <div class="card-top">
            <span class="task-name">{{ item.title }}</span>
            <el-tag :type="stateTagType(item.state)" size="small" effect="light">
              {{ stateLabel(item.state) }}
            </el-tag>
          </div>
          <div class="card-sub">
            {{ item.subTitle || '订阅已删除' }}
            <span v-if="item.episodeLabel">· {{ item.episodeLabel }}</span>
          </div>
          <el-progress
            v-if="item.state === 'DOWNLOADING' || item.state === 'COMPLETED'"
            :percentage="Math.round((item.progress || 0) * 100)"
            :status="item.state === 'COMPLETED' ? 'success' : undefined"
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
          </div>
          <div class="card-fail" v-if="item.state === 'FAILED'">
            <el-icon><WarningFilled /></el-icon>
            <el-tag v-if="item.failReasonCode" size="small" :type="failReasonTagType(item.failReasonCode)">
              {{ failReasonCodeLabel(item.failReasonCode) }}
            </el-tag>
            <span>{{ item.failReason || '未知原因' }}</span>
          </div>
        </div>
        <div class="card-actions" v-if="item.state === 'FAILED'">
          <el-button link type="primary" size="small" :loading="retryingIds.has(item.id)" @click="handleRetry(item)">
            重试
          </el-button>
        </div>
      </div>

      <el-empty v-if="!loading && taskList.length === 0" description="暂无下载记录" />
    </div>

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
  </div>
</template>

<script setup lang="ts">
import { WarningFilled } from '@element-plus/icons-vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import { usePtDownloadRecord } from '@/composables/usePtDownloadRecord'

const {
  taskList, loading, total, queryParams, queryRef,
  handleQuery, resetQuery,
  retryingIds, handleRetry,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed
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
.mobile-page {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: calc(100vh - 120px);
  padding-bottom: 8px;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 200px;
  flex: 1;
}

.task-card {
  display: flex;
  gap: 10px;
  background: var(--osr-surface);
  border-radius: var(--osr-radius-lg);
  padding: 12px;
  box-shadow: var(--osr-shadow-base);

  .card-content {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .card-top {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;

    .task-name {
      font-size: 13px;
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

  .card-sub {
    font-size: 12px;
    color: var(--osr-text-secondary);
  }

  .card-detail {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .detail-row {
    display: flex;
    gap: 8px;
    font-size: 12px;
    line-height: 1.6;

    .label {
      flex-shrink: 0;
      width: 68px;
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

  .card-fail {
    display: flex;
    align-items: flex-start;
    gap: 6px;
    padding: 6px 8px;
    border-radius: var(--osr-radius-sm);
    background: var(--el-color-danger-light-9);
    color: var(--el-color-danger);
    font-size: 11px;
    line-height: 1.5;
  }

  .card-actions {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    padding-left: 8px;
    border-left: 1px solid var(--osr-border-light);
  }
}
</style>
