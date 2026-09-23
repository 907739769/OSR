<template>
  <v-card class="chart-card">
    <div class="chart-header">
      <span class="chart-title">最近失败记录</span>
    </div>
    <!-- 加载中必须与「暂无失败记录」分开：那个空态是一个绿色对勾，数据没到时先亮出来，
         读起来就是「系统很干净」 -->
    <div v-if="loading" class="failure-list osr-skeleton" role="status" aria-busy="true" aria-label="加载中">
      <div v-for="i in 5" :key="i" class="failure-item failure-skeleton osr-sheen" :style="{ '--osr-i': i - 1 }">
        <span class="osr-bone osr-bone--circle failure-skeleton__icon" />
        <span class="osr-bone failure-skeleton__tag" />
        <span class="osr-bone" :style="{ width: ['52%', '38%', '60%', '44%', '48%'][i - 1] }" />
        <span class="osr-bone osr-bone--caption failure-skeleton__time" />
      </div>
    </div>
    <div v-else-if="!recentFailures.length" class="empty-tip">
      <v-empty-state icon="circle-check" title="暂无失败记录" />
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
import { formatRelativeTime } from '@/composables/relativeTime'

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

const recentFailures = ref<FailureItem[]>([])
const loading = ref(true)

async function loadRecentFailures() {
  const items: FailureItem[] = []
  const strmPath = getRoutePathForComponent('openlist/strmRecord/index')
  const copyPath = getRoutePathForComponent('openlist/copyRecord/index')
  const renamePath = getRoutePathForComponent('openlist/renameDetail/index')

  try {
    const res: any = await getStrmRecordListApi({ pageNum: 1, pageSize: 5, strmStatus: '0' })
    for (const r of res?.records || []) {
      items.push({ type: 'strm', typeLabel: 'STRM', color: 'success', icon: 'video', id: r.strmId, name: r.strmFileName, time: r.createTime, path: strmPath })
    }
  } catch (e) {
    console.error('[Dashboard] Failed to load strm failures:', e)
  }

  try {
    const res: any = await getCopyRecordListApi({ pageNum: 1, pageSize: 5, copyStatus: '0' })
    for (const r of res?.records || []) {
      items.push({ type: 'copy', typeLabel: 'COPY', color: 'primary', icon: 'files', id: r.copyId, name: r.copySrcFileName, time: r.createTime, path: copyPath })
    }
  } catch (e) {
    console.error('[Dashboard] Failed to load copy failures:', e)
  }

  try {
    const res: any = await getRenameDetailListApi({ pageNum: 1, pageSize: 5, status: '0' })
    for (const r of res?.records || []) {
      items.push({ type: 'rename', typeLabel: 'Rename', color: 'warning', icon: 'square-pen', id: r.id, name: r.originalName, time: r.createTime, path: renamePath })
    }
  } catch (e) {
    console.error('[Dashboard] Failed to load rename failures:', e)
  }

  items.sort((a, b) => (a.time < b.time ? 1 : -1))
  recentFailures.value = items.slice(0, 8)
  loading.value = false
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

.failure-skeleton {
  cursor: default;

  &__icon {
    width: 18px;
    height: 18px;
  }

  &__tag {
    width: 44px;
  }

  &__time {
    width: 48px;
    margin-left: auto;
  }
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
