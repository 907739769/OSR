import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import RecordStatusBar from '../RecordStatusBar.vue'

const options = [
  { value: '2', title: '失败', type: 'error' as const },
  { value: '3', title: '成功', type: 'success' as const }
]

describe('RecordStatusBar', () => {
  it('统计还没拉到时整条不渲染', () => {
    const wrapper = mount(RecordStatusBar, { props: { options, stats: null } })
    expect(wrapper.find('.record-status-bar').exists()).toBe(false)
  })

  it('显示全部与各状态的条数', () => {
    const wrapper = mount(RecordStatusBar, { props: { options, stats: { '2': 17, '3': 1182, total: 1199 } } })
    const text = wrapper.text().replace(/\s+/g, '')
    expect(text).toContain('全部1199')
    expect(text).toContain('失败17')
    expect(text).toContain('成功1182')
  })

  it('点状态即筛选，再点一次回到全部', async () => {
    const wrapper = mount(RecordStatusBar, { props: { options, stats: { '2': 17, total: 17 } } })
    const failed = wrapper.findAll('.record-status-chip')[1]

    await failed.trigger('click')
    expect(wrapper.emitted('update:modelValue')![0]).toEqual(['2'])

    await wrapper.setProps({ modelValue: '2' })
    await failed.trigger('click')
    expect(wrapper.emitted('update:modelValue')![1]).toEqual([undefined])
  })
})
