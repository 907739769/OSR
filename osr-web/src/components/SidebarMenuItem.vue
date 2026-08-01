<template>
  <template v-if="menu.children?.length">
    <div v-show="appStore.sidebarOpened" class="menu-group-label" :data-testid="`menu-group-${menu.name || menu.path}`">
      {{ menu.meta?.title }}
    </div>
    <SidebarMenuItem v-for="child in menu.children" :key="child.name || child.path" :menu="child" />
  </template>
  <v-list-item v-else :to="menu.path" :title="menu.meta?.title" :data-testid="`menu-item-${menu.path}`" rounded="lg" class="menu-item">
    <template v-if="getIconComponent(menu.meta?.icon)" #prepend>
      <v-icon :icon="getIconComponent(menu.meta?.icon)" size="20" />
    </template>
  </v-list-item>
</template>

<script setup lang="ts">
import type { MenuRoute } from '@/stores/user'
import { getIconComponent } from '@/composables/useMenuIcon'
import { useAppStore } from '@/stores/app'

defineProps<{ menu: MenuRoute }>()
const appStore = useAppStore()
</script>

<style scoped lang="scss">
.menu-group-label {
  padding: 12px 12px 4px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.5px;
  color: rgba(var(--v-theme-on-surface), 0.5);
  white-space: nowrap;
  overflow: hidden;
}

.menu-item {
  margin: 1px 6px;

  &.v-list-item--active {
    color: rgb(var(--v-theme-primary));
    background: rgba(var(--v-theme-primary), 0.1);
  }
}
</style>
