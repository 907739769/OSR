import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { effectScope } from 'vue'
import { message } from '@/composables/useMessage'

vi.mock('@/composables/useMessage', () => ({
  message: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }
}))

vi.mock('@/api/openlist/renameConfig', () => ({
  getRenameTemplateApi: vi.fn(),
  previewRenameTemplateApi: vi.fn(),
  updateRenameTemplateApi: vi.fn(),
  getCategoryRulesApi: vi.fn(),
  saveCategoryRulesApi: vi.fn()
}))

vi.mock('@/api/openlist/renameTask', () => ({ testParseRenameApi: vi.fn() }))

import { useRenameConfig } from '../useRenameConfig'
import {
  getRenameTemplateApi, previewRenameTemplateApi, updateRenameTemplateApi,
  getCategoryRulesApi, saveCategoryRulesApi
} from '@/api/openlist/renameConfig'
import { testParseRenameApi } from '@/api/openlist/renameTask'

/** 造一条分类规则 */
function rule(targetDir: string, overrides: Record<string, any> = {}) {
  return { mediaType: 'movie', targetDir, genreIds: '', originalLanguages: '', originCountries: '', isFallback: '0', ...overrides }
}

/** 让已经排好的微任务跑完（不推进定时器） */
const tick = () => Promise.resolve().then(() => {})

/** 让整条真实宏任务队列跑完（不能与假定时器同用） */
const flush = () => new Promise((r) => setTimeout(r, 0))

beforeEach(() => {
  vi.clearAllMocks()
  ;(getRenameTemplateApi as any).mockResolvedValue({ template: '{{ title }}.{{ extension }}' })
  ;(previewRenameTemplateApi as any).mockResolvedValue('示例电影.mkv')
  ;(updateRenameTemplateApi as any).mockResolvedValue(undefined)
  ;(saveCategoryRulesApi as any).mockResolvedValue(undefined)
  ;(getCategoryRulesApi as any).mockImplementation(async (mediaType: string) =>
    mediaType === 'movie'
      ? [rule('动画电影'), rule('外语电影', { isFallback: '1' })]
      : [rule('日番', { mediaType: 'tv' }), rule('未分类', { mediaType: 'tv', isFallback: '1' })]
  )
})

afterEach(() => {
  vi.useRealTimers()
})

describe('useRenameConfig 分类规则保存', () => {
  /**
   * 这条守的是一个会静默丢用户改动的问题：保存后整份重载（movie + tv 一起拉）
   * 会把另一侧还没保存的编辑覆盖掉，而「先改剧集、再改电影、点保存电影」
   * 恰恰是这页最自然的操作顺序——界面上没有任何提示，用户以为两边都存上了。
   */
  it('保存电影规则不会冲掉剧集那边未保存的编辑', async () => {
    const c = useRenameConfig()
    await flush()

    c.tvRules.value.push(rule('还没保存的新规则', { mediaType: 'tv' }))
    const before = [...c.tvRules.value]

    await c.saveRules('movie')

    expect(c.tvRules.value).toEqual(before)
    expect(c.tvRules.value.some(r => r.targetDir === '还没保存的新规则')).toBe(true)
    // 刚保存的那一侧要回填（初始化 1 次 + 保存后 1 次），另一侧只在初始化时拉过
    const calls = (getCategoryRulesApi as any).mock.calls.map((c2: any[]) => c2[0])
    expect(calls.filter((m: string) => m === 'movie')).toHaveLength(2)
    expect(calls.filter((m: string) => m === 'tv')).toHaveLength(1)
  })

  /**
   * 两个保存按钮原先共用一个布尔量，点「保存电影」时「保存剧集」也一起转圈，
   * 看起来像两边都在提交。
   */
  it('保存中的标记只落在被点的那一侧', async () => {
    let release: () => void = () => {}
    ;(saveCategoryRulesApi as any).mockReturnValue(new Promise<void>((r) => { release = r }))

    const c = useRenameConfig()
    await tick()

    const saving = c.saveRules('movie')
    await tick()
    expect(c.savingRulesType.value).toBe('movie')

    release()
    await saving
    expect(c.savingRulesType.value).toBe('')
  })

  it('保存失败也要复位标记，且不重复弹 toast（拦截器已经弹过）', async () => {
    (saveCategoryRulesApi as any).mockRejectedValue(new Error('必须保留且只能保留一条兜底规则'))

    const c = useRenameConfig()
    await tick()
    await c.saveRules('tv')

    expect(c.savingRulesType.value).toBe('')
    expect(message.error).not.toHaveBeenCalled()
  })
})

