import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { defineComponent, h, ref } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import FormDialogShell from '../dialogs/FormDialogShell.vue'
import { confirm } from '@/composables/useConfirm'

vi.mock('@/composables/useConfirm', () => ({ confirm: vi.fn() }))
const wrappers: ReturnType<typeof mount>[] = []
const global = { plugins: [createPinia()], stubs: { VDialog: { template: '<div><slot /></div>' } } }
function openDialog(dirty = true) {
  const wrapper = mount(FormDialogShell, { props: { modelValue: true, dirty }, global })
  wrappers.push(wrapper)
  return wrapper
}
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.resetAllMocks() })
const cancelButton = (w: ReturnType<typeof mount>) => w.findAll('button').find(b => b.text() === '取消')!

describe('表单未保存保护', () => {
  it('继续编辑保留弹窗，确认放弃后才关闭', async () => {
    const wrapper = openDialog()
    vi.mocked(confirm).mockRejectedValueOnce('cancel').mockResolvedValueOnce()
    await cancelButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    await cancelButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })

  it('没有改动直接关闭，保存中不允许取消', async () => {
    const wrapper = openDialog(false)
    await cancelButton(wrapper).trigger('click')
    await flushPromises()
    expect(confirm).not.toHaveBeenCalled()
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    await wrapper.setProps({ submitting: true })
    expect(cancelButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it('刷新保护仅在弹窗有未保存修改时生效', async () => {
    const wrapper = openDialog()
    const event = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    await wrapper.setProps({ modelValue: false })
    const closedEvent = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(closedEvent)
    expect(closedEvent.defaultPrevented).toBe(false)
  })

  it('离开路由前也能继续编辑或确认放弃', async () => {
    const open = ref(true)
    const editor = defineComponent({ setup: () => () => h(FormDialogShell, {
      modelValue: open.value, dirty: true, 'onUpdate:modelValue': (value: boolean) => { open.value = value }
    }) })
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: editor }, { path: '/other', component: { template: '<div>其他页面</div>' } }
    ] })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(RouterView, { global: { ...global, plugins: [...global.plugins, router] } })
    wrappers.push(wrapper)
    vi.mocked(confirm).mockRejectedValueOnce('cancel').mockResolvedValueOnce()
    await router.push('/other')
    expect(router.currentRoute.value.path).toBe('/')
    expect(open.value).toBe(true)
    await router.push('/other')
    expect(router.currentRoute.value.path).toBe('/other')
    expect(open.value).toBe(false)
  })
})
