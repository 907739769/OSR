import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ListLoadNotice from '../ListLoadNotice.vue'

describe('记录错误提示', () => {
  it('首次失败给重试入口，恢复后提示消失', async () => {
    const wrapper = mount(ListLoadNotice, { props: { failed: true, lastLoadedAt: null, loading: false } })
    expect(wrapper.text()).toContain('记录加载失败')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
    await wrapper.setProps({ failed: false })
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('保留旧数据时解释数据可能不匹配当前筛选，展示上次更新时间', () => {
    const wrapper = mount(ListLoadNotice, { props: { failed: true, lastLoadedAt: '2026-11-06T08:00:00.000Z', loading: false } })
    expect(wrapper.text()).toContain('可能不符合最新筛选条件')
    expect(wrapper.text()).toContain('上次更新：')
    wrapper.unmount()
  })
})