describe('useRenameConfig 模板预览', () => {
  /**
   * 防抖取消掉的那次调用，请求根本没发出去、没有结果可等。旧实现让它的 Promise
   * 永远不 resolve，而 loadTemplate 正 await 着它——templateLoading 会卡在 true。
   */
  it('被防抖取消的那次调用也会结束等待，不会吊死', async () => {
    vi.useFakeTimers()
    const c = useRenameConfig()
    await vi.advanceTimersByTimeAsync(400)

    let firstSettled = false
    const first = c.doPreview()
    first.then(() => { firstSettled = true })
    c.doPreview()
    await tick()

    expect(firstSettled).toBe(true)
    await vi.advanceTimersByTimeAsync(400)
  })

  it('初始化后 templateLoading 会落回 false', async () => {
    vi.useFakeTimers()
    const c = useRenameConfig()
    await vi.advanceTimersByTimeAsync(400)

    expect(c.templateLoading.value).toBe(false)
    expect(c.template.value).toBe('{{ title }}.{{ extension }}')
    expect(c.previewResult.value).toBe('示例电影.mkv')
  })

  /**
   * 防抖只压住「还没发出去」的那批，两个请求同时在途仍然可能发生；
   * 先发的后回会把新结果覆盖成旧的，用户看到的预览与编辑框里的模板对不上。
   */
  it('后发的预览结果不会被先发的覆盖', async () => {
    vi.useFakeTimers()
    let releaseOld: (v: string) => void = () => {}
    ;(previewRenameTemplateApi as any)
      .mockReturnValueOnce(Promise.resolve('初始'))
      .mockReturnValueOnce(new Promise<string>((r) => { releaseOld = r }))
      .mockReturnValueOnce(Promise.resolve('新结果'))

    const c = useRenameConfig()
    await vi.advanceTimersByTimeAsync(400)

    c.doPreview()
    await vi.advanceTimersByTimeAsync(400)   // 第二次请求发出，挂着不回
    c.doPreview()
    await vi.advanceTimersByTimeAsync(400)   // 第三次请求发出并立即回
    expect(c.previewResult.value).toBe('新结果')

    releaseOld('过期结果')
    await tick()
    await tick()
    expect(c.previewResult.value).toBe('新结果')
  })

  /** 页面卸载后不该再发预览请求，也不该往没人看的 ref 上写值 */
  it('作用域销毁后不再发出预览请求', async () => {
    vi.useFakeTimers()
    const scope = effectScope()
    let c: ReturnType<typeof useRenameConfig>
    scope.run(() => { c = useRenameConfig() })
    await vi.advanceTimersByTimeAsync(400)

    const callsBefore = (previewRenameTemplateApi as any).mock.calls.length
    c!.doPreview()
    scope.stop()
    await vi.advanceTimersByTimeAsync(400)

    expect((previewRenameTemplateApi as any).mock.calls.length).toBe(callsBefore)
  })
})

describe('useRenameConfig 错误提示', () => {
  it('加载失败不重复弹 toast（拦截器已经弹过）', async () => {
    (getRenameTemplateApi as any).mockRejectedValue(new Error('boom'))
    ;(getCategoryRulesApi as any).mockRejectedValue(new Error('boom'))

    const c = useRenameConfig()
    await flush()

    expect(message.error).not.toHaveBeenCalled()
    expect(c.templateLoading.value).toBe(false)
    expect(c.rulesLoading.value).toBe(false)
  })

  it('测试解析失败不重复弹 toast，后端给的原因由拦截器负责显示', async () => {
    (testParseRenameApi as any).mockRejectedValue(new Error('解析失败: 模板渲染失败'))

    const c = useRenameConfig()
    await tick()
    c.testForm.filename = 'The.Movie.2024.1080p.mkv'
    await c.doTest()

    expect(message.error).not.toHaveBeenCalled()
    expect(c.testLoading.value).toBe(false)
  })

  it('文件名为空时仍然在前端拦一下', async () => {
    const c = useRenameConfig()
    await tick()
    await c.doTest()

    expect(testParseRenameApi).not.toHaveBeenCalled()
    expect(message.warning).toHaveBeenCalled()
  })
})
