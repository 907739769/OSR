<template>
  <!-- 匹配日志 -->
  <v-dialog v-model="searchLogOpen" max-width="600">
    <v-card title="匹配日志">
      <v-card-text>
        <div class="log-toolbar">
          <!-- 翻这张表基本只为了找「这一轮为什么没抓到」，通过的记录会把淘汰原因冲散 -->
          <v-checkbox-btn v-model="searchLogRejectedOnly" label="只看淘汰" />
          <span class="log-count">共 {{ searchLogs.length }} 条，显示 {{ visibleSearchLogs.length }} 条</span>
        </div>
        <v-data-table
          :loading="searchLogLoading"
          :items="visibleSearchLogs"
          :headers="searchLogHeaders"
          height="420"
          fixed-header
          items-per-page="-1"
          hide-default-footer
          density="compact"
          class="modern-table"
        >
          <template #item.source="{ item }">
            <StatusChip :type="item.source === 'RSS' ? 'info' : 'primary'" :text="item.source === 'RSS' ? 'RSS轮询' : '搜索补集'" />
          </template>
          <template #item.torrentTitle="{ item }">{{ item.torrentTitle || '-' }}</template>
          <template #item.accepted="{ item }">
            <StatusChip v-if="item.accepted === '1'" type="success" text="通过" />
            <StatusChip v-else type="error" text="淘汰" />
          </template>
          <template #item.reason="{ item }">{{ item.reason || '-' }}</template>
        </v-data-table>
        <v-empty-state
          v-if="!searchLogLoading && visibleSearchLogs.length === 0"
          icon="mdi-inbox-outline"
          :title="searchLogRejectedOnly && searchLogs.length ? '没有被淘汰的记录' : '暂无日志'"
          :text="searchLogRejectedOnly && searchLogs.length ? '最近 100 条里每一条都通过了过滤' : '还没轮询/搜索过，或该订阅日志已被清理'"
        />
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="outlined" @click="searchLogOpen = false">关闭</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { usePtSubscriptionContext } from '@/composables/ptSubscriptionContext'

const {
  searchLogLoading,
  searchLogOpen,
  searchLogRejectedOnly,
  searchLogs,
  visibleSearchLogs
} = usePtSubscriptionContext()

const searchLogHeaders = [
  { title: '时间', key: 'createTime', sortable: false, width: 160 },
  { title: '触发方式', key: 'source', sortable: false, width: 100 },
  { title: '种子标题', key: 'torrentTitle', sortable: false, minWidth: '200' },
  { title: '结果', key: 'accepted', sortable: false, width: 80 },
  { title: '原因', key: 'reason', sortable: false, minWidth: '180' }
]
</script>

<style scoped lang="scss">
.log-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.log-count {
  font-size: 12px;
  color: var(--osr-text-secondary);
}
</style>
