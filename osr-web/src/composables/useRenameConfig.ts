import { ref, reactive, computed, getCurrentInstance, getCurrentScope, onScopeDispose } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import {
  getRenameTemplateApi,
  previewRenameTemplateApi,
  updateRenameTemplateApi,
  getCategoryRulesApi,
  saveCategoryRulesApi,
  type CategoryRule,
  type TemplatePreview,
  type TemplateVariable
} from '@/api/openlist/renameConfig'
import { testParseRenameApi } from '@/api/openlist/renameTask'

/**
 * 后端没有返回变量清单时（只升级了前端镜像）退回的最小清单。
 * 正常情况下清单由后端从 MediaInfo 的渲染上下文生成，不在这里维护。
 */
const FALLBACK_VARIABLES: TemplateVariable[] = [
  'title', 'year', 'season', 'episode', 'resolution',
  'source', 'videoCodec', 'audioCodec', 'tags', 'releaseGroup', 'extension'
].map(name => ({ name, label: null, sample: '', common: true }))

/** 上移 / 下移一位，或置顶 / 置底（置底 = 兜底行之前） */
export type RuleMoveDirection = -1 | 1 | 'top' | 'bottom'

/** 与后端 CategoryRuleValidator.MAX_TARGET_DIR_LENGTH、rename_category_rule.target_dir 列宽一致 */
export const TARGET_DIR_MAX = 128

