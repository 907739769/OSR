<template>
  <transition :name="isMobile ? 'slide-up' : 'slide-left'">
    <div v-if="needRefresh" class="app-update-prompt" :class="isMobile ? 'is-mobile' : 'is-desktop'">
      <v-icon icon="mdi-refresh" class="update-icon" />
      <div class="update-text">
        <span class="update-title">新版本已就绪</span>
        <span v-if="!isMobile" class="update-desc">刷新后即可使用最新功能</span>
      </div>
      <v-btn color="primary" size="small" :loading="updating" @click="applyUpdate">
        刷新
      </v-btn>
      <v-icon icon="mdi-close" class="dismiss-btn" @click="dismiss" />
    </div>
  </transition>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from '@/stores/app'
import { useAppUpdate } from '@/composables/useAppUpdate'

const appStore = useAppStore()
const isMobile = computed(() => appStore.device === 'mobile')

const { needRefresh, updating, applyUpdate, dismiss } = useAppUpdate()
</script>

<style scoped lang="scss">
.app-update-prompt {
  position: fixed;
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--osr-surface);
  border: 1px solid var(--osr-border-base);
  border-radius: var(--osr-radius-base);
  box-shadow: var(--osr-shadow-lg);
  padding: 10px 12px;

  .update-icon {
    font-size: 18px;
    color: var(--osr-primary);
    flex-shrink: 0;
  }

  .update-text {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;

    .update-title {
      font-size: 14px;
      font-weight: 600;
      color: var(--osr-text-primary);
      white-space: nowrap;
    }

    .update-desc {
      font-size: 12px;
      color: var(--osr-text-secondary);
    }
  }

  .dismiss-btn {
    font-size: 16px;
    cursor: pointer;
    color: var(--osr-text-secondary);
    padding: 4px;
    border-radius: var(--osr-radius-sm);
    flex-shrink: 0;
    transition: all var(--osr-transition-fast);

    &:hover {
      color: var(--osr-text-primary);
      background-color: var(--osr-primary-light-9);
    }
  }
}

/* 手机端：贴在 tabbar 上方的一条 banner，不遮挡列表内容。
   z-index 与 tabbar(997) 同级但物理不重叠；抽屉遮罩(998) 打开时会盖住它。 */
.app-update-prompt.is-mobile {
  left: 12px;
  right: 12px;
  bottom: calc(56px + env(safe-area-inset-bottom, 0px) + 8px);
  z-index: 997;
}

/* 桌面端：右下角常驻卡片，压在 Element 弹窗(2000+) 之下 */
.app-update-prompt.is-desktop {
  right: 20px;
  bottom: 20px;
  width: 320px;
  z-index: 1000;
}

.slide-up-enter-active,
.slide-up-leave-active,
.slide-left-enter-active,
.slide-left-leave-active {
  transition: transform var(--osr-transition-base), opacity var(--osr-transition-base);
}

.slide-up-enter-from,
.slide-up-leave-to {
  transform: translateY(120%);
  opacity: 0;
}

.slide-left-enter-from,
.slide-left-leave-to {
  transform: translateX(120%);
  opacity: 0;
}
</style>
