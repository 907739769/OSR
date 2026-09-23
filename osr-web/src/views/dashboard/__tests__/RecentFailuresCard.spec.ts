import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('@/api/openlist/strmRecord', () => ({ getStrmRecordListApi: vi.fn() }))
vi.mock('@/api/openlist/copyRecord', () => ({ getCopyRecordListApi: vi.fn() }))
vi.mock('@/api/openlist/renameDetail', () => ({ getRenameDetailListApi: vi.fn() }))
vi.mock('@/router', () => ({ getRoutePathForComponent: (k: string) => `/p/${k}` }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import { getStrmRecordListApi } from '@/api/openlist/strmRecord'
import { getCopyRecordListApi } from '@/api/openlist/copyRecord'
import { getRenameDetailListApi } from '@/api/openlist/renameDetail'
import RecentFailuresCard from '../RecentFailuresCard.vue'

async function mountCard() {
  const wrapper = mount(RecentFailuresCard)
  await flushPromises()
  return wrapper
}

describe('RecentFailuresCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it('三路全部失败：显示错误态与重试，不显示绿色的「暂无失败记录」', async () => {
    vi.mocked(getStrmRecordListApi).mockRejectedValue(new Error('down'))
    vi.mocked(getCopyRecordListApi).mockRejectedValue(new Error('down'))
    vi.mocked(getRenameDetailListApi).mockRejectedValue(new Error('down'))
    const wrapper = await mountCard()

    expect(wrapper.text()).not.toContain('暂无失败记录')
    expect(wrapper.find('.failure-error').exists()).toBe(true)
    const retry = wrapper.findAll('button').find((b) => b.text() === '重试')
    expect(retry).toBeTruthy()

    // 重试后恢复正常、且确实没有失败记录，才轮得到绿色空态
    vi.mocked(getStrmRecordListApi).mockResolvedValue({ records: [] } as any)
    vi.mocked(getCopyRecordListApi).mockResolvedValue({ records: [] } as any)
    vi.mocked(getRenameDetailListApi).mockResolvedValue({ records: [] } as any)
    await retry!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.failure-error').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无失败记录')
  })

  it('部分失败：照常列出取到的记录，并提示数据不全', async () => {
    vi.mocked(getStrmRecordListApi).mockResolvedValue({ records: [{ strmId: 1, strmFileName: 'a.strm', createTime: '2026-09-20 10:00:00' }] } as any)
    vi.mocked(getCopyRecordListApi).mockRejectedValue(new Error('down'))
    vi.mocked(getRenameDetailListApi).mockResolvedValue({ records: [] } as any)
    const wrapper = await mountCard()

    expect(wrapper.findAll('.failure-item').length).toBe(1)
    expect(wrapper.text()).toContain('a.strm')
    expect(wrapper.find('.failure-partial').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('暂无失败记录')
  })

  it('部分失败且取到的几路都没有记录：也不能报平安', async () => {
    vi.mocked(getStrmRecordListApi).mockResolvedValue({ records: [] } as any)
    vi.mocked(getCopyRecordListApi).mockRejectedValue(new Error('down'))
    vi.mocked(getRenameDetailListApi).mockResolvedValue({ records: [] } as any)
    const wrapper = await mountCard()

    expect(wrapper.text()).not.toContain('暂无失败记录')
    expect(wrapper.find('.failure-error').text()).toContain('部分数据没取到')
  })
})
