<template>
  <!-- 移动端外壳（MobileLayout）已经给每页画了「图标 + 大标题」，这里再画一遍就是双标题。
       所以移动端只保留操作区；没有操作区时整块不渲染，免得留一截空白 -->
  <div v-if="!isMobile || $slots.actions" class="page-header" :class="{ 'page-header--mobile': isMobile }">
    <div v-if="!isMobile" class="page-header-left">
      <div v-if="icon" class="page-header-icon">
        <v-icon :icon="icon" />
      </div>
      <!-- 标题与描述同行：竖排时这块要占 57px，而 1280×800 上首行数据本来就已经在
           半屏以下。描述仍然完整保留，只是不再单独占一行 -->
      <div class="page-header-text">
        <h2 class="page-title">{{ title }}</h2>
        <p v-if="$slots.desc || desc" class="page-desc">
          <slot name="desc">{{ desc }}</slot>
        </p>
      </div>
    </div>
    <div class="page-header-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { useInMobileLayout } from '@/composables/useMobilePageAction'

const isMobile = useInMobileLayout()

defineProps<{
  title: string
  desc?: string
  icon?: string
}>()
</script>

<style scoped lang="scss">
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;

  .page-header-left {
    display: flex;
    align-items: center;
    gap: 12px;
    min-width: 0;
  }

  .page-header-text {
    display: flex;
    align-items: baseline;
    gap: 10px;
    flex-wrap: wrap;
    min-width: 0;
  }

  .page-header-icon {
    width: 32px;
    height: 32px;
    border-radius: var(--osr-radius-base);
    background: linear-gradient(135deg, rgb(var(--v-theme-primary)), rgb(var(--v-theme-primary-darken-1)));
    color: rgb(var(--v-theme-on-primary));
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 18px;
    box-shadow: 0 2px 8px rgba(var(--v-theme-primary), 0.35);
    flex-shrink: 0;
  }

  .page-title {
    margin: 0;
    font-size: 18px;
    font-weight: 700;
    color: var(--osr-text-primary);
    letter-spacing: 0.3px;
    white-space: nowrap;
  }

  .page-desc {
    margin: 0;
    font-size: 13px;
    color: var(--osr-text-secondary);
  }

  /* 不给 flex 的话，多个操作之间只剩标签间的空白符当间距，行内文字（如「上次加载」）
     还会按基线与按钮错开半行——缺集体检页头是第一个同时放文字与两颗按钮的 */
  .page-header-actions {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-shrink: 0;
  }
}

/* 移动端只剩操作区：靠右排，放不下时折行 */
.page-header--mobile {
  justify-content: flex-end;

  .page-header-actions {
    flex-shrink: 1;
    flex-wrap: wrap;
    justify-content: flex-end;
    min-width: 0;
  }
}

/* 窄屏：描述折行，图标缩小 */
@media (max-width: 768px) {
  .page-header {
    padding: 0 4px;
    align-items: flex-start;
    flex-wrap: wrap;

    .page-title {
      font-size: 17px;
    }

    .page-desc {
      display: none;
    }
  }
}
</style>
