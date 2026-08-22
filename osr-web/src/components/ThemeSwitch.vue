<template>
  <v-menu :close-on-content-click="false" location="bottom end">
    <template #activator="{ props: menuProps }">
      <v-btn v-bind="menuProps" icon variant="text" size="small" class="theme-switch-btn" aria-label="主题切换">
        <!-- 图标做交叉旋转而不是直接换：换肤是全屏级别的变化，
             触发它的那个控件自己也该动一下，否则视觉上像是「页面自己变了」 -->
        <v-icon
          :key="isDark ? 'dark' : 'light'"
          :icon="isDark ? 'moon' : 'sun'"
          class="theme-switch-icon"
        />
      </v-btn>
    </template>
    <v-list density="compact" class="theme-menu">
      <v-list-item
        v-for="opt in options"
        :key="opt.value"
        :active="mode === opt.value"
        :prepend-icon="opt.icon"
        :title="opt.label"
        @click="(e: MouseEvent | KeyboardEvent) => setMode(opt.value, e)"
      />
    </v-list>
  </v-menu>
</template>

<script setup lang="ts">
import { useThemeMode, type ThemeMode } from '@/composables/useThemeMode'

const { mode, isDark, setMode } = useThemeMode()

const options: { value: ThemeMode; label: string; icon: string }[] = [
  { value: 'light', label: '浅色', icon: 'sun' },
  { value: 'dark', label: '深色', icon: 'moon' },
  { value: 'system', label: '跟随系统', icon: 'sun-moon' }
]
</script>

<style scoped lang="scss">
.theme-switch-btn {
  margin-right: 4px;
}

/* 图标随主题切换转半圈。挂 :key 让 Vue 在图标名变化时重建节点，
   动画因此每次切换都重放一遍 —— 只写 transition 的话，
   图标是被整个替换掉的，没有可过渡的中间态 */
.theme-switch-icon {
  animation: theme-icon-in var(--osr-dur-3) var(--osr-ease-out);
}

@keyframes theme-icon-in {
  from {
    opacity: 0;
    transform: rotate(-90deg) scale(0.6);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
</style>
