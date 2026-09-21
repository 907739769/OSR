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
  type CategoryRule
} from '@/api/openlist/renameConfig'
import { testParseRenameApi } from '@/api/openlist/renameTask'

/** 模板里可用的变量参考，点击插入到文本框光标处 */
export const TEMPLATE_VARIABLES = [
  'title', 'year', 'season', 'episode', 'resolution',
  'source', 'videoCodec', 'audioCodec', 'tags', 'releaseGroup', 'extension'
]

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
  const previewResult = ref('')
  const previewError = ref('')
  /** 最近一次与服务端一致的模板，用来判断编辑框里有没有没保存的改动 */
  const savedTemplate = ref('')

  const loadTemplate = async () => {
    templateLoading.value = true
    try {
      const data = await getRenameTemplateApi() as any
      template.value = data.template
      savedTemplate.value = data.template
      defaultTemplate.value = data.defaultTemplate || ''
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
            previewResult.value = rendered
            previewError.value = ''
          }
        } catch (e: any) {
          if (seq === previewSeq) {
            previewResult.value = ''
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

  const moveRule = (mediaType: string, index: number, direction: -1 | 1) => {
    const list = listRef(mediaType)
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
    template, defaultTemplate, templateLoading, templateSaving, previewResult, previewError,
    doPreview, saveTemplate, restoreDefaultTemplate,
    movieRules, tvRules, rulesLoading, savingRulesType,
    addRule, removeRule, moveRule, saveRules,
    templateDirty, movieRulesDirty, tvRulesDirty, rulesDirty, anyDirty,
    testLoading, testResult, testForm, testPlacement, doTest
  }
}
