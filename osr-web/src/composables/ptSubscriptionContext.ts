import { inject, provide, type InjectionKey } from 'vue'
import { usePtSubscription as createPtSubscription } from '@/composables/usePtSubscription'
import type { usePtSubscription } from '@/composables/usePtSubscription'

export type PtSubscriptionContext = ReturnType<typeof usePtSubscription>

const KEY: InjectionKey<PtSubscriptionContext> = Symbol('ptSubscription')

/**
 * 订阅页把 usePtSubscription 的**同一个实例**共享给它的弹窗子组件。
 *
 * 这一层是拆分 ptSubscription 的前提。这个 composable 返回 90 多个标识符，6 个弹窗
 * 全靠它们取状态；直接拆组件的话每个弹窗要塞 20~30 个 props，比不拆还难维护。
 * 而 composable 本身不是单例，子组件自己再调一次 usePtSubscription() 会拿到另一份
 * 互不相通的状态——列表里点「进度」，弹窗里什么都不会发生。
 *
 * 页面负责 providePtSubscription(ctx)，弹窗组件用 usePtSubscriptionContext() 取。
 */
/**
 * 页面侧入口：建实例 + provide + 原样返回，让页面仍然写成
 * `const { … } = usePtSubscriptionProvider(…)`。
 *
 * 名字必须以 use 开头：`device-parity.spec.ts` 扫的是 `const { … } = useXxx(` 这个形状，
 * 写成 `const ctx = usePtSubscription(); const { … } = ctx` 的话页面自己的解构就不在
 * 扫描范围内了——两端对齐的守护会悄悄缩水到只剩弹窗组件那部分，而且不会有任何报错。
 */
export function usePtSubscriptionProvider(
  ...args: Parameters<typeof usePtSubscription>
): PtSubscriptionContext {
  const ctx = createPtSubscription(...args)
  provide(KEY, ctx)
  return ctx
}

export function usePtSubscriptionContext(): PtSubscriptionContext {
  const ctx = inject(KEY)
  if (!ctx) {
    // 只可能是「组件被挂到了订阅页之外」，早点炸掉比在模板里到处出现 undefined 好查
    throw new Error('usePtSubscriptionContext 必须在 ptSubscription 页面内使用（页面需先调用 providePtSubscription）')
  }
  return ctx
}
