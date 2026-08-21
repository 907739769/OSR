import { nextTick, onScopeDispose, ref, type Ref } from 'vue'
import { useRouter } from 'vue-router'

/**
 * 页面切换的入场动画。
 *
 * ## 为什么不用 <transition>
 *
 * 两个 Layout 里都有一段注释记着：原先包了一层 `<transition name="fade-slide">`，
 * 但在「`<KeepAlive>` 与裸 `<component>` 交替 + 页面组件异步加载」这个结构下，
 * 过渡钩子从首屏起就没正常收敛过 —— enter-from 类一直挂着不被移除，离场过渡
 * 永远收不到结束事件，结果每导航一次旧页面就留在新页面下方越堆越多，
 * `mode="out-in"` 与 `:duration` 都压不住。
 *
 * 这里换了个思路：**只做入场，不做离场**。没有离场就没有「等旧元素动画结束
 * 再移除」这件事，那个死结的成因整个不存在了。用户实际感知的差别很小 ——
 * 页面切换时旧内容本来就是瞬间消失的，真正被读作「转场」的是新内容怎么进来。
 *
 * ## 为什么用 WAAPI 而不是 CSS class
 *
 * `element.animate()` 返回的动画自带生命周期，播完自动回到元素原本的样式，
 * **不会留下任何需要清理的类名或内联样式** —— 也就是说，把这段代码删掉之后
 * 页面不会残留任何痕迹。这正是上一版 CSS 过渡出问题的地方。
 *
 * 另外它天然兼容 KeepAlive：动画挂在**容器**上，与里面的组件是不是从缓存里
 * 恢复的无关。若把 CSS 动画挂在页面组件根节点上，keep-alive 命中缓存时
 * 组件不重新挂载，动画根本不会重放。
 *
 * ## 为什么在 afterEach + nextTick 里播
 *
 * vue-router 会在导航过程中 await 异步组件的 import，所以 afterEach 触发时
 * chunk 已经到位，只差 Vue 渲染这一步 —— 补一个 nextTick 就能保证动画开始时
 * 容器里已经有新页面的内容。这也是不用 View Transitions 做导航转场的原因：
 * 它抓快照的时机比这早，会把还没渲染出来的空白当成新页面。
 */
export function usePageTransition(): { contentRef: Ref<HTMLElement | null> } {
  const contentRef = ref<HTMLElement | null>(null)
  const router = useRouter()

  const stop = router.afterEach(async (to, from) => {
    // 首屏不播：那时用户刚从启动屏过来，再叠一层入场会显得拖沓。
    // 判 from.name 而不是 from.path —— 初始导航的 from 是那条 name 为
    // undefined 的空路由记录，而它的 path 是 '/'，与真实的首页路径撞车。
    if (!from.name) return
    // 同一路由内改 query（翻页、筛选）不算页面切换，播了反而像页面在闪
    if (to.path === from.path) return

    await nextTick()
    const el = contentRef.value
    if (!el || typeof el.animate !== 'function') return

    const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    if (reduceMotion) return

    el.animate(
      [
        { opacity: 0, transform: 'translate3d(0, 10px, 0)' },
        { opacity: 1, transform: 'none' }
      ],
      {
        duration: 320,
        // 与 tokens.scss 的 --osr-ease-out 同一条曲线。WAAPI 读不到 CSS 变量，
        // 只能在这里重写一遍；改令牌时记得同步这一处（全站仅此一处重复）
        easing: 'cubic-bezier(0.22, 1, 0.36, 1)'
      }
    )
  })

  onScopeDispose(stop)

  return { contentRef }
}
