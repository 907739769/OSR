<template>
  <div class="search-panel" :class="{ collapsed }">
    <div class="search-panel-header" @click="collapsed = !collapsed">
      <span class="search-panel-title">
        <v-icon icon="mdi-magnify" size="16" />
        筛选查询
      </span>
      <v-icon icon="mdi-chevron-down" class="collapse-icon" :class="{ expanded: !collapsed }" size="16" />
    </div>
    <div class="search-panel-body">
      <!-- 各页放自己的 v-form 表单字段 -->
      <slot />
      <div class="search-actions">
        <v-btn color="primary" prepend-icon="mdi-magnify" :loading="loading" @click="$emit('search')">
          搜索
        </v-btn>
        <v-btn prepend-icon="mdi-refresh" variant="outlined" @click="$emit('reset')">
          重置
        </v-btn>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  loading?: boolean
}>()

defineEmits<{
  search: []
  reset: []
}>()

// 折叠状态双向绑定，默认收起由页面决定
const collapsed = defineModel<boolean>('collapsed', { default: true })
</script>

<style scoped lang="scss">
.search-panel {
  background: var(--osr-surface);
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);
  overflow: hidden;
  transition: all var(--osr-transition-base);

  &.collapsed .search-panel-body {
    display: none;
  }

  .search-panel-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 14px;
    cursor: pointer;
    user-select: none;
    transition: background var(--osr-transition-fast);

    &:active {
      background: var(--osr-bg-page);
    }

    .search-panel-title {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 14px;
      font-weight: 600;
      color: var(--osr-text-primary);

      .v-icon {
        color: var(--osr-primary);
      }
    }

    .collapse-icon {
      font-size: 16px;
      color: var(--osr-text-secondary);
      transition: transform var(--osr-transition-base);

      &.expanded {
        transform: rotate(180deg);
      }
    }
  }

  .search-panel-body {
    padding: 0 14px 14px;

    :deep(.v-input) {
      margin-bottom: 12px;
    }

    .search-actions {
      display: flex;
      gap: 8px;
      margin-top: 4px;

      .v-btn {
        flex: 1;
      }
    }
  }
}
</style>
