import { onMounted, onScopeDispose, ref, type Ref } from 'vue'

/**
 * 移动端外壳（顶栏小标题 / 悬浮底栏）的滚动响应状态。
 *
 * 两个布尔量，判据不同，**不要合并成一个**：
 *
 * - `scrolled`：大标题是否已经滚出视口。它决定顶栏里那个小标题淡不淡入、顶栏底部
 *   那条分隔线画不画。判据是**绝对位置**（滚过大标题那一行就算），因为它回答的是
 *   「大标题还看得见吗」。
 * - `compact`：底栏要不要收窄。判据是**滚动方向**——向下滚收起、向上滚或回到顶部
 *   附近展开。按 `scrolled` 那样用绝对位置做的话，任何一个滚过一次的列表页此后
 *   永远是图标态，等于把文字标签删了；而标签正是新用户分得清底栏那五格的唯一依据。
 *
 * 滚动的是**文档**而不是某个内层容器：MobileLayout 的 `.mobile-content` 没有设
 * overflow，`router` 的 `scrollBehavior` 也是按窗口滚动位置在恢复。改成内层滚动的话
 * 这里要跟着换滚动源，而症状会是「外壳纹丝不动」。
 */

/** 大标题那一行的高度量级：滚过它，顶栏里的小标题才该出现 */
const TITLE_THRESHOLD = 28

/**
 * 方向判定的最小位移。太小会让指尖的抖动来回翻转底栏（观感是它在抽搐），
 * 太大则要滑很远才响应。8px 是「一次有意的滑动」与「手指没拿稳」的分界。
 */
const DIRECTION_DELTA = 8

/**
 * 这个位置以内一律展开。没有这条的话，从中途向上快滑到顶部时，最后几帧位移
 * 可能不足 DIRECTION_DELTA，底栏会停在收窄态停在页面顶部——那是明显的错态。
 */
const EXPAND_ZONE = 60

export interface MobileChromeState {
  scrolled: Ref<boolean>
  compact: Ref<boolean>
  /** 喂入当前滚动位置。抽出来是为了让规则本身能脱离 DOM 被测到 */
  update: (y: number) => void
  /** 换页时调用：新页面从顶部开始，而滚动事件未必会再触发一次 */
  reset: () => void
}

export function createMobileChromeState(): MobileChromeState {
  const scrolled = ref(false)
  const compact = ref(false)
  let lastY = 0

  const update = (y: number) => {
    const top = Math.max(0, y)
    scrolled.value = top > TITLE_THRESHOLD

    if (top <= EXPAND_ZONE) {
      compact.value = false
      lastY = top
      return
    }
    // lastY 只在位移够大时推进：慢速连续滚动会逐步累积到阈值并触发，
    // 而原地抖动始终累积不到，不会来回翻转
    if (top - lastY > DIRECTION_DELTA) {
      compact.value = true
      lastY = top
    } else if (lastY - top > DIRECTION_DELTA) {
      compact.value = false
      lastY = top
    }
  }

  const reset = () => {
    scrolled.value = false
    compact.value = false
    lastY = 0
  }

  return { scrolled, compact, update, reset }
}

export function useMobileChrome(): MobileChromeState {
  const state = createMobileChromeState()

  const onScroll = () => state.update(window.scrollY)

  onMounted(() => {
    // passive：这个回调只读滚动位置、从不 preventDefault，声明出来能让浏览器
    // 不必等它返回就先滚起来
    window.addEventListener('scroll', onScroll, { passive: true })
    // keep-alive 的页面返回时 scrollBehavior 会恢复滚动位置，那一次未必产生
    // scroll 事件，所以挂载时先按当前位置算一次
    onScroll()
  })

  onScopeDispose(() => window.removeEventListener('scroll', onScroll))

  return state
}
