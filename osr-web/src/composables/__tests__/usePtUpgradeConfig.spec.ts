import { describe, it, expect, vi, beforeEach } from 'vitest'
import { effectScope } from 'vue'

vi.mock('@/composables/useMessage', () => ({
  message: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }
}))
vi.mock('@/composables/useConfirm', () => ({ confirm: vi.fn() }))
vi.mock('@/api/openlist/ptUpgradeConfig', () => ({
  getPtUpgradeConfigApi: vi.fn(),
  updatePtUpgradeConfigApi: vi.fn(),
  getQualityDimensionsApi: vi.fn(),
  getUpgradeOverviewApi: vi.fn(),
  checkPtUpgradeConfigApi: vi.fn(),
  triggerUpgradeScanApi: vi.fn()
}))
vi.mock('@/api/openlist/ptFilterConfig', () => ({
  getFilterVocabularyApi: vi.fn()
}))

import { usePtUpgradeConfig } from '../usePtUpgradeConfig'
import { message } from '@/composables/useMessage'
import {
  getPtUpgradeConfigApi,
  updatePtUpgradeConfigApi,
  getQualityDimensionsApi,
  getUpgradeOverviewApi,
  checkPtUpgradeConfigApi,
  triggerUpgradeScanApi
} from '@/api/openlist/ptUpgradeConfig'
import { getFilterVocabularyApi } from '@/api/openlist/ptFilterConfig'

const flush = () => new Promise((r) => setTimeout(r, 0))

async function setup() {
  const scope = effectScope()
  const api = scope.run(() => usePtUpgradeConfig())!
  await flush()
  await flush()
  return { api, scope }
}

beforeEach(() => {
  vi.clearAllMocks()
  ;(getPtUpgradeConfigApi as any).mockResolvedValue({
    enabled: '1',
    qualityPriority: 'SOURCE,RESOLUTION',
    targetResolution: '2160p',
    targetSources: 'REMUX',
    targetTags: '',
    maxConcurrent: 2,
    maxSearchesPerRound: 20,
    scanIntervalHours: 6
  })
  ;(getQualityDimensionsApi as any).mockResolvedValue(['RESOLUTION', 'SOURCE', 'TAG', 'RELEASE_GROUP'])
  ;(getFilterVocabularyApi as any).mockResolvedValue({ resolutions: ['2160p'], sources: ['REMUX'], tags: [] })
  ;(getUpgradeOverviewApi as any).mockResolvedValue({ active: true, problems: [] })
  ;(checkPtUpgradeConfigApi as any).mockResolvedValue([])
  ;(updatePtUpgradeConfigApi as any).mockResolvedValue(undefined)
  ;(triggerUpgradeScanApi as any).mockResolvedValue(undefined)
})

describe('usePtUpgradeConfig', () => {
  it('维度顺序保留配置的先后，未配置的补在后面', async () => {
    const { api } = await setup()
    expect(api.dimensionOrder.value).toEqual(['SOURCE', 'RESOLUTION', 'TAG', 'RELEASE_GROUP'])
    expect(api.isDirty.value).toBe(false)
  })

  it('清空目标分辨率后提交空串而不是 null——后端会跳过 null 字段，清空存不进去', async () => {
    const { api } = await setup()
    api.form.targetResolution = null as any
    expect(api.isDirty.value).toBe(true)
    await api.save()
    const payload = (updatePtUpgradeConfigApi as any).mock.calls[0][0]
    expect(payload.targetResolution).toBe('')
    expect(payload.qualityPriority).toBe('SOURCE,RESOLUTION,TAG,RELEASE_GROUP')
  })

  it('有未保存修改时不发起扫描：扫描按已保存的规则跑', async () => {
    const { api } = await setup()
    api.form.targetSources = 'REMUX,BluRay'
    await api.scanNow()
    expect(triggerUpgradeScanApi).not.toHaveBeenCalled()
    expect(message.warning).toHaveBeenCalled()
  })

  it('改目标质量后按草稿做一致性诊断', async () => {
    vi.useFakeTimers()
    try {
      const scope = effectScope()
      const api = scope.run(() => usePtUpgradeConfig())!
      await vi.runAllTimersAsync()
      ;(checkPtUpgradeConfigApi as any).mockClear()
      ;(checkPtUpgradeConfigApi as any).mockResolvedValue(['来源优先级为空'])
      api.form.targetSources = 'WEBRip'
      await vi.advanceTimersByTimeAsync(500)
      expect((checkPtUpgradeConfigApi as any).mock.calls[0][0].targetSources).toBe('WEBRip')
      expect(api.problems.value).toEqual(['来源优先级为空'])
      scope.stop()
    } finally {
      vi.useRealTimers()
    }
  })
})
