import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import GlobalFeedback from '../GlobalFeedback.vue'
import { useFeedbackStore } from '@/stores/feedback'

// vitest.setup.ts 已全局安装真实 Vuetify 插件，v-snackbar 走真实实现——本用例考的正是
// v-snackbar 的自动关闭计时器有没有被正确触发，stub 掉就什么都测不到了。
// 断言一律看 store 队列而不是 DOM：v-snackbar 内容是 teleport 出去的，且带进出场过渡，
// 用 DOM 断言会被过渡时序干扰。队列空 == 提示已消失。

/** 让 Vuetify 的 setTimeout 回调跑完，并把随后的 DOM 更新与卸载/挂载都推进到位 */
async function advance(ms: number) {
  vi.advanceTimersByTime(ms)
  await nextTick()
  await nextTick()
}

describe('GlobalFeedback', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('单条提示到点后自动消失', async () => {
    const store = useFeedbackStore()
    const wrapper = mount(GlobalFeedback)

    store.pushSnackbar('已关闭洗版', 'success')
    await nextTick()
    expect(store.snackbars).toHaveLength(1)

    await advance(3000)
    expect(store.snackbars).toHaveLength(0)

    wrapper.unmount()
  })

  it('队头换人后新提示仍会自动消失，不会永远挂在页面上', async () => {
    const store = useFeedbackStore()
    const wrapper = mount(GlobalFeedback)

    // 连点开关：两条不同文案先后入队，第二条要等第一条播完才顶上来。
    // 若 v-snackbar 复用同一个组件实例，它的计时器不会为第二条重启，第二条就再也关不掉。
    store.pushSnackbar('已关闭洗版', 'success')
    store.pushSnackbar('已开启洗版', 'success')
    await nextTick()
    expect(store.snackbars).toHaveLength(2)

    await advance(3000)
    expect(store.snackbars.map((s) => s.text)).toEqual(['已开启洗版'])

    await advance(3000)
    expect(store.snackbars).toHaveLength(0)

    wrapper.unmount()
  })

  it('重复提示不入队，连点不会攒出一长串待播提示', () => {
    const store = useFeedbackStore()

    store.pushSnackbar('已关闭洗版', 'success')
    store.pushSnackbar('已开启洗版', 'success')
    store.pushSnackbar('已关闭洗版', 'success')
    store.pushSnackbar('已开启洗版', 'success')
    store.pushSnackbar('已关闭洗版', 'success')

    expect(store.snackbars.map((s) => s.text)).toEqual(['已关闭洗版', '已开启洗版'])
  })

  it('同文案不同级别算两条，错误提示不会被成功提示吞掉', () => {
    const store = useFeedbackStore()

    store.pushSnackbar('操作失败', 'success')
    store.pushSnackbar('操作失败', 'error')

    expect(store.snackbars.map((s) => s.level)).toEqual(['success', 'error'])
  })
})
