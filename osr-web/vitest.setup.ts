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

// VOverlay 的定位策略（v-snackbar/v-menu/v-dialog 都走它）会直接读全局 visualViewport，
// jsdom 没有这个对象，缺了它组件在挂载阶段就抛 ReferenceError。
if (!(globalThis as any).visualViewport) {
  const viewportStub = {
    width: 1024,
    height: 768,
    offsetLeft: 0,
    offsetTop: 0,
    scale: 1,
    addEventListener: () => {},
    removeEventListener: () => {}
  }
  ;(globalThis as any).visualViewport = viewportStub
  ;(window as any).visualViewport = viewportStub
}

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
