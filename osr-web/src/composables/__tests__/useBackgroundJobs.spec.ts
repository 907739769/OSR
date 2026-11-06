import { describe, it, expect, vi, beforeEach } from 'vitest'
import { backgroundJobs, taskCenterOpen, clearBackgroundJobs, startBackgroundJob } from '../useBackgroundJobs'
import { submitBackgroundJobApi } from '@/api/openlist/backgroundJobs'
vi.mock('@/api/openlist/backgroundJobs', () => ({ submitBackgroundJobApi: vi.fn() }))
beforeEach(() => { clearBackgroundJobs(); vi.resetAllMocks() })
describe('后台任务会话缓存', () => {
  it('提交成功后打开任务中心，记录服务端任务编号', async () => {
    vi.mocked(submitBackgroundJobApi).mockResolvedValue({ id: 'saved', kind: 'SUBSCRIPTION_SEARCH', state: 'QUEUED', cancelRequested: false, createTime: null, updateTime: null, items: [] })
    await startBackgroundJob('SUBSCRIPTION_SEARCH', [1, 2])
    expect(taskCenterOpen.value).toBe(true)
    expect(backgroundJobs.value[0].id).toBe('saved')
  })
  it('退出后忽略旧会话尚未返回的任务，避免显示给下一位用户', async () => {
    let resolve!: (value: any) => void
    vi.mocked(submitBackgroundJobApi).mockReturnValue(new Promise(r => { resolve = r }))
    const pending = startBackgroundJob('SUBSCRIPTION_SEARCH', [1])
    clearBackgroundJobs()
    resolve({ id: 'old-user' })
    await pending
    expect(taskCenterOpen.value).toBe(false)
    expect(backgroundJobs.value).toEqual([])
  })
})
