import { describe, it, expect } from 'vitest'
import { defineComponent, h, KeepAlive, nextTick, ref, shallowRef, type Component } from 'vue'
import { mount } from '@vue/test-utils'
import {
  createMobilePageChrome,
  provideMobilePageChrome,
  useMobileBatchBarPresence,
  useMobilePageAction,
  type MobilePageChrome
} from '@/composables/useMobilePageAction'

/**
 * 悬浮底栏右侧的页面主动作。这里钉住的都是「坏了也不报错」的那几种：
 * 切页后按钮留在下一页、返回缓存页按钮消失、旧页面收尾时把新页面的按钮清掉、
 * 选择模式下按钮还在。
 */
const noop = () => {}

describe('页面主动作的登记表', () => {
  it('后登记的优先，撤销只撤自己那一份', () => {
    const chrome = createMobilePageChrome()
    const a = Symbol('a')
    const b = Symbol('b')

    chrome.setAction(a, () => ({ icon: 'plus', label: 'A', onClick: noop }))
    chrome.setAction(b, () => ({ icon: 'plus', label: 'B', onClick: noop }))
    expect(chrome.action.value?.label).toBe('B')

    // 切页时新页面先登记、旧页面后撤销：旧页面收尾不能把新页面的按钮带走
    chrome.clearAction(a)
    expect(chrome.action.value?.label).toBe('B')

    chrome.clearAction(b)
    expect(chrome.action.value).toBeNull()
  })

  it('重新登记会排到最后（Map 对已有键 set 不改顺序，这里必须先删再写）', () => {
    const chrome = createMobilePageChrome()
    const a = Symbol('a')
    const b = Symbol('b')
    chrome.setAction(a, () => ({ icon: 'plus', label: 'A', onClick: noop }))
    chrome.setAction(b, () => ({ icon: 'plus', label: 'B', onClick: noop }))

    chrome.setAction(a, () => ({ icon: 'plus', label: 'A', onClick: noop }))
    expect(chrome.action.value?.label).toBe('A')
  })

  it('批量条可见时收起主动作，隐藏后恢复', () => {
    const chrome = createMobilePageChrome()
    const visible = ref(false)
    chrome.setAction(Symbol('page'), () => ({ icon: 'plus', label: 'A', onClick: noop }))
    chrome.setBatchBar(Symbol('bar'), () => visible.value)

    expect(chrome.action.value).not.toBeNull()
    visible.value = true
    expect(chrome.action.value).toBeNull()
    visible.value = false
    expect(chrome.action.value?.label).toBe('A')
  })

  it('getter 里的状态变化会反映出来（扫描/保存的 loading）', () => {
    const chrome = createMobilePageChrome()
    const loading = ref(false)
    chrome.setAction(Symbol('page'), () => ({ icon: 'save', label: '保存', loading: loading.value, onClick: noop }))

    expect(chrome.action.value?.loading).toBe(false)
    loading.value = true
    expect(chrome.action.value?.loading).toBe(true)
  })
})

describe('keep-alive 下的登记与撤销', () => {
  const page = (label: string, batchVisible?: () => boolean) =>
    defineComponent({
      name: label,
      setup() {
        useMobilePageAction(() => ({ icon: 'plus', label, onClick: noop }))
        if (batchVisible) useMobileBatchBarPresence(batchVisible)
        return () => h('div', label)
      }
    })

  const mountShell = (initial: Component) => {
    let chrome!: MobilePageChrome
    const current = shallowRef<Component>(initial)
    const Shell = defineComponent({
      setup() {
        chrome = provideMobilePageChrome()
        return () => h(KeepAlive, null, [h(current.value)])
      }
    })
    const wrapper = mount(Shell)
    return { wrapper, current, action: () => chrome.action.value }
  }

  it('离开缓存页后按钮不留到下一页，返回时按钮回来', async () => {
    const Cached = page('新增STRM任务')
    const Plain = defineComponent({ name: 'Plain', setup: () => () => h('div', 'plain') })
    const { current, action } = mountShell(Cached)
    await nextTick()
    expect(action()?.label).toBe('新增STRM任务')

    current.value = Plain
    await nextTick()
    await nextTick()
    expect(action()).toBeNull()

    current.value = Cached
    await nextTick()
    await nextTick()
    expect(action()?.label).toBe('新增STRM任务')
  })

  it('从一个有主动作的页面切到另一个，显示的是新页面的', async () => {
    const A = page('新增索引器')
    const B = page('新增下载器')
    const { current, action } = mountShell(A)
    await nextTick()

    current.value = B
    await nextTick()
    await nextTick()
    expect(action()?.label).toBe('新增下载器')

    current.value = A
    await nextTick()
    await nextTick()
    expect(action()?.label).toBe('新增索引器')
  })

  it('缓存页里开着的批量条，切走之后不能继续压住别页的主动作', async () => {
    const selecting = ref(true)
    const Selecting = page('新增订阅', () => selecting.value)
    const Other = page('新增索引器')
    const { current, action } = mountShell(Selecting)
    await nextTick()
    expect(action()).toBeNull()

    current.value = Other
    await nextTick()
    await nextTick()
    expect(action()?.label).toBe('新增索引器')
  })

  it('不在 MobileLayout 里（没有 provider）时静默不做任何事', () => {
    expect(() => mount(page('新增'))).not.toThrow()
  })
})
