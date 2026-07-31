import { ref } from 'vue'
import { message } from '@/composables/useMessage'
import {
  getRenameTemplateApi,
  previewRenameTemplateApi,
  updateRenameTemplateApi,
  getCategoryRulesApi,
  saveCategoryRulesApi,
  type CategoryRule
} from '@/api/openlist/renameConfig'

/** 模板里可用的变量参考，点击插入到文本框光标处 */
export const TEMPLATE_VARIABLES = [
  'title', 'year', 'season', 'episode', 'resolution',
  'source', 'videoCodec', 'audioCodec', 'tags', 'releaseGroup', 'extension'
]

export function useRenameConfig() {
  // ---- 文件名模板 ----
  const template = ref('')
  const templateLoading = ref(false)
  const templateSaving = ref(false)
  const previewResult = ref('')
  const previewError = ref('')

  const loadTemplate = async () => {
    templateLoading.value = true
    try {
      const data = await getRenameTemplateApi() as any
      template.value = data.template
      await doPreview()
    } catch (e) {
      console.error('[重命名规则设置] 加载模板失败:', e)
      message.error('加载模板失败')
    } finally {
      templateLoading.value = false
    }
  }

  let previewTimer: ReturnType<typeof setTimeout> | undefined
  const doPreview = async () => {
    if (previewTimer) clearTimeout(previewTimer)
    return new Promise<void>((resolve) => {
      previewTimer = setTimeout(async () => {
        try {
          previewResult.value = await previewRenameTemplateApi(template.value) as any
          previewError.value = ''
        } catch (e: any) {
          previewResult.value = ''
          previewError.value = e?.message || '预览失败'
        }
        resolve()
      }, 300)
    })
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
  const rulesSaving = ref(false)

  const loadRules = async () => {
    rulesLoading.value = true
    try {
      movieRules.value = await getCategoryRulesApi('movie') as any
      tvRules.value = await getCategoryRulesApi('tv') as any
    } catch (e) {
      console.error('[重命名规则设置] 加载分类规则失败:', e)
      message.error('加载分类规则失败')
    } finally {
      rulesLoading.value = false
    }
  }

  const listRef = (mediaType: string) => (mediaType === 'movie' ? movieRules : tvRules)

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
    rulesSaving.value = true
    try {
      await saveCategoryRulesApi(mediaType, listRef(mediaType).value)
      message.success('分类规则保存成功')
      await loadRules()
    } catch (e) {
      // 具体错误文案（如"必须保留且只能保留一条兜底规则"）已经由 request.ts 的响应拦截器统一 toast 过了，这里不重复弹一次
      console.error('[重命名规则设置] 保存分类规则失败:', e)
    } finally {
      rulesSaving.value = false
    }
  }

  loadTemplate()
  loadRules()

  return {
    template, templateLoading, templateSaving, previewResult, previewError,
    doPreview, saveTemplate,
    movieRules, tvRules, rulesLoading, rulesSaving,
    addRule, removeRule, moveRule, saveRules
  }
}
