import { config } from '@vue/test-utils'
import vuetify from './src/plugins/vuetify'

// jsdom 没有实现这些浏览器 API，Vuetify 的 VMenu/VOverlay/VDataTable 等组件内部会用到，
// 不 stub 掉会在 mount 阶段直接抛错。
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as any).ResizeObserver = (globalThis as any).ResizeObserver || ResizeObserverStub

if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false
  }) as unknown as MediaQueryList
}

config.global.plugins.push(vuetify)
