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

import { useRenameConfig, targetDirError, TARGET_DIR_MAX } from '../useRenameConfig'
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
  ;(previewRenameTemplateApi as any).mockResolvedValue({ movie: '示例电影.mkv', tv: '示例剧集 S1E3.mkv' })
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
    expect(c.previewSamples.value).toEqual({ movie: '示例电影.mkv', tv: '示例剧集 S1E3.mkv' })
  })

  /**
   * 防抖只压住「还没发出去」的那批，两个请求同时在途仍然可能发生；
   * 先发的后回会把新结果覆盖成旧的，用户看到的预览与编辑框里的模板对不上。
   */
  it('后发的预览结果不会被先发的覆盖', async () => {
    vi.useFakeTimers()
    let releaseOld: (v: string) => void = () => {}
    ;(previewRenameTemplateApi as any)
      .mockReturnValueOnce(Promise.resolve({ movie: '初始', tv: '初始' }))
      .mockReturnValueOnce(new Promise((r) => { releaseOld = (v: string) => r({ movie: v, tv: v }) }))
      .mockReturnValueOnce(Promise.resolve({ movie: '新结果', tv: '新结果' }))

    const c = useRenameConfig()
    await vi.advanceTimersByTimeAsync(400)

    c.doPreview()
    await vi.advanceTimersByTimeAsync(400)   // 第二次请求发出，挂着不回
    c.doPreview()
    await vi.advanceTimersByTimeAsync(400)   // 第三次请求发出并立即回
    expect(c.previewSamples.value.movie).toBe('新结果')

    releaseOld('过期结果')
    await tick()
    await tick()
    expect(c.previewSamples.value.movie).toBe('新结果')
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

describe('目标目录名校验', () => {
  /**
   * 目录名最终落在 targetRoot.resolve(category) 上：带 / 会静默多建一层目录，
   * .. 会把整个媒体库写到 targetRoot 之外。口径与后端 CategoryRuleValidator 一致。
   */
  it('拦住路径字符、. 与 ..、超长与空值', () => {
    for (const bad of ['外语/电影', '外语\\电影', 'a:b', 'a*b', 'a?b', 'a"b', 'a<b', 'a>b', 'a|b', '.', '..', ' .. ', '', '   ', null, undefined]) {
      expect(targetDirError(bad as any), String(bad)).not.toBeNull()
    }
    expect(targetDirError('a'.repeat(TARGET_DIR_MAX + 1))).not.toBeNull()
  })

  it('正常目录名放行，含中文、空格与单个点', () => {
    for (const ok of ['动画电影', 'Anime Movies', 'a.b', '...b', 'a'.repeat(TARGET_DIR_MAX)]) {
      expect(targetDirError(ok), ok).toBeNull()
    }
  })

  it('有非法目录名时不发请求，并指出是第几条', async () => {
    const c = useRenameConfig()
    await flush()
    c.movieRules.value[0].targetDir = '外语/电影'

    await c.saveRules('movie')

    expect(saveCategoryRulesApi).not.toHaveBeenCalled()
    expect(message.warning).toHaveBeenCalledWith(expect.stringContaining('第 1 条'))
    expect(c.savingRulesType.value).toBe('')
  })
})

describe('空规则列表', () => {
  /**
   * 界面上没有「设为兜底」的入口，后端又要求恰好一条兜底：
   * 列表为空时按普通规则新增，永远保存不上。
   */
  it('列表为空时新增的第一条就是兜底规则', async () => {
    (getCategoryRulesApi as any).mockResolvedValue([])
    const c = useRenameConfig()
    await flush()

    c.addRule('tv')
    expect(c.tvRules.value).toHaveLength(1)
    expect(c.tvRules.value[0].isFallback).toBe('1')

    c.addRule('tv')
    expect(c.tvRules.value[0].isFallback).toBe('0')
    expect(c.tvRules.value[1].isFallback).toBe('1')
  })
})

describe('未保存的修改', () => {
  it('刚加载完不算有修改', async () => {
    vi.useFakeTimers()
    const c = useRenameConfig()
    await vi.advanceTimersByTimeAsync(400)

    expect(c.anyDirty.value).toBe(false)
  })

  it('改模板只标记模板，保存后复位', async () => {
    vi.useFakeTimers()
    const c = useRenameConfig()
    await vi.advanceTimersByTimeAsync(400)

    c.template.value = '{{ title }}'
    expect(c.templateDirty.value).toBe(true)
    expect(c.rulesDirty.value).toBe(false)

    await c.saveTemplate()
    expect(c.templateDirty.value).toBe(false)
  })

  it('保存失败时仍算未保存', async () => {
    vi.useFakeTimers()
    ;(updateRenameTemplateApi as any).mockRejectedValue(new Error('模板渲染失败'))
    const c = useRenameConfig()
    await vi.advanceTimersByTimeAsync(400)

    c.template.value = '{{ broken'
    await c.saveTemplate()
    expect(c.templateDirty.value).toBe(true)
  })

  it('改剧集规则只标记剧集那一侧，保存后复位', async () => {
    const c = useRenameConfig()
    await flush()

    c.tvRules.value[0].targetDir = '日本动画'
    expect(c.tvRulesDirty.value).toBe(true)
    expect(c.movieRulesDirty.value).toBe(false)

    ;(getCategoryRulesApi as any).mockImplementation(async (mediaType: string) =>
      mediaType === 'tv'
        ? [rule('日本动画', { mediaType: 'tv' }), rule('未分类', { mediaType: 'tv', isFallback: '1' })]
        : []
    )
    await c.saveRules('tv')
    expect(c.tvRulesDirty.value).toBe(false)
  })

  /** 库里是 NULL，加一个条件再删掉会变成 ''——两者都是「不限」，不该凭空报一次未保存 */
  it('条件字段 null 与空串视为相同', async () => {
    (getCategoryRulesApi as any).mockResolvedValue([rule('外语电影', { isFallback: '1', genreIds: null })])
    const c = useRenameConfig()
    await flush()

    c.movieRules.value[0].genreIds = ''
    expect(c.movieRulesDirty.value).toBe(false)
  })
})

describe('模板变量清单', () => {
  it('用后端给的清单', async () => {
    (getRenameTemplateApi as any).mockResolvedValue({
      template: '{{ title }}',
      defaultTemplate: '{{ title }}',
      variables: [{ name: 'episodeName', label: '单集标题', sample: '第三集的标题', common: false }]
    })
    const c = useRenameConfig()
    await flush()

    expect(c.templateVariables.value.map(v => v.name)).toEqual(['episodeName'])
  })

  /** 只升级了前端镜像、后端还是旧版时，面板不能是空的 */
  it('后端没给清单时退回最小清单', async () => {
    const c = useRenameConfig()
    await flush()

    expect(c.templateVariables.value.map(v => v.name)).toContain('title')
  })
})

describe('规则置顶 / 置底', () => {
  const load = async () => {
    (getCategoryRulesApi as any).mockResolvedValue([
      rule('A'), rule('B'), rule('C'), rule('兜底', { isFallback: '1' })
    ])
    const c = useRenameConfig()
    await flush()
    return c
  }
  const names = (c: ReturnType<typeof useRenameConfig>) => c.movieRules.value.map(r => r.targetDir)

  it('置顶挪到第一位', async () => {
    const c = await load()
    c.moveRule('movie', 2, 'top')
    expect(names(c)).toEqual(['C', 'A', 'B', '兜底'])
  })

  /** 兜底行永远在最后：「置底」是挪到它前面，不能越过它 */
  it('置底挪到兜底规则之前', async () => {
    const c = await load()
    c.moveRule('movie', 0, 'bottom')
    expect(names(c)).toEqual(['B', 'C', 'A', '兜底'])
  })

  it('兜底行本身挪不动', async () => {
    const c = await load()
    c.moveRule('movie', 3, 'top')
    expect(names(c)).toEqual(['A', 'B', 'C', '兜底'])
  })
})

describe('识别参数详情', () => {
  /**
   * 原先把整个 MediaInfo 原样摊开：英文字段名、空值各占一行，还有 metadata——
   * TMDb 原始响应，一份 images 就 26KB，整块倒在一个格子里。
   */
  it('空值不列，中文说明取自变量清单，metadata 只报拉到了哪几份', async () => {
    (getRenameTemplateApi as any).mockResolvedValue({
      template: '{{ title }}',
      defaultTemplate: '{{ title }}',
      variables: [
        { name: 'title', label: '标题', sample: '', common: true },
        { name: 'tags', label: '特效标签', sample: '', common: true }
      ]
    })
    ;(testParseRenameApi as any).mockResolvedValue({
      renamed: 'x.mkv',
      info: {
        title: '进击的巨人',
        season: null,
        episode: '',
        tags: ['HDR', 'DV'],
        genreIds: [],
        newField: 'v',
        metadata: { images: { posters: new Array(500).fill('p') }, external_ids: {} }
      }
    })
    const c = useRenameConfig()
    await flush()
    c.testForm.filename = 'x.mkv'
    await c.doTest()

    expect(c.testInfoRows.value).toEqual([
      { key: 'title', label: '标题', value: '进击的巨人' },
      { key: 'tags', label: '特效标签', value: 'HDR, DV' },
      { key: 'newField', label: null, value: 'v' },
      { key: 'metadata', label: 'TMDb 元数据', value: '已获取 images、external_ids' }
    ])
  })
})
