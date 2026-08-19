<template>
  <div class="page-header">
    <div class="page-header-left">
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

  .page-header-actions {
    flex-shrink: 0;
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
