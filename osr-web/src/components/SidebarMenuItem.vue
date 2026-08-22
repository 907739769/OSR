<template>
  <!-- 目录：一行标题 + 子项平铺，两端共用。移动端抽屉里是纯标题（平铺，见
       useSidebarGroups 的注释）；PC 侧边栏传 collapsible，标题可点开合 -->
  <template v-if="menu.children?.length">
    <div
      v-show="showGroupLabel"
      class="menu-group-label"
      :class="{ 'menu-group-label--clickable': collapsible }"
      :data-testid="`menu-group-${menu.name || menu.path}`"
      @click="collapsible && toggleGroup(groupKey)"
    >
      <span class="menu-group-title">{{ menu.meta?.title }}</span>
      <v-icon v-if="collapsible" :icon="open ? 'chevron-down' : 'chevron-right'" size="14" />
    </div>
    <template v-if="open">
      <SidebarMenuItem
        v-for="child in menu.children"
        :key="child.name || child.path"
        :menu="child"
        :show-group-label="showGroupLabel"
        :collapsible="collapsible"
      />
    </template>
  </template>
  <v-list-item v-else :to="menu.path" :title="menu.meta?.title" :data-testid="`menu-item-${menu.path}`" rounded="lg" class="menu-item">
    <template v-if="getIconComponent(menu.meta?.icon)" #prepend>
      <v-icon :icon="getIconComponent(menu.meta?.icon)" size="20" />
    </template>
  </v-list-item>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import type { MenuRoute } from '@/stores/user'
import { getIconComponent } from '@/composables/useMenuIcon'
import { useSidebarGroups } from '@/composables/useSidebarGroups'

/**
 * showGroupLabel：PC 侧边栏收成 rail（64px）时分组标题要藏起来，由 DesktopLayout
 * 传 appStore.sidebarOpened 决定。以前这个判断写在组件内部直接读 store —— 移动端
 * 复用不了，因为 App.vue 在移动端会调 closeSidebar()，标题会跟着一起消失。
 * 状态留给调用方，组件本身保持纯粹。
 *
 * collapsible：分组标题可点开合（只有 PC 常驻侧边栏开）。
 */
const props = withDefaults(
  defineProps<{ menu: MenuRoute; showGroupLabel?: boolean; collapsible?: boolean }>(),
  { showGroupLabel: true, collapsible: false }
)

const { isExpanded, toggleGroup } = useSidebarGroups()
// 组件单测是脱离 router 挂载的，useRoute() 会是 undefined
const route = useRoute()

const groupKey = computed(() => props.menu.name || props.menu.path)

/** 当前页在这一组里 —— 无论用户有没有展开过，它都要是展开的，否则「我在哪」看不见 */
const containsCurrent = computed(() => {
  const target = route?.path
  if (!target) return false
  const walk = (menus: MenuRoute[]): boolean =>
    menus.some((m) => m.path === target || (m.children?.length ? walk(m.children) : false))
  return walk(props.menu.children || [])
})

/** rail 态下标题是藏起来的，此时不能再折叠——否则 64px 的图标条上一个图标都不剩 */
const open = computed(
  () => !props.collapsible || !props.showGroupLabel || isExpanded(groupKey.value) || containsCurrent.value
)
</script>

<!-- 样式在 styles/menu.scss，两个 Layout 自己渲染的「首页」那条也要用同一套 -->
