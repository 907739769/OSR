<template>
  <v-card class="chart-card">
    <div class="chart-header">
      <span class="chart-title">最近失败记录</span>
    </div>
    <div v-if="!recentFailures.length" class="empty-tip">
      <v-empty-state icon="mdi-check-circle-outline" title="暂无失败记录" />
    </div>
    <div v-else class="failure-list">
      <div
        v-for="f in recentFailures"
        :key="f.type + '-' + f.id"
        class="failure-item"
        @click="f.path && router.push(f.path)"
      >
        <v-icon :icon="f.icon" size="18" class="failure-icon" :color="f.color" />
        <span class="failure-tag-text">{{ f.typeLabel }}</span>
        <span class="failure-name" :title="f.name">{{ f.name }}</span>
        <span class="failure-time" :title="f.time">{{ formatRelativeTime(f.time) }}</span>
      </div>
    </div>
  </v-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getRoutePathForComponent } from '@/router'
import { getStrmRecordListApi } from '@/api/openlist/strmRecord'
import { getCopyRecordListApi } from '@/api/openlist/copyRecord'
import { getRenameDetailListApi } from '@/api/openlist/renameDetail'

/**
 * 首页的「最近失败记录」：把 STRM / 同步 / 重命名三张表的失败项合成一条时间线。
 * 三个接口只服务这一个区块，连同相对时间格式化一起搬出 desktop.vue。
 */
const router = useRouter()

/* ============================================
   Recent failures (merged from strm/copy/rename)
   ============================================ */

interface FailureItem {
  type: string
  typeLabel: string
  color: string
  icon: string
  id: number | string
  name: string
  time: string
  path: string | null
}

/** 后端时间转相对时间（x 分钟前/小时前/天前），无效值原样返回 */
function formatRelativeTime(time: string): string {
  if (!time) return ''
  const t = new Date(time).getTime()
  if (Number.isNaN(t)) return time
  const min = Math.floor((Date.now() - t) / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  const day = Math.floor(hour / 24)
  if (day < 30) return `${day} 天前`
  return new Date(time).toLocaleDateString('zh-CN')
}

const recentFailures = ref<FailureItem[]>([])

async function loadRecentFailures() {
  const items: FailureItem[] = []
  const strmPath = getRoutePathForComponent('openlist/strmRecord/index')
  const copyPath = getRoutePathForComponent('openlist/copyRecord/index')
  const renamePath = getRoutePathForComponent('openlist/renameDetail/index')

  try {
    const res: any = await getStrmRecordListApi({ pageNum: 1, pageSize: 5, strmStatus: '0' })
    for (const r of res?.records || []) {
      items.push({ type: 'strm', typeLabel: 'STRM', color: 'success', icon: 'mdi-video-outline', id: r.strmId, name: r.strmFileName, time: r.createTime, path: strmPath })
    }
  } catch (e) {
    console.error('[Dashboard] Failed to load strm failures:', e)
  }

  try {
    const res: any = await getCopyRecordListApi({ pageNum: 1, pageSize: 5, copyStatus: '0' })
    for (const r of res?.records || []) {
      items.push({ type: 'copy', typeLabel: 'COPY', color: 'primary', icon: 'mdi-file-multiple-outline', id: r.copyId, name: r.copySrcFileName, time: r.createTime, path: copyPath })
    }
  } catch (e) {
    console.error('[Dashboard] Failed to load copy failures:', e)
  }

  try {
    const res: any = await getRenameDetailListApi({ pageNum: 1, pageSize: 5, status: '0' })
    for (const r of res?.records || []) {
      items.push({ type: 'rename', typeLabel: 'Rename', color: 'warning', icon: 'mdi-pencil-outline', id: r.id, name: r.originalName, time: r.createTime, path: renamePath })
    }
  } catch (e) {
    console.error('[Dashboard] Failed to load rename failures:', e)
  }

  items.sort((a, b) => (a.time < b.time ? 1 : -1))
  recentFailures.value = items.slice(0, 8)
}

onMounted(loadRecentFailures)
</script>

<style scoped lang="scss">
/* 图表卡外观。原先与 .pt-card 写在同一条分组选择器里，PT 概览拆成组件后各留各的 */
.chart-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);
  margin-bottom: 16px;
  transition: box-shadow var(--osr-transition-base);
  height: 100%;

  &:hover {
    box-shadow: var(--osr-shadow-md);
  }
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 20px;
  border-bottom: 1px solid var(--osr-border-light);
  background-color: var(--osr-surface);

  .chart-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--osr-text-primary);
  }

  :deep(.v-tabs) {
    flex: 0 1 auto;
  }
}

/* ============================================
   Recent failures
   ============================================ */
.empty-tip {
  padding: 8px 0;
}

.failure-list {
  padding: 4px 20px 12px;
}

.failure-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--osr-border-light);
  cursor: pointer;

  &:last-child {
    border-bottom: none;
  }

  &:hover .failure-name {
    color: var(--osr-primary);
  }

  .failure-icon {
    flex-shrink: 0;
  }

  .failure-tag-text {
    flex-shrink: 0;
    font-size: 11px;
    font-weight: 600;
    color: var(--osr-text-secondary);
  }

  .failure-name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 13px;
    color: var(--osr-text-primary);
    transition: color var(--osr-transition-base);
  }

  .failure-time {
    flex-shrink: 0;
    font-size: 12px;
    color: var(--osr-text-secondary);
  }
}
</style>
