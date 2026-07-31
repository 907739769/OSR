<template>
  <v-list-group v-if="menu.children?.length" :value="menu.name || menu.path" :data-testid="`menu-group-${menu.name || menu.path}`">
    <template #activator="{ props: activatorProps }">
      <v-list-item v-bind="activatorProps" :title="menu.meta?.title" class="menu-group-item">
        <template v-if="getIconComponent(menu.meta?.icon)" #prepend>
          <v-icon :icon="getIconComponent(menu.meta?.icon)" size="20" />
        </template>
      </v-list-item>
    </template>
    <MobileSidebarMenuItem v-for="child in menu.children" :key="child.name || child.path" :menu="child" />
  </v-list-group>
  <v-list-item
    v-else
    :to="menu.path"
    :title="menu.meta?.title"
    :data-testid="`menu-item-${menu.path}`"
    rounded="lg"
    class="menu-item"
  >
    <template v-if="getIconComponent(menu.meta?.icon)" #prepend>
      <v-icon :icon="getIconComponent(menu.meta?.icon)" size="20" />
    </template>
  </v-list-item>
</template>

<script setup lang="ts">
import type { MenuRoute } from '@/stores/user'
import { getIconComponent } from '@/composables/useMenuIcon'

defineProps<{ menu: MenuRoute }>()
</script>

<style scoped lang="scss">
.menu-group-item {
  min-height: 44px;
  font-weight: 500;
}

.menu-item {
  margin: 1px 6px;
  min-height: 44px;

  &.v-list-item--active {
    color: rgb(var(--v-theme-primary));
    background: rgba(var(--v-theme-primary), 0.1);
  }
}
</style>
