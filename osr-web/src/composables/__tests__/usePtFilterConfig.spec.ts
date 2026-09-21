import { describe, it, expect, vi, beforeEach } from 'vitest'
import { effectScope } from 'vue'

vi.mock('@/composables/useMessage', () => ({
  message: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }
}))
vi.mock('@/composables/useConfirm', () => ({ confirm: vi.fn() }))
vi.mock('@/api/openlist/ptFilterConfig', () => ({
  getPtFilterConfigApi: vi.fn(),
  updatePtFilterConfigApi: vi.fn(),
  getSortDimensionsApi: vi.fn(),
  getFilterVocabularyApi: vi.fn(),
  previewPtFilterApi: vi.fn()
}))

import { usePtFilterConfig } from '../usePtFilterConfig'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import {
  getPtFilterConfigApi,
  updatePtFilterConfigApi,
  getSortDimensionsApi,
  getFilterVocabularyApi,
  previewPtFilterApi
} from '@/api/openlist/ptFilterConfig'
import { GB } from '@/composables/sizeUnits'

const flush = () => new Promise((r) => setTimeout(r, 0))

async function setup() {
  const scope = effectScope()
  const api = scope.run(() => usePtFilterConfig())!
  await flush()
  return { api, scope }
}

beforeEach(() => {
  vi.clearAllMocks()
  ;(getPtFilterConfigApi as any).mockResolvedValue({
    minSeeders: 1,
    minSize: GB,
    maxSize: 0,
    preferredSize: 0,
    sortPriority: 'SEEDERS',
    resolutionPriority: '2160p,1080p'
  })
  ;(getSortDimensionsApi as any).mockResolvedValue(['RESOLUTION', 'SEEDERS'])
  ;(getFilterVocabularyApi as any).mockResolvedValue({ resolutions: [], sources: [], tags: [] })
  ;(updatePtFilterConfigApi as any).mockResolvedValue(undefined)
  ;(previewPtFilterApi as any).mockResolvedValue({ accepted: true, tags: [], effectiveSize: 0, episodeCount: 1 })
})

describe('usePtFilterConfig', () => {
  it('加载后不算有修改，改了任意一项才算', async () => {
    const { api } = await setup()
    expect(api.isDirty.value).toBe(false)
    api.sortOrder.value = ['RESOLUTION', 'SEEDERS']
    expect(api.isDirty.value).toBe(true)
  })

  it('体积下限大于上限时拦在前端，不提交', async () => {
    const { api } = await setup()
    api.form.minSize = 5
    api.form.maxSize = 2
    expect(api.sizeRangeError.value).not.toBe('')
    await api.save()
    expect(updatePtFilterConfigApi).not.toHaveBeenCalled()
    expect(message.error).toHaveBeenCalled()
  })

  it('试算用的是页面上未保存的规则，体积换成字节', async () => {
    const { api } = await setup()
    api.form.minSize = 2
    api.previewForm.title = 'Some.Show.S01E01.1080p.WEB-DL-GRP'
    api.previewForm.sizeGb = 1.5
    await api.runPreview()
    const req = (previewPtFilterApi as any).mock.calls[0][0]
    expect(req.config.minSize).toBe(2 * GB)
    expect(req.size).toBe(Math.round(1.5 * GB))
  })

  it('放弃修改先确认，取消则保留编辑', async () => {
    const { api } = await setup()
    api.form.minSeeders = 9
    ;(confirm as any).mockRejectedValueOnce(new Error('cancel'))
    await api.discard()
    expect(api.form.minSeeders).toBe(9)
    ;(confirm as any).mockResolvedValueOnce(undefined)
    await api.discard()
    expect(api.form.minSeeders).toBe(1)
  })
})
