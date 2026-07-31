<template>
  <v-list-group v-if="menu.children?.length" :value="menu.name || menu.path" :data-testid="`menu-group-${menu.name || menu.path}`">
    <template #activator="{ props: activatorProps }">
      <v-list-item v-bind="activatorProps" :title="menu.meta?.title">
        <template v-if="getIconComponent(menu.meta?.icon)" #prepend>
          <v-icon :icon="getIconComponent(menu.meta?.icon)" />
        </template>
      </v-list-item>
    </template>
    <SidebarMenuItem v-for="child in menu.children" :key="child.name || child.path" :menu="child" />
  </v-list-group>
  <v-list-item v-else :to="menu.path" :title="menu.meta?.title" :data-testid="`menu-item-${menu.path}`">
    <template v-if="getIconComponent(menu.meta?.icon)" #prepend>
      <v-icon :icon="getIconComponent(menu.meta?.icon)" />
    </template>
  </v-list-item>
</template>

<script setup lang="ts">
import type { MenuRoute } from '@/stores/user'
import { getIconComponent } from '@/composables/useMenuIcon'

defineProps<{ menu: MenuRoute }>()
</script>
