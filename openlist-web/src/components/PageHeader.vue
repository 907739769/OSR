<template>
  <div class="page-header">
    <div class="page-header-left">
      <div v-if="icon" class="page-header-icon">
        <v-icon :icon="icon" />
      </div>
      <div>
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
    gap: 16px;
  }

  .page-header-icon {
    width: 48px;
    height: 48px;
    border-radius: var(--osr-radius-lg);
    background: linear-gradient(135deg, rgb(var(--v-theme-primary-lighten-4)), rgb(var(--v-theme-primary)));
    color: rgb(var(--v-theme-on-primary));
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 24px;
    box-shadow: 0 4px 14px rgba(var(--v-theme-primary), 0.35);
    flex-shrink: 0;
  }

  .page-title {
    margin: 0;
    font-size: 22px;
    font-weight: 700;
    color: var(--osr-text-primary);
    letter-spacing: 0.3px;
  }

  .page-desc {
    margin: 4px 0 0;
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

    .page-header-icon {
      width: 42px;
      height: 42px;
      font-size: 20px;
    }

    .page-title {
      font-size: 19px;
    }

    .page-desc {
      display: none;
    }
  }
}
</style>
