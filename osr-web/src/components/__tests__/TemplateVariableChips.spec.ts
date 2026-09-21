import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TemplateVariableChips from '../TemplateVariableChips.vue'

const variables = [
  { name: 'title', label: '标题', sample: '示例剧集', common: true },
  { name: 'episodeName', label: '单集标题', sample: '第三集的标题', common: false }
]

describe('TemplateVariableChips', () => {
  it('先只摆常用变量，其余收进「更多」', async () => {
    const wrapper = mount(TemplateVariableChips, { props: { variables } })
    expect(wrapper.findAll('.variable-tag').map(c => c.text())).toEqual(['title'])

    await wrapper.find('.variables-toggle').trigger('click')
    expect(wrapper.findAll('.variable-tag').map(c => c.text())).toEqual(['title', 'episodeName'])
  })

  /**
   * 变量是靠点 chip 插进模板的；键盘用户要能 Tab 到它、按回车插入。
   * 这件事是 VChip 在「挂了 click 监听」时自己给的（tabindex=0 + Enter/Space），
   * 这条用例钉住它——改成不挂 @click 的写法（比如包一层 div 去接点击）会让键盘整个失效。
   */
  it('chip 可被键盘聚焦，回车即插入', async () => {
    const wrapper = mount(TemplateVariableChips, { props: { variables } })
    const chip = wrapper.find('.variable-tag')

    expect(chip.attributes('tabindex')).toBe('0')
    await chip.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('insert')?.[0]).toEqual(['title'])
  })
})
