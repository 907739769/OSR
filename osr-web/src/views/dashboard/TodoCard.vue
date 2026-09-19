<template>
  <v-card class="todo-card">
    <div class="chart-header">
      <span class="chart-title">待办提醒</span>
      <v-btn v-if="failed" variant="text" size="small" color="primary" @click="load">重试</v-btn>
    </div>
    <v-progress-linear v-if="loading" indeterminate color="primary" />
    <div v-else-if="failed && !items.length" class="todo-empty">部分数据没取到，暂时无法判断是否有待办</div>
    <div v-else-if="!items.length" class="todo-empty">
      <v-icon icon="circle-check" size="18" color="success" />
      <span>暂无待办，一切正常</span>
    </div>
    <div v-else class="todo-list">
      <div
        v-for="item in items"
        :key="item.key"
        class="todo-item"
        :class="{ clickable: !!item.path }"
        @click="item.path && router.push(item.path)"
      >
        <span class="todo-count" :class="item.tone">{{ item.count }}</span>
        <div class="todo-text">
          <div class="todo-label">{{ item.label }}</div>
          <div class="todo-hint">{{ item.hint }}</div>
        </div>
        <v-icon icon="chevron-right" size="16" />
      </div>
    </div>
  </v-card>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useDashboardTodo } from '@/composables/useDashboardTodo'

const router = useRouter()
const { loading, failed, items, load } = useDashboardTodo()

onMounted(load)
defineExpose({ load })
</script>

<style scoped lang="scss">
.todo-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);
  height: 100%;
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
}

.todo-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 28px 16px;
  font-size: 13px;
  color: var(--osr-text-secondary);
}

.todo-list {
  padding: 6px 8px;
}

.todo-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-radius: var(--osr-radius-md);
  color: var(--osr-text-secondary);

  &.clickable {
    cursor: pointer;
    transition: background-color var(--osr-transition-fast);

    &:hover {
      background-color: var(--osr-primary-subtle);
    }
  }
}

.todo-count {
  min-width: 32px;
  text-align: center;
  font-weight: 700;
  font-size: 16px;
  padding: 2px 8px;
  border-radius: var(--osr-radius-md);

  &.warning {
    color: var(--osr-warning);
    background-color: var(--osr-warning-light);
  }
  &.error {
    color: var(--osr-error);
    background-color: var(--osr-error-light);
  }
}

.todo-text {
  flex: 1;
  min-width: 0;

  .todo-label {
    font-size: 14px;
    color: var(--osr-text-primary);
  }
  .todo-hint {
    font-size: 12px;
  }
}
</style>
