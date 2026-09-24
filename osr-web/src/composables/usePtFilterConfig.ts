import { ref, reactive, computed, getCurrentInstance, getCurrentScope, onScopeDispose } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import {
  getPtFilterConfigApi,
  updatePtFilterConfigApi,
  getSortDimensionsApi,
  getFilterVocabularyApi,
  previewPtFilterApi,
  replayPtFilterApi,
  type PtFilterConfig,
  type FilterVocabulary,
  type FilterPreviewResult,
  type FilterReplayResult
} from '@/api/openlist/ptFilterConfig'
import { bytesToGb, gbToBytes } from '@/composables/sizeUnits'
import { mergeOrder, joinCsv } from '@/composables/orderList'
import type { FieldRule } from '@/composables/formRules'

/** 各排序维度的中文说明，键必须与后端 SortDimension 枚举名一致 */
const DIMENSION_LABELS: Record<string, string> = {
  RESOLUTION: '分辨率优先级',
  SOURCE: '媒介来源优先级（Remux/BluRay/WEB-DL…）',
  FREE: '促销优先（计量系数越低越优）',
  SEEDERS: '做种数（多者优先）',
  HR: 'H&R 规避（无保种考核的站点优先）',
  RELEASE_GROUP: '发布组优先级',
  SIZE: '体积接近偏好值'
}

const SIZE_FIELDS = ['minSize', 'maxSize', 'preferredSize'] as const

/**
 * PT 全局过滤与排序规则 composable
 */
