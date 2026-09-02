<template>
  <!-- 悬浮玻璃底栏。样式在 styles/mobile-chrome.scss 的 .mobile-tabbar 单源
       （批量条要与它逐项对齐几何，放一起才看得出来）。

       用原生 <button> 而不是 v-btn / v-bottom-navigation：后者是 Vuetify 的布局组件，
       贴边全宽、参与 v-main 的偏移计算，改成离边悬浮要一路和它的布局系统对抗；
       而它给按钮的 min-width: 80px 也曾把五格挤出 375px 的屏幕（首尾各被裁掉一截，
       不报错、不出横向滚动条，只是边缘缺了一块）。 -->
  <nav
    class="mobile-tabbar"
    :class="{ 'mobile-tabbar--compact': compact }"
    :style="{ '--osr-tabbar-slots': slotCount, '--osr-tabbar-active': activeIndex }"
    aria-label="主导航"
  >
    <span class="tabbar-pill" aria-hidden="true" />

    <button
      v-for="tab in tabs"
      :key="tab.path"
      type="button"
      class="tabbar-item"
      :class="{ 'tabbar-item--active': tab.path === activeTabPath }"
      :aria-current="tab.path === activeTabPath ? 'page' : undefined"
      @click="go(tab.path)"
    >
      <v-icon :icon="tab.icon" size="21" />
      <span class="tabbar-label">{{ tab.title }}</span>
    </button>

    <!-- 第 5 格固定是「更多」。它不是路由，只负责掀开面板 -->
    <button
      type="button"
      class="tabbar-item"
      :class="{ 'tabbar-item--active': !onTab }"
      :aria-expanded="moreOpen"
      @click="emit('open-more')"
    >
      <v-icon icon="ellipsis" size="21" />
      <span class="tabbar-label">更多</span>
    </button>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMobileTabs } from '@/composables/useMobileTabs'

withDefaults(defineProps<{ compact?: boolean; moreOpen?: boolean }>(), {
  compact: false,
  moreOpen: false
})

const emit = defineEmits<{ 'open-more': [] }>()

const route = useRoute()
const router = useRouter()

/** 哪四个页面上底栏由用户定（存 localStorage，入口在「更多」面板底部） */
const { tabs } = useMobileTabs()

/** 可跳转的 tab + 固定的「更多」。tab 可能少于 4 个（配置里指向的菜单已被删/无权限） */
const slotCount = computed(() => tabs.value.length + 1)

const activeTabPath = computed(
  () =>
    tabs.value.find(
      (tab) => route.path === tab.path || route.path.startsWith(`${tab.path}/`)
    )?.path ?? null
)

const onTab = computed(() => activeTabPath.value !== null)

/**
 * 选中胶囊落在第几格。
 *
 * **不在任何 tab 上时落到最后那格（「更多」）而不是藏起来**：PT 那四组 12 个页面
 * 全都不在底栏上，底栏五格全灰会让用户失去「我在哪」——这条约定从 v-bottom-navigation
 * 时代就在，换实现时最容易顺手丢掉。
 */
const activeIndex = computed(() => {
  const i = tabs.value.findIndex((tab) => tab.path === activeTabPath.value)
  return i >= 0 ? i : tabs.value.length
})

const go = (path: string) => {
  if (route.path !== path) router.push(path)
}
</script>
