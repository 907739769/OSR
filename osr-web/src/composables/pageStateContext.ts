import { inject, provide, type InjectionKey } from 'vue'

/**
 * 页面把自己那份业务 composable 实例共享给子组件（当前用于 `components/dialogs/` 下
 * 两端共用的表单弹窗）。
 *
 * 这是 `ptSubscriptionContext.ts` 的通用版：那边因为订阅页要给 6 个弹窗传 90 多个
 * 标识符而单独写了一份，这边的十个表单弹窗各自只要 8~10 个，但**问题是同一个**——
 * 表单弹窗要 `v-model="form.xxx"`，`form` 一旦作为 prop 传进来就会被
 * `vue/no-mutating-props` 拦下，而绕过它（改 `:model-value` + 逐字段 emit）
 * 等于把 16 个字段的双向绑定手写一遍。
 *
 * **子组件绝不能自己再调一次业务 composable**：那会拿到另一份互不相通的状态，
 * 现象是「列表里点修改，弹窗里是空的 / 填完提交没反应」。
 *
 * provider 的名字必须以 `use` 开头：`device-parity.spec.ts` 扫的是
 * `const { … } = useXxx(` 这个形状，写成 `providePageState(...)` 的话页面自己的
 * 解构就不在扫描范围内了，两端对齐的守护会悄悄缩水且没有任何报错。
 */
const KEY: InjectionKey<unknown> = Symbol('pageState')

/** 页面侧：建实例 + provide + 原样返回，页面仍然写成 `const { … } = usePageStateProvider(useXxx(…))` */
export function usePageStateProvider<T extends object>(state: T): T {
  provide(KEY, state)
  return state
}

/** 子组件侧：取**同一个**实例 */
export function usePageState<T extends object>(): T {
  const state = inject<T>(KEY as InjectionKey<T>)
  if (!state) {
    // 只可能是「组件被挂到了对应页面之外」，早点炸掉比在模板里到处出现 undefined 好查
    throw new Error('usePageState 必须在调用过 usePageStateProvider 的页面内使用')
  }
  return state
}
