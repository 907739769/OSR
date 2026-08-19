<template>
  <!-- 匹配日志 -->
  <v-dialog v-model="searchLogOpen" width="92%">
    <v-card title="匹配日志">
      <v-card-text>
        <v-progress-linear v-if="searchLogLoading" indeterminate color="primary" class="mb-2" />
        <div class="log-toolbar">
          <!-- 翻这张表基本只为了找「这一轮为什么没抓到」，通过的记录会把淘汰原因冲散 -->
          <v-checkbox-btn v-model="searchLogRejectedOnly" label="只看淘汰" />
          <span class="log-count">共 {{ searchLogs.length }} / 显示 {{ visibleSearchLogs.length }}</span>
        </div>
        <div class="log-list">
          <div v-for="(log, idx) in visibleSearchLogs" :key="idx" class="log-item">
            <div class="log-top">
              <span class="log-time">{{ log.createTime }}</span>
              <StatusChip :type="log.source === 'RSS' ? 'info' : 'primary'" :text="log.source === 'RSS' ? 'RSS轮询' : '搜索补集'" />
              <StatusChip v-if="log.accepted === '1'" type="success" text="通过" />
              <StatusChip v-else type="error" text="淘汰" />
            </div>
            <div class="log-title">{{ log.torrentTitle || '-' }}</div>
            <div v-if="log.reason" class="log-reason">{{ log.reason }}</div>
          </div>
          <v-empty-state
            v-if="!searchLogLoading && visibleSearchLogs.length === 0"
            icon="mdi-inbox-outline"
            :title="searchLogRejectedOnly && searchLogs.length ? '没有被淘汰的记录' : '暂无日志'"
          />
        </div>
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
</script>

<style scoped lang="scss">
.log-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.log-count {
  font-size: 11px;
  color: var(--osr-text-secondary);
}
.log-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 60vh;
  overflow-y: auto;
}
.log-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px;
  border-radius: var(--osr-radius-sm);
  background: var(--osr-bg-page);
}
.log-top {
  display: flex;
  align-items: center;
  gap: 6px;

  .log-time {
    flex: 1;
    min-width: 0;
    font-size: 11px;
    color: var(--osr-text-secondary);
  }
}
.log-title {
  font-size: 12px;
  color: var(--osr-text-primary);
  word-break: break-all;
}
.log-reason {
  font-size: 11px;
  color: rgb(var(--v-theme-error));
}
</style>
