<template>
  <v-card class="chart-card">
    <div class="chart-header">
      <span class="chart-title">快捷入口</span>
    </div>
    <div class="quick-links">
      <div
        v-for="link in quickLinks"
        :key="link.path"
        class="quick-link-item"
        @click="link.path && router.push(link.path)"
      >
        <v-icon :icon="link.icon" />
        <span>{{ link.title }}</span>
      </div>
    </div>
  </v-card>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useMenuLinks } from '@/composables/useMenuLinks'

/** 首页快捷入口：菜单树拍平取叶子（useMenuLinks），路径不写死 */
const router = useRouter()

const quickLinks = useMenuLinks()
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
   Quick links
   ============================================ */
.quick-links {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 10px;
  padding: 16px 20px;

  .quick-link-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    padding: 12px 8px;
    border-radius: var(--osr-radius-md);
    background: var(--osr-bg-page);
    cursor: pointer;
    transition: all var(--osr-transition-fast);

    .v-icon {
      color: var(--osr-primary);
      font-size: 20px;
    }

    span {
      font-size: 12px;
      color: var(--osr-text-secondary);
      text-align: center;
      line-height: 1.3;
      overflow: hidden;
      text-overflow: ellipsis;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
    }

    &:hover {
      background: var(--osr-primary-subtle);
      transform: translateY(-1px);
    }
  }
}
</style>
