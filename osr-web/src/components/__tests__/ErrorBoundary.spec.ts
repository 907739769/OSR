import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, ref, nextTick } from 'vue'
import ErrorBoundary from '../ErrorBoundary.vue'

// 边界里用了 useRoute（路由一变就复位）与 useRouter（返回首页）。测试环境不装
// vue-router 插件，mock 成一个可控的路径 ref，好在用例里手动"导航"。
const fullPath = ref('/a')
const push = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => new Proxy({}, { get: (_t, key) => (key === 'fullPath' ? fullPath.value : undefined) }),
  useRouter: () => ({ push })
}))

/** 受控的会抛异常的子组件：boom 为 true 时渲染即抛。 */
const Boom = defineComponent({
  props: { boom: { type: Boolean, default: true } },
  setup(props) {
    return () => {
      if (props.boom) throw new Error('渲染炸了')
      return h('div', { class: 'ok' }, '正常内容')
    }
  }
})

let errSpy: any

beforeEach(() => {
  fullPath.value = '/a'
  // 边界自己会 console.error 补一条堆栈（吞掉错误后控制台就什么都没有了），
  // 用例里静音，免得测试输出里混进红色噪音。
  errSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  errSpy.mockRestore()
  push.mockReset()
})

describe('ErrorBoundary', () => {
  it('子组件正常时原样渲染插槽，不插入任何布局盒子', () => {
    const wrapper = mount(ErrorBoundary, {
      slots: { default: () => h(Boom, { boom: false }) }
    })

    expect(wrapper.find('.ok').exists()).toBe(true)
    expect(wrapper.find('.error-boundary').exists()).toBe(false)
    // 包裹层是 display: contents，不能带任何影响布局的类
    expect(wrapper.find('.error-boundary__content').exists()).toBe(true)
  })

  it('子组件抛错时渲染兜底界面而不是整块白屏', async () => {
    const wrapper = mount(ErrorBoundary, {
      slots: { default: () => h(Boom) }
    })
    await nextTick()

    expect(wrapper.find('.error-boundary').exists()).toBe(true)
    expect(wrapper.text()).toContain('页面出错了')
    // 出错原因要显示出来：不显示的话用户能做的只有刷新，而刷新回到同一个错误
    expect(wrapper.find('.error-boundary__message').text()).toContain('渲染炸了')
  })

  it('吞掉错误的同时仍往控制台补一条堆栈', () => {
    mount(ErrorBoundary, { slots: { default: () => h(Boom) } })

    expect(errSpy).toHaveBeenCalled()
    expect(errSpy.mock.calls[0][0]).toBe('[ErrorBoundary]')
  })

  it('路由一变就自动复位', async () => {
    const wrapper = mount(ErrorBoundary, {
      slots: { default: () => h(Boom, { boom: false }) }
    })
    // 直接把内部状态推成出错态，模拟"上一页炸了"
    ;(wrapper.vm as any).error = new Error('上一页炸了')
    await nextTick()
    expect(wrapper.find('.error-boundary').exists()).toBe(true)

    fullPath.value = '/b'
    await nextTick()

    // 错误态属于"某个页面的这一次渲染"。不复位的话用户点到别的菜单，
    // 那个页面本来是好的，却会继续看到上一页的错误
    expect(wrapper.find('.error-boundary').exists()).toBe(false)
    expect(wrapper.find('.ok').exists()).toBe(true)
  })

  it('重试会换掉插槽的 key，强制子组件重建', async () => {
    const wrapper = mount(ErrorBoundary, {
      slots: { default: () => h(Boom, { boom: false }) }
    })
    const before = (wrapper.vm as any).retryKey
    ;(wrapper.vm as any).error = new Error('炸了')
    await nextTick()

    await wrapper.find('.error-boundary__actions .v-btn').trigger('click')

    // 只把 error 置空的话出错的组件实例还在，Vue 复用它继续渲染、多半立刻再抛同一个错
    expect((wrapper.vm as any).retryKey).toBe(before + 1)
    expect(wrapper.find('.error-boundary').exists()).toBe(false)
  })
})
