import { defineAsyncComponent, defineComponent, h } from 'vue'
import { useAppStore } from '@/stores/app'

type Loader = () => Promise<any>

/**
 * 生成「按设备切换实现」的路由组件。
 *
 * 路由表注册时不再固化 PC / 移动端组件：早先的做法是在 convertMenuToRoute 里读一次
 * device 决定加载哪一份，而 addRoute 之后这个选择就再也不会变，横竖屏切换或窗口缩放
 * 后会出现「移动端布局里套着 PC 页面」。改由组件自身响应 device，切换即时生效。
 */
export function createDeviceView(desktopLoader: Loader, mobileLoader: Loader) {
  const Desktop = defineAsyncComponent(desktopLoader)
  const Mobile = defineAsyncComponent(mobileLoader)

  return defineComponent({
    name: 'DeviceView',
    setup() {
      const appStore = useAppStore()
      return () => h(appStore.device === 'mobile' ? Mobile : Desktop)
    }
  })
}
