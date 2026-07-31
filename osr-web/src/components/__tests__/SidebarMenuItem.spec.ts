import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { createPinia } from 'pinia'
import { VList } from 'vuetify/components'
import SidebarMenuItem from '../SidebarMenuItem.vue'
import type { MenuRoute } from '@/stores/user'

// vitest.setup.ts 已全局安装真实 Vuetify 插件，v-list-group/v-list-item 按真实实现渲染；
// 组件自身给关键节点打了 data-testid，断言走 testid 而不是 Vuetify 内部 class，更稳定。
//
// v-list-group 依赖父级 v-list 提供的嵌套上下文（Symbol(vuetify:nested)）才能正常工作，
// 脱离 v-list 单独挂载会因缺少注入而渲染成空内容——这里统一包一层 v-list 模拟真实使用场景
// （DesktopLayout.vue 里就是 <v-list><SidebarMenuItem/></v-list>），并展开所有分组以便断言子节点。

function leaf(path: string, title: string): MenuRoute {
  return { path, name: title, meta: { title } }
}

/** 收集菜单树里所有目录节点的 group value（menu.name || menu.path），用于强制展开 */
function collectGroupValues(menu: MenuRoute): string[] {
  if (!menu.children?.length) return []
  const self = menu.name || menu.path
  return [self, ...menu.children.flatMap(collectGroupValues)]
}

function mountInList(menu: MenuRoute) {
  const Host = defineComponent({
    render() {
      return h(VList, { opened: collectGroupValues(menu) }, () => h(SidebarMenuItem, { menu }))
    }
  })
  // SidebarMenuItem 的 setup 会调用 useAppStore()，测试环境需提供 Pinia 实例
  return mount(Host, { global: { plugins: [createPinia()] } })
}

describe('SidebarMenuItem', () => {
  it('叶子菜单渲染成 v-list-item，data-testid 用自身 path', () => {
    const menu = leaf('/openlist/renameConfig', '重命名规则设置')
    const wrapper = mountInList(menu)

    const item = wrapper.find('[data-testid="menu-item-/openlist/renameConfig"]')
    expect(item.exists()).toBe(true)
    expect(item.text()).toContain('重命名规则设置')
    expect(wrapper.find('.v-list-group').exists()).toBe(false)
  })

  it('目录菜单渲染成 v-list-group，data-testid 用 menu.name 而不是 path', () => {
    const menu: MenuRoute = {
      path: '/openliststrm',
      name: '同步管理',
      meta: { title: '同步管理' },
      children: [
        leaf('/openliststrm/task', '同步任务配置'),
        leaf('/openliststrm/copy', '同步任务记录')
      ]
    }
    const wrapper = mountInList(menu)

    const subMenu = wrapper.find('[data-testid="menu-group-同步管理"]')
    expect(subMenu.exists()).toBe(true)
    expect(subMenu.text()).toContain('同步管理')

    const items = wrapper.findAll('[data-testid^="menu-item-"]')
    expect(items).toHaveLength(2)
    expect(items[0].attributes('data-testid')).toBe('menu-item-/openliststrm/task')
    expect(items[1].attributes('data-testid')).toBe('menu-item-/openliststrm/copy')
  })

  it('三级嵌套（目录>子目录>叶子）逐级递归渲染，父子目录即使 path 相同，data-testid(name) 也不会撞车', () => {
    const menu: MenuRoute = {
      path: '/openliststrm',
      name: 'OpenListStrm',
      meta: { title: 'OpenListStrm' },
      children: [
        {
          // 刻意让子目录的 path 和父目录一样，模拟后端 derivePath 反推路径撞车的真实场景，
          // 验证用 name 当 testid 不受这个影响
          path: '/openliststrm',
          name: '同步管理',
          meta: { title: '同步管理' },
          children: [leaf('/openliststrm/task', '同步任务配置')]
        }
      ]
    }
    const wrapper = mountInList(menu)

    const subMenus = wrapper.findAll('[data-testid^="menu-group-"]')
    expect(subMenus).toHaveLength(2)
    expect(subMenus[0].attributes('data-testid')).toBe('menu-group-OpenListStrm')
    expect(subMenus[1].attributes('data-testid')).toBe('menu-group-同步管理')

    const item = wrapper.find('[data-testid^="menu-item-"]')
    expect(item.attributes('data-testid')).toBe('menu-item-/openliststrm/task')
  })

  it('同级多个目录反推出的path相同时（模拟 derivePath 撞车场景），仍能各自正确渲染，data-testid不受影响', () => {
    const menu: MenuRoute = {
      path: '/openliststrm',
      name: 'OpenListStrm',
      meta: { title: 'OpenListStrm' },
      children: [
        {
          // 故意和下面两个目录的 path 一样，模拟 derivePath 撞车
          path: '/openliststrm',
          name: '同步管理',
          meta: { title: '同步管理' },
          children: [leaf('/openliststrm/task', '同步任务配置')]
        },
        {
          // 故意和上面/下面目录的 path 一样
          path: '/openliststrm',
          name: 'STRM管理',
          meta: { title: 'STRM管理' },
          children: [leaf('/openliststrm/strm_task', 'strm任务配置')]
        },
        {
          // 故意和上面两个目录的 path 一样
          path: '/openliststrm',
          name: '重命名管理',
          meta: { title: '重命名管理' },
          children: [leaf('/openliststrm/renameTask', '重命名任务配置')]
        }
      ]
    }
    const wrapper = mountInList(menu)

    // 顶层 OpenListStrm 自己是一个 group，加上3个子目录各自也是 group，一共4个
    const subMenus = wrapper.findAll('[data-testid^="menu-group-"]')
    expect(subMenus).toHaveLength(4)

    // v-for 用的 key 撞车（都是 '/openliststrm'）不应导致节点被错误复用或丢失，
    // testid(name) 应该各自唯一
    const testids = subMenus.map(s => s.attributes('data-testid'))
    expect(new Set(testids).size).toBe(testids.length)
    expect(testids).toEqual([
      'menu-group-OpenListStrm',
      'menu-group-同步管理',
      'menu-group-STRM管理',
      'menu-group-重命名管理'
    ])

    // 3个叶子节点都正确渲染出来了，且各自 testid 对应各自的 url
    const items = wrapper.findAll('[data-testid^="menu-item-"]')
    expect(items).toHaveLength(3)
    expect(items.map(i => i.attributes('data-testid'))).toEqual([
      'menu-item-/openliststrm/task',
      'menu-item-/openliststrm/strm_task',
      'menu-item-/openliststrm/renameTask'
    ])
  })
})