export function usePtFilterConfig() {
  const loading = ref(false)
  const saving = ref(false)
  const formRef = ref<any>()

  const form = reactive<PtFilterConfig>({
    minSeeders: 1,
    minSize: 0,
    maxSize: 0,
    freeOnly: '0',
    includeKeywords: '',
    excludeKeywords: '',
    descriptionExcludeKeywords: '',
    resolutionPriority: '',
    resolutionWhitelist: '',
    sourceWhitelist: '',
    sourcePriority: '',
    requiredTags: '',
    excludeTags: '',
    releaseGroupPriority: '',
    sortPriority: '',
    preferredSize: 0,
    sizePerEpisode: '1',
    requireChineseSubtitle: '0',
    avoidHitAndRun: '0'
  })

  /** 排序维度的当前顺序，用有序数组承载，提交时拼成逗号分隔串 */
  const sortOrder = ref<string[]>([])
  const allDimensions = ref<string[]>([])
  const vocabulary = ref<FilterVocabulary>({ resolutions: [], sources: [], tags: [] })

  const rules: Record<string, FieldRule[]> = {
    minSeeders: [{ required: true, type: 'number', min: 0, message: '最低做种数不能为空，且不能小于 0' }],
    size: [{ type: 'number', min: 0, message: '不能为负数，0 表示不限' }]
  }

  /** 下限大于上限时所有种子都会被淘汰，界面上又什么都看不出来——在输入框上直接标出来 */
  const sizeRangeError = computed(() =>
    Number(form.minSize) > 0 && Number(form.maxSize) > 0 && Number(form.minSize) > Number(form.maxSize)
      ? '体积下限大于上限，所有种子都会被淘汰'
      : ''
  )

  const labelOf = (dimension: string) => DIMENSION_LABELS[dimension] || dimension

  // ---- 未保存修改 ----
  /** 与提交内容同形的快照：表单 + 排序维度顺序 */
  const snapshot = () => JSON.stringify({ ...form, sortPriority: joinCsv(sortOrder.value) })
  const savedSnapshot = ref('')
  const isDirty = computed(() => !loading.value && savedSnapshot.value !== '' && snapshot() !== savedSnapshot.value)

  const load = async () => {
    loading.value = true
    try {
      const [config, dimensions, vocab] = await Promise.all([
        getPtFilterConfigApi(),
        getSortDimensionsApi(),
        getFilterVocabularyApi()
      ])
      // 体积字段后端存字节，前端显示 GB（保留小数，见 sizeUnits）
      const gbConfig = { ...config }
      for (const field of SIZE_FIELDS) {
        gbConfig[field] = bytesToGb(config[field])
      }
      Object.assign(form, gbConfig)
      allDimensions.value = dimensions || []
      vocabulary.value = vocab || vocabulary.value
      sortOrder.value = mergeOrder(config.sortPriority, allDimensions.value)
      savedSnapshot.value = snapshot()
    } catch (e) {
      console.error(e)
    } finally {
      loading.value = false
    }
  }

  /** 提交给后端的形态：体积换回字节、排序维度拼成串 */
  const toPayload = (): PtFilterConfig => {
    const payload: any = { ...form, sortPriority: joinCsv(sortOrder.value) }
    for (const field of SIZE_FIELDS) {
      payload[field] = gbToBytes(form[field])
    }
    return payload
  }

  const save = async () => {
    if (formRef.value) {
      const result = await formRef.value.validate()
      if (result && typeof result === 'object' && 'valid' in result && !result.valid) return
    }
    if (sizeRangeError.value) {
      message.error(sizeRangeError.value)
      return
    }
    saving.value = true
    try {
      // 校验不通过时后端会把原因原样返回（request.ts 已 toast），比如这次修改会让洗版规则失效
      await updatePtFilterConfigApi(toPayload())
      message.success('保存成功')
      await load()
    } catch (e) {
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  /** 放弃未保存的修改，回到服务端的版本 */
  const discard = async () => {
    if (isDirty.value) {
      try {
        await confirm({
          title: '放弃修改',
          message: '页面上未保存的修改会丢失，恢复成已保存的规则。',
          confirmText: '放弃修改',
          cancelText: '继续编辑',
          type: 'warning'
        })
      } catch {
        return
      }
    }
    await load()
  }

  // ---- 规则试算 ----
  const previewForm = reactive({
    title: '',
    description: '',
    sizeGb: 0 as number | string,
    seeders: 10 as number | string,
    free: false,
    hitAndRun: false,
    foreignMovie: false
  })
  const previewing = ref(false)
  const previewResult = ref<FilterPreviewResult | null>(null)

  /** 拿页面上**当前（可能未保存）**的规则试算，用户能先试再存 */
  const runPreview = async () => {
    if (!previewForm.title.trim()) {
      message.warning('请输入种子标题')
      return
    }
    previewing.value = true
    try {
      previewResult.value = await previewPtFilterApi({
        title: previewForm.title,
        description: previewForm.description || undefined,
        size: gbToBytes(Number(previewForm.sizeGb) || 0),
        seeders: Number(previewForm.seeders) || 0,
        free: previewForm.free,
        hitAndRun: previewForm.hitAndRun,
        foreignMovie: previewForm.foreignMovie,
        config: toPayload()
      })
    } catch (e) {
      console.error(e)
    } finally {
      previewing.value = false
    }
  }

  // ---------- 历史回放 ----------

  const replayDays = ref(7)
  const replaying = ref(false)
  const replayResult = ref<FilterReplayResult | null>(null)

  /** 拿最近搜到过的候选，比较「已保存的规则」与「当前编辑中的草稿」——没改动时结论必然全部相同 */
  const runReplay = async () => {
    replaying.value = true
    try {
      replayResult.value = await replayPtFilterApi(toPayload(), replayDays.value)
    } catch (e) {
      console.error(e)
    } finally {
      replaying.value = false
    }
  }

  // 这个页面没有 keep-alive，离开即卸载，没保存的编辑就此丢掉。
  // 测试里直接调用、不在组件 setup 里时不挂（onBeforeRouteLeave 需要组件实例）
  if (getCurrentInstance()) {
    onBeforeRouteLeave(async () => {
      if (!isDirty.value) return true
      try {
        await confirm({
          title: '有未保存的修改',
          message: '过滤规则还没有保存，离开后这些修改会丢失。',
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
    if (!isDirty.value) return
    e.preventDefault()
    e.returnValue = ''
  }
  if (getCurrentScope() && typeof window !== 'undefined') {
    window.addEventListener('beforeunload', onBeforeUnload)
    onScopeDispose(() => window.removeEventListener('beforeunload', onBeforeUnload))
  }

  load()

  return {
    loading, saving, formRef, form, rules, sizeRangeError, sortOrder, allDimensions, vocabulary,
    labelOf, load, save, discard, isDirty,
    previewForm, previewing, previewResult, runPreview,
    replayDays, replaying, replayResult, runReplay
  }
}
