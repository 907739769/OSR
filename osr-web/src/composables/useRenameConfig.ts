import { ref, reactive, computed, getCurrentScope, onScopeDispose } from 'vue'
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

export function useRenameConfig() {
  // ---- 文件名模板 ----
  const template = ref('')
  /** 后端内置的默认模板，供「恢复默认」用；接口拿不到时按空串处理，按钮自己会隐藏 */
  const defaultTemplate = ref('')
  const templateLoading = ref(false)
  const templateSaving = ref(false)
  const previewResult = ref('')
  const previewError = ref('')

  const loadTemplate = async () => {
    templateLoading.value = true
    try {
      const data = await getRenameTemplateApi() as any
      template.value = data.template
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
      await updateRenameTemplateApi(template.value)
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

  /** 只拉一侧，供保存后回填使用 */
  const loadRulesFor = async (mediaType: string) => {
    listRef(mediaType).value = await getCategoryRulesApi(mediaType) as any
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
    const insertIndex = Math.max(list.value.length - 1, 0)
    list.value.splice(insertIndex, 0, {
      mediaType,
      targetDir: '',
      genreIds: '',
      originalLanguages: '',
      originCountries: '',
      isFallback: '0'
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

  loadTemplate()
  loadRules()

  return {
    template, defaultTemplate, templateLoading, templateSaving, previewResult, previewError,
    doPreview, saveTemplate, restoreDefaultTemplate,
    movieRules, tvRules, rulesLoading, savingRulesType,
    addRule, removeRule, moveRule, saveRules,
    testLoading, testResult, testForm, testPlacement, doTest
  }
}
