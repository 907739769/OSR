import {
  computed,
  inject,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  provide,
  shallowReactive,
  type ComputedRef,
  type InjectionKey
} from 'vue'

/**
 * 移动端「页面主动作」：悬浮底栏右侧那颗独立的圆形按钮（新增 / 立即扫描 / 保存）。
 *
 * 它取代了原先各页自己写的右下角 `.fab-add`。那个按钮压在内容上方——滚到底时正好盖住
 * 分页器右侧的「下一页」与每页条数，没有分页器的页面盖住最后一张卡片的「更多」——而内容区
 * 只为底栏留了位、没为它留，页面不报任何错。并进底栏那一行之后，它与底栏共用同一份
 * 「底部占位」，这个问题在结构上就不存在了。
 *
 * **为什么要一层 provide/inject，而不是页面里直接渲染一个 fixed 按钮**：底栏必须知道这一页
 * 有没有主动作，才能把自己右侧让出来。两边各判一次的话迟早漂移，漂移的表现是按钮叠在
 * 底栏最右格「更多」上面。
 *
 * 三条不要改坏的：
 * 1. **一页只有一个主动作，不做成点开再选的菜单。** 那样最常用的动作要多点一次，
 *    这颗按钮也就变成了第二个「更多」。选哪个动作的判据见 osr-web/src/AGENTS.md。
 * 2. **登记必须同时挂 mounted/activated 与 deactivated/beforeUnmount。** 本项目有 7 个
 *    列表页开了 keep-alive，切走时它们只 deactivate、不 unmount：只挂 mounted 的话，
 *    离开缓存页后按钮会留到下一页（点下去执行的是上一页的新增），返回缓存页时按钮又没了。
 * 3. **按 owner 登记、按 owner 撤销。** 新旧页面的挂载与撤销钩子在同一次刷新里先后执行，
 *    按「清空当前值」撤销的话，旧页面收尾时会把新页面刚登记的按钮一起清掉。
 */

export interface MobilePageAction {
  /** lucide 图标名，须已在 plugins/lucideIcons.ts 登记 */
  icon: string
  /** 按钮只有图标，这是它唯一的文字：读屏名称 + 长按提示。写全，如「新增索引器」 */
  label: string
  onClick: () => void
  /** 进行中时显示转圈并禁止重复点击（扫描、保存这类要等后端的动作） */
  loading?: boolean
}

type Getter<T> = () => T

export interface MobilePageChrome {
  /** 当前应当显示的主动作；批量条出现时为 null（批量条接管底栏） */
  action: ComputedRef<MobilePageAction | null>
  setAction: (owner: symbol, getter: Getter<MobilePageAction | null>) => void
  clearAction: (owner: symbol) => void
  setBatchBar: (owner: symbol, getter: Getter<boolean>) => void
  clearBatchBar: (owner: symbol) => void
}

/** 纯状态部分，抽出来是为了脱离组件树被测到 */
export function createMobilePageChrome(): MobilePageChrome {
  const actions = shallowReactive(new Map<symbol, Getter<MobilePageAction | null>>())
  const batchBars = shallowReactive(new Map<symbol, Getter<boolean>>())

  const batchActive = computed(() => [...batchBars.values()].some((visible) => visible()))

  const action = computed(() => {
    if (batchActive.value) return null
    // 后登记的优先：切页瞬间新旧两页可能同时在表里，新页面总是排在后面
    const getters = [...actions.values()]
    for (let i = getters.length - 1; i >= 0; i--) {
      const a = getters[i]()
      if (a) return a
    }
    return null
  })

  // 先删再写：Map 对已有键 set 不改变顺序，不删的话重新激活的缓存页会排在新页面前面
  const put = <T>(map: Map<symbol, T>, owner: symbol, value: T) => {
    map.delete(owner)
    map.set(owner, value)
  }

  return {
    action,
    setAction: (owner, getter) => put(actions, owner, getter),
    clearAction: (owner) => { actions.delete(owner) },
    setBatchBar: (owner, getter) => put(batchBars, owner, getter),
    clearBatchBar: (owner) => { batchBars.delete(owner) }
  }
}

const KEY: InjectionKey<MobilePageChrome> = Symbol('mobile-page-chrome')

/** MobileLayout 调用：创建并向下提供 */
export function provideMobilePageChrome(): MobilePageChrome {
  const chrome = createMobilePageChrome()
  provide(KEY, chrome)
  return chrome
}

/** 在组件「处于激活状态」期间保持登记，见文件头第 2 条 */
function bindWhileActive(bind: () => void, unbind: () => void) {
  onMounted(bind)
  onActivated(bind)
  onDeactivated(unbind)
  onBeforeUnmount(unbind)
}

/**
 * 页面调用：声明本页的主动作。传 getter 而不是对象，loading 这类状态才能跟着变。
 * 不在 MobileLayout 里（单元测试直接挂载页面）时静默不做任何事。
 */
export function useMobilePageAction(getter: Getter<MobilePageAction | null>) {
  const chrome = inject(KEY, null)
  if (!chrome) return
  const owner = Symbol('page-action')
  bindWhileActive(() => chrome.setAction(owner, getter), () => chrome.clearAction(owner))
}

/** MobileBatchBar 调用：批量条可见时收起主动作 */
export function useMobileBatchBarPresence(visible: Getter<boolean>) {
  const chrome = inject(KEY, null)
  if (!chrome) return
  const owner = Symbol('batch-bar')
  bindWhileActive(() => chrome.setBatchBar(owner, visible), () => chrome.clearBatchBar(owner))
}
