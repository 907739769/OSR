import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import BackgroundTaskCenter from '../BackgroundTaskCenter.vue'
import { listBackgroundJobsApi } from '@/api/openlist/backgroundJobs'
import { clearBackgroundJobs, taskCenterOpen } from '@/composables/useBackgroundJobs'
vi.mock('@/stores/app', () => ({ useAppStore: () => ({ device: 'desktop' }) }))
vi.mock('@/api/openlist/backgroundJobs', () => ({ listBackgroundJobsApi: vi.fn(), cancelBackgroundJobApi: vi.fn(), retryBackgroundJobApi: vi.fn() }))
afterEach(() => { vi.useRealTimers(); clearBackgroundJobs() })
describe('后台任务刷新', () => {
  it('空列表轮询保留空状态、隐藏加载条，并防止请求重叠', async () => {
    vi.useFakeTimers()
    vi.mocked(listBackgroundJobsApi).mockResolvedValueOnce([])
    taskCenterOpen.value = true
    const wrapper = mount(BackgroundTaskCenter, { global: { stubs: {
      VDialog: { template: '<div><slot /></div>' },
      VCard: { template: '<div><slot /></div>' },
      VCardText: { template: '<div><slot /></div>' },
      VProgressLinear: { template: '<div data-test="progress" />' },
      VEmptyState: { template: '<div data-test="empty" />' }
    } } })
    await flushPromises()
    let finish!: (jobs: []) => void
    vi.mocked(listBackgroundJobsApi).mockImplementation(() => new Promise(resolve => { finish = resolve }))
    await vi.advanceTimersByTimeAsync(3000)
    expect(wrapper.find('[data-test="empty"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="progress"]').attributes('style')).toContain('display: none')
    await vi.advanceTimersByTimeAsync(3000)
    expect(listBackgroundJobsApi).toHaveBeenCalledTimes(2)
    finish([])
    await flushPromises()
    wrapper.unmount()
  })
})
