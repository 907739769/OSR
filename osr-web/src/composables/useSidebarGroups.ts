import { ref } from 'vue'

const STORAGE_KEY = 'osr-sidebar-groups'

function load(): string[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const parsed = raw ? JSON.parse(raw) : null
    return Array.isArray(parsed) ? parsed.filter((x) => typeof x === 'string') : []
  } catch {
    return []
  }
}

/** 模块级单例：侧边栏是常驻的，展开态要在所有分组之间共享 */
const expanded = ref<string[]>(load())

/**
 * PC 侧边栏分组的展开状态。
 *
 * 菜单摊平后一共 37 行、1604px，而 1280×800 的屏幕只装得下 17 行——PT 那四个分组
 * （追剧/下载/规则/接入，也就是现在最常用的一块）全在折叠线以下，每次都要先滚动。
 * 默认只展开「当前页所在的那一组」，其余收起：9 个分组标题 + 当前组约 3 项 ≈ 12 行，
 * 一屏装得下，换组多一次点击。
 *
 * 这与移动端抽屉刻意保持平铺不矛盾：抽屉是临时浮层、开一次点一下就关，多一层展开
 * 就是多一次等待；侧边栏是常驻的，值得让用户把不用的部分收起来。
 */
export function useSidebarGroups() {
  const isExpanded = (key: string) => expanded.value.includes(key)

  const toggleGroup = (key: string) => {
    expanded.value = isExpanded(key)
      ? expanded.value.filter((k) => k !== key)
      : [...expanded.value, key]
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(expanded.value))
    } catch {
      // 隐私模式下记不住就算了，不影响当次使用
    }
  }

  return { isExpanded, toggleGroup }
}
