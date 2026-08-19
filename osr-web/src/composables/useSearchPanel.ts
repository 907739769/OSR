import { ref, watch, type Ref } from 'vue'
import { useRoute } from 'vue-router'

const STORAGE_KEY = 'osr-search-panel'

function readAll(): Record<string, boolean> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const parsed = raw ? JSON.parse(raw) : null
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    // 存的东西坏了就当没配过，不要因此让页面打不开
    return {}
  }
}

/**
 * PC 列表页搜索区的展开状态。
 *
 * 默认**收起**，与移动端一致（MobileSearchPanel 一直是默认收起的）。实测搜索卡在
 * 1280×800 上占 122px，而首行数据本来就已经在 y=403——顶栏 48 + 页头 + 搜索卡 122 +
 * 操作条 36 + 表头 56，半屏都不是数据。搜索并不是每次进页面都要用，但它一直占着位置。
 *
 * 按页记住用户的选择：常开着筛选的人只需要点一次。原来 17 个页面各写一行
 * `ref(window.innerWidth >= 768)`——那行还只在创建时读一次宽度，窗口变化它不跟。
 */
export function useSearchPanel(pageKey?: string): { showSearch: Ref<boolean> } {
  // 页面单测是脱离 router 挂载的（mount(Page) 不装 router 插件），此时 useRoute()
  // 返回 undefined —— 这里必须容错，否则一个「记住搜索区展开状态」的小功能会把
  // 所有页面组件测试一起打挂（实测 43 条）。落到共享 key 上对测试没有影响。
  const route = useRoute()
  const key = pageKey || route?.path || '_'
  const showSearch = ref<boolean>(readAll()[key] ?? false)

  watch(showSearch, (value) => {
    try {
      const all = readAll()
      all[key] = value
      localStorage.setItem(STORAGE_KEY, JSON.stringify(all))
    } catch {
      // 隐私模式下 localStorage 会抛，记不住就算了，不影响当次使用
    }
  })

  return { showSearch }
}
