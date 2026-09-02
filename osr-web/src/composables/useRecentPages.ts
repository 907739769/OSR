import { computed, ref } from 'vue'

const STORAGE_KEY = 'osr-mobile-recent'

/** 「常用」一行放几个。四个正好铺满 .more-tiles 的一行，多出来的只是把分组挤下去 */
export const MAX_RECENT = 4

function read(): string[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) && parsed.every((p) => typeof p === 'string') ? parsed : []
  } catch {
    // 存的东西坏了就当没记录过，不要让「更多」面板整个打不开
    return []
  }
}

/** 模块级单例：刚访问过的页面要立刻出现在下一次打开的面板里 */
const paths = ref<string[]>(read())

/**
 * 最近访问过的页面（移动端「更多」面板顶部的「常用」）。
 *
 * 为什么需要它：底栏只有 4 格，而菜单有 25 个叶子——其余 21 个每次都要开面板、
 * 找分组、点进去。而人实际在用的从来不是 25 个，是其中固定的少数几个，只是那几个
 * 因人而异（这与 `useMobileTabs` 存在的理由完全相同）。
 *
 * 与底栏的分工：**面板里要把已经在底栏上的路径剔掉**（调用方负责，见
 * `MobileMorePanel`）。它们本来就一触即达，摆进「常用」只是把这一行占满、
 * 把真正需要这个入口的页面挤出去。
 */
export function useRecentPages() {
  const record = (path: string) => {
    if (!path) return
    const next = [path, ...paths.value.filter((p) => p !== path)]
    // 存的条数比展示的多一些：调用方会剔掉底栏上的那几个，只存 4 条的话
    // 剔完常常只剩一两个格子
    paths.value = next.slice(0, MAX_RECENT * 3)
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(paths.value))
    } catch {
      // 隐私模式 / 存储配额满：记不住不影响导航本身，不要抛出去
    }
  }

  const clear = () => {
    paths.value = []
    try {
      localStorage.removeItem(STORAGE_KEY)
    } catch {
      // 同上
    }
  }

  return { recentPaths: computed(() => paths.value), record, clear }
}
