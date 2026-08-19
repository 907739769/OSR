<template>
  <!-- 目录：渲染成一行灰色标题 + 子项平铺，两端一致。
       移动端原先是独立的 MobileSidebarMenuItem（v-list-group 手风琴），已合并到这里 ——
       同一份菜单树没有理由有两套交互模型，何况手风琴在移动端反而多两步操作。 -->
  <template v-if="menu.children?.length">
    <div v-show="showGroupLabel" class="menu-group-label" :data-testid="`menu-group-${menu.name || menu.path}`">
      {{ menu.meta?.title }}
    </div>
    <SidebarMenuItem
      v-for="child in menu.children"
      :key="child.name || child.path"
      :menu="child"
      :show-group-label="showGroupLabel"
    />
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

/**
 * showGroupLabel：PC 侧边栏收成 rail（64px）时分组标题要藏起来，由 DesktopLayout
 * 传 appStore.sidebarOpened 决定。以前这个判断写在组件内部直接读 store —— 移动端
 * 复用不了，因为 App.vue 在移动端会调 closeSidebar()，标题会跟着一起消失。
 * 状态留给调用方，组件本身保持纯粹。
 */
withDefaults(defineProps<{ menu: MenuRoute; showGroupLabel?: boolean }>(), {
  showGroupLabel: true
})
</script>

<!-- 样式在 styles/menu.scss，两个 Layout 自己渲染的「首页」那条也要用同一套 -->
