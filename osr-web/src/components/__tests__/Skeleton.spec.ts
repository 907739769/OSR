import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref } from 'vue'
import { useFirstLoad } from '@/composables/useFirstLoad'
import MobileListPage from '../mobile/MobileListPage.vue'
import SkeletonTable from '../skeleton/SkeletonTable.vue'
import { VDataTable } from 'vuetify/components'

/**
 * 骨架屏的三条规则（见 styles/skeleton.scss 头注释）里，有两条会静默失效：
 * 「首屏给骨架、刷新给进度条」判错了只是换了一种加载形态，不报错；
 * 表格的 #loading 插槽一旦无条件提供，每次翻页都会把数据行整片抹成骨架，也不报错。
 */

describe('useFirstLoad', () => {
  it('loading 初值为 false、挂载后才置 true 的 composable，首屏仍判为首屏', async () => {
    const loading = ref(false)
    const { firstLoading, refreshing } = useFirstLoad(loading)
    loading.value = true
    await nextTick()
    expect(firstLoading.value).toBe(true)
    expect(refreshing.value).toBe(false)
  })

  it('loading 落下过一次之后，再加载就是刷新', async () => {
    const loading = ref(true)
    const { firstLoading, refreshing } = useFirstLoad(loading)
    loading.value = false
    await nextTick()
    loading.value = true
    await nextTick()
    expect(firstLoading.value).toBe(false)
    expect(refreshing.value).toBe(true)
  })
})

describe('MobileListPage 加载形态', () => {
  it('首屏渲染骨架卡而不是进度条，加载完后的刷新只给进度条', async () => {
    const wrapper = mount(MobileListPage, { props: { loading: true } })
    expect(wrapper.findAll('.osr-sk-mcard').length).toBe(5)
    expect(wrapper.find('.v-progress-linear').exists()).toBe(false)

    await wrapper.setProps({ loading: false })
    await wrapper.setProps({ loading: true })
    expect(wrapper.find('.osr-sk-mcard').exists()).toBe(false)
    expect(wrapper.find('.v-progress-linear').exists()).toBe(true)
  })

  it('加载中不展示空态', () => {
    const wrapper = mount(MobileListPage, { props: { loading: true, empty: true } })
    expect(wrapper.find('.v-empty-state').exists()).toBe(false)
  })
})

describe('SkeletonTable', () => {
  const headers = [
    { title: '路径', key: 'path', minWidth: '300' },
    { title: '状态', key: 'status', align: 'center' as const, width: '80' },
    { title: '操作', key: 'actions', align: 'center' as const, width: '220' }
  ]

  it('列模板吃表格自己的 headers：定宽列定宽、minWidth 列分剩余空间，勾选列单独一格', () => {
    const wrapper = mount(SkeletonTable, { props: { headers, selectable: true, rows: 2 } })
    const style = wrapper.find('.osr-sk-table').attributes('style')
    expect(style).toContain('56px minmax(300px, 1fr) 80px 220px')
    expect(wrapper.findAll('.osr-sk-table__row').length).toBe(2)
    // 状态列画成 pill、操作列画成按钮位
    expect(wrapper.findAll('.osr-bone--chip').length).toBe(2)
    expect(wrapper.findAll('.osr-bone--btn').length).toBe(4)
  })

  it('放进 v-data-table 的 #loading 插槽且带 !items.length 条件时，有数据的刷新不替换数据行', async () => {
    const items = ref<Array<{ path: string }>>([])
    const Page = defineComponent({
      setup: () => () =>
        h('div', [
          // 与页面里的写法一致：<template v-if="!list.length" #loading>
          h(
            VDataTable,
            { items: items.value, headers, loading: true },
            items.value.length ? {} : { loading: () => h(SkeletonTable, { headers }) }
          )
        ])
    })
    const wrapper = mount(Page)
    expect(wrapper.find('.osr-sk-table').exists()).toBe(true)

    items.value = [{ path: '/a' }]
    await nextTick()
    expect(wrapper.find('.osr-sk-table').exists()).toBe(false)
    expect(wrapper.text()).toContain('/a')
  })
})
