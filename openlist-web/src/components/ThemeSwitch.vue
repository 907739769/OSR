<template>
  <v-menu :close-on-content-click="false" location="bottom end">
    <template #activator="{ props: menuProps }">
      <v-btn v-bind="menuProps" icon variant="text" size="small" class="theme-switch-btn" aria-label="主题切换">
        <v-icon :icon="isDark ? 'mdi-weather-night' : 'mdi-white-balance-sunny'" />
      </v-btn>
    </template>
    <v-list density="compact" class="theme-menu">
      <v-list-item
        v-for="opt in options"
        :key="opt.value"
        :active="mode === opt.value"
        :prepend-icon="opt.icon"
        :title="opt.label"
        @click="setMode(opt.value)"
      />
    </v-list>
  </v-menu>
</template>

<script setup lang="ts">
import { useThemeMode, type ThemeMode } from '@/composables/useThemeMode'

const { mode, isDark, setMode } = useThemeMode()

const options: { value: ThemeMode; label: string; icon: string }[] = [
  { value: 'light', label: '浅色', icon: 'mdi-white-balance-sunny' },
  { value: 'dark', label: '深色', icon: 'mdi-weather-night' },
  { value: 'system', label: '跟随系统', icon: 'mdi-theme-light-dark' }
]
</script>

<style scoped lang="scss">
.theme-switch-btn {
  margin-right: 4px;
}
</style>
