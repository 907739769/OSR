<template>
  <!-- 「更多」面板。取代原先从左侧滑入的抽屉，理由见 styles/mobile-chrome.scss 的注释。
       样式必须放在全局样式表里：v-bottom-sheet 会把内容 Teleport 到 .v-overlay-container，
       <style scoped> 的属性选择器够不着它，症状是「掀开来一片没样式的白板」。 -->
  <v-bottom-sheet v-model="model" content-class="more-panel-shell">
    <div class="more-panel">
      <div class="more-grabber" aria-hidden="true" />

      <div class="more-search">
        <v-text-field
          v-model="keyword"
          placeholder="搜功能，比如「洗版」「索引器」"
          prepend-inner-icon="search"
          variant="solo-filled"
          density="compact"
          rounded="lg"
          flat
          clearable
          hide-details
          aria-label="搜索功能"
        />
      </div>

      <div class="more-body">
        <template v-for="section in sections" :key="section.title">
          <div v-if="section.title" class="more-group-label">{{ section.title }}</div>
          <div class="more-tiles">
            <button
              v-for="item in section.items"
              :key="`${section.title}-${item.path}`"
              type="button"
              class="more-tile"
              :class="{ 'more-tile--current': item.path === route.path }"
              :aria-current="item.path === route.path ? 'page' : undefined"
              @click="go(item.path)"
            >
              <span class="more-tile-glyph"><v-icon :icon="item.icon" size="21" /></span>
              <span class="more-tile-label">{{ item.title }}</span>
            </button>
          </div>
        </template>

        <p v-if="!sections.length" class="more-empty">没有匹配的功能，换个词试试</p>
      </div>

      <div class="more-foot">
        <v-btn
          variant="text"
          size="small"
          prepend-icon="sliders-horizontal"
          @click="openTabSettings"
        >
          自定义底栏
        </v-btn>
      </div>
    </div>
  </v-bottom-sheet>

  <MobileTabSettingsDialog v-model="showTabSettings" />
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MobileTabSettingsDialog from '@/components/mobile/MobileTabSettingsDialog.vue'
import { useMenuGroups, type MenuGroup } from '@/composables/useMenuLinks'
import { useMobileTabs } from '@/composables/useMobileTabs'
import { useRecentPages, MAX_RECENT } from '@/composables/useRecentPages'

const model = defineModel<boolean>({ default: false })

const route = useRoute()
const router = useRouter()

const groups = useMenuGroups()
const { tabs } = useMobileTabs()
const { recentPaths } = useRecentPages()

const keyword = ref('')
const showTabSettings = ref(false)

// 关掉面板就把搜索词清掉：留着的话下次掀开只剩上次筛出来的那几个格子，
// 而用户完全不记得自己搜过什么，读起来像「菜单少了一半」
watch(model, (open) => {
  if (!open) keyword.value = ''
})

/**
 * 「常用」= 最近访问过、且**不在底栏上**的页面。
 *
 * 剔掉底栏那几个是关键：它们本来就一触即达，摆进这一行只会把真正需要走这个入口的
 * 页面挤出去——而这个面板存在的全部理由，就是底栏放不下的那 21 个。
 */
const recentItems = computed(() => {
  const onTabBar = new Set(tabs.value.map((t) => t.path))
  const known = new Map(groups.value.flatMap((g) => g.items).map((i) => [i.path, i]))
  return recentPaths.value
    .filter((p) => !onTabBar.has(p))
    .map((p) => known.get(p))
    .filter((i): i is NonNullable<typeof i> => !!i)
    .slice(0, MAX_RECENT)
})

/**
 * 关键词同时匹配**菜单名与分组名**：打「pt」要能一次筛出 PT 那四组，
 * 而它们的子菜单名（订阅管理、过滤规则、索引器…）里一个 PT 字样都没有——
 * 20260785 那次分组时特意把子菜单名里的「PT」前缀去掉了。
 */
const filteredGroups = computed<MenuGroup[]>(() => {
  const kw = keyword.value?.trim().toLowerCase() ?? ''
  if (!kw) return groups.value
  return groups.value
    .map((g) => ({
      title: g.title,
      items: g.title.toLowerCase().includes(kw)
        ? g.items
        : g.items.filter((i) => i.title.toLowerCase().includes(kw))
    }))
    .filter((g) => g.items.length > 0)
})

/**
 * 面板的分块顺序：**无标题的首块（首页等常量路由）→ 常用 → 各菜单分组**。
 *
 * 无标题那块必须排在最前。它夹在两个带标题的分块中间时，读起来像是上一块漏了几个格子
 * ——而「无标题的首块」本身是列表界面的常见形态，放在开头完全成立。
 *
 * 「常用」只在没搜索时出现：用户已经明确说了要找什么，这时它是纯干扰项。
 */
const sections = computed<MenuGroup[]>(() => {
  const untitled = filteredGroups.value.filter((g) => !g.title)
  const titled = filteredGroups.value.filter((g) => g.title)
  const list: MenuGroup[] = [...untitled]
  if (!keyword.value && recentItems.value.length) {
    list.push({ title: '常用', items: recentItems.value })
  }
  list.push(...titled)
  return list
})

const go = (path: string) => {
  model.value = false
  if (route.path !== path) router.push(path)
}

const openTabSettings = () => {
  model.value = false
  showTabSettings.value = true
}
</script>
