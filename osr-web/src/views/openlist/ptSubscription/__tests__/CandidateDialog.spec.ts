import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, DOMWrapper } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref } from 'vue'

// v-dialog 的内容 teleport 到 document.body，不在 wrapper 子树里
const body = () => new DOMWrapper(document.body)

afterEach(() => {
  document.querySelectorAll('.v-overlay-container').forEach((el) => el.remove())
})

vi.mock('@/composables/usePtSubscription', () => ({
  usePtSubscription: vi.fn()
}))

import CandidateDialog from '../dialogs/CandidateDialog.vue'
import { usePtSubscription } from '@/composables/usePtSubscription'
import { usePtSubscriptionProvider } from '@/composables/ptSubscriptionContext'

const cand = (title: string, over: Record<string, any> = {}) => ({
  title,
  indexerId: 1,
  indexerName: 'A站',
  resolution: '1080p',
  source: 'WEB-DL',
  size: 1024,
  seeders: 3,
  free: false,
  parsedEpisode: 5,
  parsedEpisodeEnd: null,
  ...over
})

const mountDialog = (candidates: any[]) => {
  const pushSelectedCandidate = vi.fn()
  ;(usePtSubscription as any).mockReturnValue({
    candidateDialogOpen: ref(true),
    candidates: ref(candidates),
    formatSize: () => '1 KB',
    pushSelectedCandidate,
    pushingSelected: ref(false)
  })
  // 走真实的 provider，弹窗拿到的是同一个实例——与页面里的装配方式一致
  const Host = defineComponent({
    setup() {
      usePtSubscriptionProvider()
      return () => h(CandidateDialog)
    }
  })
  return { wrapper: mount(Host, { attachTo: document.body }), pushSelectedCandidate }
}

const rows = () => body().findAll('.v-data-table tbody tr')

describe('候选种子弹窗的筛选', () => {
  it('输入标题关键词后表格只剩匹配行，并显示「显示 N / M」', async () => {
    const { wrapper } = mountDialog([
      cand('Some.Show.S01E05.2160p.HDR', { resolution: '2160p' }),
      cand('Some.Show.S01E05.1080p', { indexerId: 2, indexerName: 'B站' }),
      cand('Some.Show.S01E05.720p', { resolution: '720p' })
    ])
    await nextTick()
    expect(rows()).toHaveLength(3)

    const input = body().find('.filter-keyword input')
    await input.setValue('-hdr')
    await nextTick()

    expect(rows()).toHaveLength(2)
    expect(body().find('.filter-count').text()).toBe('显示 2 / 3')
    wrapper.unmount()
  })

  it('选中的那行被筛掉后取消选中，「下载选中版本」不能推一个看不见的种子', async () => {
    const { wrapper } = mountDialog([
      cand('Some.Show.S01E05.2160p', { resolution: '2160p' }),
      cand('Some.Show.S01E05.1080p')
    ])
    await nextTick()
    await rows()[0].trigger('click')
    await nextTick()
    const downloadBtn = () => body().findAll('.v-card-actions .v-btn').find(b => b.text().includes('下载选中版本'))!
    expect(downloadBtn().attributes('disabled')).toBeUndefined()

    await body().find('.filter-keyword input').setValue('1080p')
    await nextTick()
    await nextTick()

    expect(rows()).toHaveLength(1)
    expect(downloadBtn().attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('只有一种取值的维度不渲染下拉', async () => {
    const { wrapper } = mountDialog([
      cand('Some.Show.S01E05.2160p', { resolution: '2160p' }),
      cand('Some.Show.S01E05.1080p')
    ])
    await nextTick()
    const labels = body().findAll('.candidate-filters .v-select .v-label').map(l => l.text())
    // 分辨率有两种取值；站点、目标、片源都只有一种
    expect(labels).toContain('分辨率')
    expect(labels).not.toContain('站点')
    expect(labels).not.toContain('片源')
    wrapper.unmount()
  })
})