const ILLEGAL_DIR_CHARS = /[\\/:*?"<>|]/

/**
 * 目标目录名的校验。规则表输入框的即时提示与保存前的拦截共用这一份，口径与后端
 * CategoryRuleValidator 一致：它必须是**一层**目录名而不是一段路径——带 `/` 会静默多建一层目录，
 * `..` 会把整个媒体库写到 targetRoot 外面去。
 */
export function targetDirError(value?: string | null): string | null {
  const v = (value || '').trim()
  if (!v) return '目录名不能为空'
  if (v.length > TARGET_DIR_MAX) return `最多 ${TARGET_DIR_MAX} 个字符`
  if (ILLEGAL_DIR_CHARS.test(v)) return '不能包含 \\ / : * ? " < > |'
  if (v === '.' || v === '..') return '不能是 . 或 ..'
  return null
}

/** 条件字段的 CSV → 集合，大小写归一方式与后端 CategoryRule 一致（语言转小写、国家转大写） */
const conditionSet = (csv: string | null | undefined, norm: (s: string) => string) =>
  new Set((csv || '').split(',').map(s => norm(s.trim())).filter(Boolean))

/**
 * 找出永远命不中的规则：返回「被覆盖的下标 → 覆盖它的那条的下标」（都是 0 基）。
 *
 * 规则从上到下、命中即停，于是前面一条更宽的规则会让后面的规则彻底失效，而界面上看不出来——
 * 最常见的是把一条「什么条件都没填」的规则拖到了中间，它后面的所有规则（连同兜底）全死了。
 *
 * 判据只认**确定**覆盖的情况，宁可漏报不误报：前一条在三个维度上，每一维要么不限，
 * 要么是后一条那一维（且后一条那一维确实填了）的超集。这与后端 CategoryRule#matches 的
 * 语义对应——类型 / 国家是「有交集即命中」、语言是「在集合内即命中」，两者在子集关系下都单调。
 */
export function findShadowedRules(rules: CategoryRule[]): Map<number, number> {
  const dims = rules.map(r => [
    conditionSet(r.genreIds, s => s),
    conditionSet(r.originalLanguages, s => s.toLowerCase()),
    conditionSet(r.originCountries, s => s.toUpperCase())
  ])
  const covers = (i: number, j: number) => dims[i].every((wide, d) => {
    if (wide.size === 0) return true
    const narrow = dims[j][d]
    return narrow.size > 0 && [...narrow].every(x => wide.has(x))
  })
  const out = new Map<number, number>()
  for (let j = 1; j < rules.length; j++) {
    for (let i = 0; i < j; i++) {
      if (covers(i, j)) {
        out.set(j, i)
        break
      }
    }
  }
  return out
}

/** 给 v-text-field 的 :rules 用 */
export const targetDirRules = [(v: string) => targetDirError(v) ?? true]

/**
 * 规则列表的比较快照。null 与空串归一：库里存的是 NULL，用户加一个条件再删掉会变成 ''，
 * 两者语义都是「不限」，不归一的话会凭空报出一次「有未保存的修改」。
 */
const rulesSnapshot = (rules: CategoryRule[]) => JSON.stringify(rules.map(r => [
  r.targetDir || '', r.genreIds || '', r.originalLanguages || '', r.originCountries || '', r.isFallback
]))

export function useRenameConfig() {
  // ---- 文件名模板 ----
  const template = ref('')
  /** 后端内置的默认模板，供「恢复默认」用；接口拿不到时按空串处理，按钮自己会隐藏 */
  const defaultTemplate = ref('')
  const templateLoading = ref(false)
  const templateSaving = ref(false)
  /** 电影样例与剧集样例各一份：模板里全是 {% if season %} 分支，只看一份永远预览不到另一半 */
  const previewSamples = ref<TemplatePreview>({ movie: '', tv: '' })
  const previewError = ref('')
  const templateVariables = ref<TemplateVariable[]>(FALLBACK_VARIABLES)
  /** 最近一次与服务端一致的模板，用来判断编辑框里有没有没保存的改动 */
  const savedTemplate = ref('')

  const loadTemplate = async () => {
    templateLoading.value = true
    try {
      const data = await getRenameTemplateApi() as any
      template.value = data.template
      savedTemplate.value = data.template
      defaultTemplate.value = data.defaultTemplate || ''
      if (Array.isArray(data.variables) && data.variables.length) templateVariables.value = data.variables
      await doPreview()
    } catch (e) {
      // 具体错误文案已由 request.ts 的响应拦截器统一 toast 过了，这里不重复弹一次
      console.error('[重命名规则设置] 加载模板失败:', e)
    } finally {
      templateLoading.value = false
    }
  }

  let previewTimer: ReturnType<typeof setTimeout> | undefined
  /** 最近一次真正发出去的预览请求的序号，只有它的结果能写进 ref（防抖之外仍可能有两个请求在途） */
  let previewSeq = 0
  /** 上一次 doPreview 返回的 Promise 的 resolve：被防抖取消时必须放它走 */
  let pendingResolve: (() => void) | undefined

  const doPreview = () => {
    if (previewTimer) clearTimeout(previewTimer)
    // 被这一次调用取消掉的那次预览，请求根本没发出去，没有结果可等——不放行的话
    // 它的 Promise 永远不 resolve，await 它的 loadTemplate 会把 templateLoading 卡在 true
    pendingResolve?.()
    return new Promise<void>((resolve) => {
      pendingResolve = resolve
      previewTimer = setTimeout(async () => {
        const seq = ++previewSeq
        try {
          const rendered = await previewRenameTemplateApi(template.value) as any
          if (seq === previewSeq) {
            previewSamples.value = { movie: rendered?.movie ?? '', tv: rendered?.tv ?? '' }
            previewError.value = ''
          }
        } catch (e: any) {
          if (seq === previewSeq) {
            previewSamples.value = { movie: '', tv: '' }
            previewError.value = e?.message || '预览失败'
          }
        }
        pendingResolve = undefined
        resolve()
      }, 300)
    })
  }

  // 页面卸载后不要再发预览、也不要把在途结果写进没人看的 ref
  if (getCurrentScope()) {
    onScopeDispose(() => {
      if (previewTimer) clearTimeout(previewTimer)
      previewSeq++
      pendingResolve?.()
      pendingResolve = undefined
    })
  }

  /**
   * 把内置默认模板填回编辑框——只填不存，用户确认效果后自己点保存。
   * 这个配置项已从参数设置页隐藏，在这之前用户把模板改坏后没有任何界面途径改得回来。
   */
  const restoreDefaultTemplate = async () => {
    if (!defaultTemplate.value) return
    try {
      await confirm({
        title: '恢复默认模板',
        message: '当前编辑框里的模板会被默认模板覆盖，确认后仍需点「保存模板」才会生效。',
        confirmText: '恢复默认',
        type: 'warning'
      })
    } catch {
      return
    }
    template.value = defaultTemplate.value
    await doPreview()
    message.info('已填入默认模板，点「保存模板」后生效')
  }

  const saveTemplate = async () => {
    templateSaving.value = true
    try {
      const submitted = template.value
      await updateRenameTemplateApi(submitted)
      // 记提交出去的那一份，而不是现在编辑框里的：请求在途时用户可能又改了几个字
      savedTemplate.value = submitted
      message.success('模板保存成功')
    } catch (e) {
      // 具体错误文案（如"模板渲染失败：..."）已经由 request.ts 的响应拦截器统一 toast 过了，这里不重复弹一次
      console.error('[重命名规则设置] 保存模板失败:', e)
    } finally {
      templateSaving.value = false
    }
  }

  // ---- 分类规则 ----
  const movieRules = ref<CategoryRule[]>([])
  const tvRules = ref<CategoryRule[]>([])
  const rulesLoading = ref(false)
  /** 正在保存的是哪一侧（'movie' / 'tv'），空串表示空闲——两个保存按钮各转各的圈 */
  const savingRulesType = ref('')

  const listRef = (mediaType: string) => (mediaType === 'movie' ? movieRules : tvRules)

  /** 最近一次与服务端一致的规则快照，按侧存 */
  const savedRules = reactive<Record<string, string>>({ movie: rulesSnapshot([]), tv: rulesSnapshot([]) })

  /** 只拉一侧，供保存后回填使用 */
  const loadRulesFor = async (mediaType: string) => {
    const rules = (await getCategoryRulesApi(mediaType) as any) || []
    listRef(mediaType).value = rules
    savedRules[mediaType] = rulesSnapshot(rules)
  }

  const loadRules = async () => {
    rulesLoading.value = true
    try {
      await Promise.all([loadRulesFor('movie'), loadRulesFor('tv')])
    } catch (e) {
      // 具体错误文案已由 request.ts 的响应拦截器统一 toast 过了，这里不重复弹一次
      console.error('[重命名规则设置] 加载分类规则失败:', e)
    } finally {
      rulesLoading.value = false
    }
  }

  const addRule = (mediaType: string) => {
    const list = listRef(mediaType)
    // 列表为空时新增的这一条就是兜底规则：界面上没有任何「设为兜底」的入口，
    // 而后端要求恰好一条兜底——按普通规则建出来的话永远保存不上，是个死局
    const isFallback = list.value.length === 0 ? '1' : '0'
    const insertIndex = Math.max(list.value.length - 1, 0)
    list.value.splice(insertIndex, 0, {
      mediaType,
      targetDir: '',
      genreIds: '',
      originalLanguages: '',
      originCountries: '',
      isFallback
    })
  }

  const removeRule = (mediaType: string, index: number) => {
    const list = listRef(mediaType)
    if (list.value[index]?.isFallback === '1') return
    list.value.splice(index, 1)
  }

  const moveRule = (mediaType: string, index: number, direction: RuleMoveDirection) => {
    const list = listRef(mediaType)
    if (direction === 'top' || direction === 'bottom') {
      const arr = list.value
      if (arr[index]?.isFallback === '1') return
      // 兜底行永远在最后一位，「置底」是挪到它前面
      const lastMovable = arr.length - 1 - (arr[arr.length - 1]?.isFallback === '1' ? 1 : 0)
      const target = direction === 'top' ? 0 : lastMovable
      if (target === index) return
      const [row] = arr.splice(index, 1)
      arr.splice(target, 0, row)
      return
    }
    const target = index + direction
    if (target < 0 || target >= list.value.length) return
    // 兜底行必须保持最后一位，禁止把它移走、也禁止把别的行移到它后面
    if (list.value[index].isFallback === '1' || list.value[target].isFallback === '1') return
    const arr = list.value
    ;[arr[index], arr[target]] = [arr[target], arr[index]]
  }

  const saveRules = async (mediaType: string) => {
    const rules = listRef(mediaType).value
    const badIndex = rules.findIndex(r => targetDirError(r.targetDir))
    if (badIndex >= 0) {
      message.warning(`第 ${badIndex + 1} 条规则的目标目录名${targetDirError(rules[badIndex].targetDir)}`)
      return
    }
    savingRulesType.value = mediaType
    try {
      await saveCategoryRulesApi(mediaType, listRef(mediaType).value)
      message.success('分类规则保存成功')
      // 只回填刚保存的这一侧。整份重载会把另一侧尚未保存的编辑静默冲掉——
      // 「先改剧集、再改电影、点保存电影」是这页最自然的操作顺序，必然踩到
      await loadRulesFor(mediaType)
    } catch (e) {
      // 具体错误文案（如"必须保留且只能保留一条兜底规则"）已经由 request.ts 的响应拦截器统一 toast 过了，这里不重复弹一次
      console.error('[重命名规则设置] 保存分类规则失败:', e)
    } finally {
      savingRulesType.value = ''
    }
  }

  // ---- 重命名测试：拿一个文件名试跑模板，看解析结果 ----
  const testLoading = ref(false)
  const testResult = ref<any>(null)
  const testForm = reactive({ filename: '', template: '' })

  /**
   * 测试结果里与分类规则有关的那一栏：判定类型 / 命中的是第几条 / 完整目标路径。
   * 文案放在 composable 里，PC 与移动端各写一遍必然漂移。
   */
  const testPlacement = computed(() => {
    const r = testResult.value
    if (!r || !r.destPath) return null
    const ruleText = r.matchedRuleSeq
      ? (r.matchedRuleFallback
          ? `兜底规则（第 ${r.matchedRuleSeq}/${r.ruleCount} 条）`
          : `第 ${r.matchedRuleSeq}/${r.ruleCount} 条规则`)
      : (r.ruleCount ? '一条规则都没命中' : '尚未配置分类规则')
    return {
      mediaTypeText: r.mediaType === 'movie' ? '电影' : '剧集',
      ruleText,
      category: r.category as string,
      destPath: r.destPath as string
    }
  })

  /**
   * 「识别参数详情」要展示的行。原先把整个 MediaInfo 原样摊开：key 全是英文字段名、空值也各占一行，
   * 还有 metadata——TMDb 原始响应，一份 images 就 26KB，日志侧专门为它做过 @ToString.Exclude，
   * UI 上却又整块倒了出来。现在：中文说明取自变量清单（字段名与模板变量是同一张表），
   * 空值不列，metadata 只报拉到了哪几份。
   */
  const testInfoRows = computed(() => {
    const info = testResult.value?.info
    if (!info || typeof info !== 'object') return []
    const labels = new Map(templateVariables.value.map(v => [v.name, v.label]))
    const rows: { key: string; label: string | null; value: string }[] = []
    for (const [key, value] of Object.entries(info)) {
      if (key === 'metadata') continue
      const text = Array.isArray(value) ? value.join(', ') : value == null ? '' : String(value)
      if (!text) continue
      rows.push({ key, label: labels.get(key) ?? null, value: text })
    }
    const meta = (info as any).metadata
    if (meta && typeof meta === 'object' && Object.keys(meta).length) {
      rows.push({ key: 'metadata', label: 'TMDb 元数据', value: `已获取 ${Object.keys(meta).join('、')}` })
    }
    return rows
  })

  /**
   * 把编辑框里（可能还没保存）的模板填进测试的模板框。留空时后端用的是**已保存**的模板，
   * 原先想试一下手上正在改的模板只能手动复制过去。
   */
  const fillTestTemplate = () => {
    testForm.template = template.value
  }

  const doTest = async () => {
    if (!testForm.filename.trim()) {
      message.warning('请输入文件名')
      return
    }
    testLoading.value = true
    try {
      testResult.value = await testParseRenameApi(testForm.filename, testForm.template || undefined) as any
      message.success('分析成功')
    } catch (e) {
      // 后端返回的「解析失败: xxx」带着原因，已由 request.ts 的响应拦截器 toast 过，
      // 这里再弹一句无信息量的「请求失败」只会把它盖掉
      console.error('[重命名规则设置] 测试解析失败:', e)
    } finally {
      testLoading.value = false
    }
  }

  // ---- 未保存的修改 ----
  const templateDirty = computed(() => template.value !== savedTemplate.value)
  const movieRulesDirty = computed(() => rulesSnapshot(movieRules.value) !== savedRules.movie)
  const tvRulesDirty = computed(() => rulesSnapshot(tvRules.value) !== savedRules.tv)
  const rulesDirty = computed(() => movieRulesDirty.value || tvRulesDirty.value)
  const anyDirty = computed(() => templateDirty.value || rulesDirty.value)

  /** 说清楚是哪几份没保存，「有未保存的修改」这句话本身不够用户决定要不要回去 */
  const dirtySummary = computed(() => [
    templateDirty.value && '文件名模板',
    movieRulesDirty.value && '电影分类规则',
    tvRulesDirty.value && '剧集分类规则'
  ].filter(Boolean).join('、'))

  // 这个页面没有 keep-alive，离开即卸载，没保存的编辑就此丢掉。
  // 测试里直接调用、不在组件 setup 里时不挂（onBeforeRouteLeave 需要组件实例）
  if (getCurrentInstance()) {
    onBeforeRouteLeave(async () => {
      if (!anyDirty.value) return true
      try {
        await confirm({
          title: '有未保存的修改',
          message: `${dirtySummary.value}还没有保存，离开后这些修改会丢失。`,
          confirmText: '仍然离开',
          cancelText: '留在本页',
          type: 'warning'
        })
        return true
      } catch {
        return false
      }
    })
  }

  // 刷新 / 关标签页走的是浏览器自己的确认框，文案由浏览器决定
  const onBeforeUnload = (e: BeforeUnloadEvent) => {
    if (!anyDirty.value) return
    e.preventDefault()
    e.returnValue = ''
  }
  if (getCurrentScope() && typeof window !== 'undefined') {
    window.addEventListener('beforeunload', onBeforeUnload)
    onScopeDispose(() => window.removeEventListener('beforeunload', onBeforeUnload))
  }

  loadTemplate()
  loadRules()

  return {
    template, defaultTemplate, templateLoading, templateSaving, previewSamples, previewError, templateVariables,
    doPreview, saveTemplate, restoreDefaultTemplate,
    movieRules, tvRules, rulesLoading, savingRulesType,
    addRule, removeRule, moveRule, saveRules,
    templateDirty, movieRulesDirty, tvRulesDirty, rulesDirty, anyDirty,
    testLoading, testResult, testForm, testPlacement, testInfoRows, fillTestTemplate, doTest
  }
}
