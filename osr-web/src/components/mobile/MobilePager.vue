<template>
  <v-card v-if="total > 0" class="pagination-bar">
    <div class="pagination-info">
      <span class="total-text">共 {{ total }} 条</span>
    </div>
    <div class="pagination-controls">
      <v-btn icon="mdi-chevron-left" variant="text" size="small" :disabled="pageNum <= 1" class="page-btn" @click="$emit('prev')" />
      <div class="page-num-box">
        <span class="current-page">{{ pageNum }}</span>
        <span class="page-divider">/</span>
        <span class="total-pages">{{ totalPages }}</span>
      </div>
      <v-btn icon="mdi-chevron-right" variant="text" size="small" :disabled="pageNum >= totalPages" class="page-btn" @click="$emit('next')" />
      <v-select
        :model-value="pageSize"
        :items="pageSizes"
        density="compact"
        variant="outlined"
        hide-details
        class="page-size-select"
        @update:model-value="onSizeChange"
      />
      <span class="page-size-label">条/页</span>
    </div>
  </v-card>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    pageNum: number
    pageSize: number
    total: number
    totalPages: number
    /** 每页条数档位。默认与 PC 列表页一致；卡片型页面（订阅/下载记录）传 [12, 24, 48] */
    pageSizes?: number[]
  }>(),
  { pageSizes: () => [10, 20, 50] }
)

const emit = defineEmits<{
  prev: []
  next: []
  'update:pageSize': [value: number]
  'size-change': []
}>()

function onSizeChange(value: number) {
  emit('update:pageSize', value)
  emit('size-change')
}
</script>

<style scoped lang="scss">
/* 外观由 v-card 提供，这里只写内部排布 */
.pagination-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  gap: 12px;

  .pagination-info {
    flex-shrink: 0;

    .total-text {
      font-size: 13px;
      font-weight: 600;
      color: var(--osr-text-secondary);
    }
  }

  .pagination-controls {
    display: flex;
    align-items: center;
    gap: 6px;
    flex: 1;
    justify-content: flex-end;

    .page-btn {
      padding: 4px;
    }

    .page-num-box {
      display: flex;
      align-items: center;
      gap: 2px;
      padding: 4px 10px;
      background: var(--osr-bg-page);
      border-radius: var(--osr-radius-sm);
      border: 1px solid var(--osr-border-light);

      .current-page {
        font-size: 16px;
        font-weight: 700;
        color: var(--osr-primary);
        line-height: 1;
      }

      .page-divider {
        font-size: 12px;
        color: var(--osr-text-disabled);
        margin: 0 2px;
      }

      .total-pages {
        font-size: 13px;
        color: var(--osr-text-secondary);
        line-height: 1;
      }
    }

    .page-size-select {
      width: 76px;
    }

    .page-size-label {
      font-size: 12px;
      color: var(--osr-text-secondary);
      flex-shrink: 0;
      white-space: nowrap;
    }
  }
}
</style>
