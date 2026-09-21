import { ref, reactive, computed, watch, getCurrentInstance, getCurrentScope, onScopeDispose } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import {
  getPtUpgradeConfigApi,
  updatePtUpgradeConfigApi,
  getQualityDimensionsApi,
  getUpgradeOverviewApi,
  checkPtUpgradeConfigApi,
  triggerUpgradeScanApi,
  type PtUpgradeConfig,
  type UpgradeOverview
} from '@/api/openlist/ptUpgradeConfig'
import { getFilterVocabularyApi, type FilterVocabulary } from '@/api/openlist/ptFilterConfig'
import { mergeOrder, joinCsv, splitCsv } from '@/composables/orderList'
import type { FieldRule } from '@/composables/formRules'

/** 各洗版维度的中文说明，键必须与后端 UpgradeDimension 枚举名一致 */
const DIMENSION_LABELS: Record<string, string> = {
  RESOLUTION: '分辨率',
  SOURCE: '媒介来源（Remux/BluRay/WEB-DL…）',
  TAG: '质量标签（还差几个目标标签）',
  RELEASE_GROUP: '发布组'
}

/** 手动扫描后隔多久回来刷一次概览：扫描在后台跑，一轮通常十几秒到几分钟 */
const SCAN_REFRESH_DELAY = 5000

/**
 * PT 洗版规则 composable
 */
export function usePtUpgradeConfig() {
  const loading = ref(false)
  const saving = ref(false)
  const formRef = ref<any>()

  const form = reactive<PtUpgradeConfig>({
    enabled: '0',
    qualityPriority: '',
    targetResolution: '',
    targetSources: '',
    targetTags: '',
    maxConcurrent: 2,
    maxSearchesPerRound: 20,
    scanIntervalHours: 6
  })

  /** 维度的当前顺序，用有序数组承载，提交时拼成逗号分隔串 */
  const dimensionOrder = ref<string[]>([])
  const allDimensions = ref<string[]>([])
  const vocabulary = ref<FilterVocabulary>({ resolutions: [], sources: [], tags: [] })

  const rules: Record<string, FieldRule[]> = {
    maxConcurrent: [{ required: true, type: 'number', min: 1, max: 20, message: '须在 1~20 之间' }],
    maxSearchesPerRound: [{ required: true, type: 'number', min: 1, max: 500, message: '须在 1~500 之间' }],
    scanIntervalHours: [{ required: true, type: 'number', min: 1, max: 168, message: '须在 1~168 小时之间' }]
  }

  const labelOf = (dimension: string) => DIMENSION_LABELS[dimension] || dimension

  /** 没配任何目标质量时 cutoff 恒成立，洗版实际不会发生——开着总开关也一样 */
  const hasTarget = () =>
    !!(form.targetResolution || splitCsv(form.targetSources).length || splitCsv(form.targetTags).length)

  /**
   * 提交给后端的形态。下拉清空后是 null，而后端按 MyBatis-Plus 默认策略**跳过 null 字段**，
   * 原样发过去的话「清空目标分辨率」存不进去——保存成功、刷新后值又回来了
   */
  const toPayload = (): PtUpgradeConfig => ({
    ...form,
    targetResolution: form.targetResolution || '',
    targetSources: form.targetSources || '',
    targetTags: form.targetTags || '',
    qualityPriority: joinCsv(dimensionOrder.value)
  })

  // ---- 未保存修改 ----
  const snapshot = () => JSON.stringify(toPayload())
  const savedSnapshot = ref('')
  const isDirty = computed(() => !loading.value && savedSnapshot.value !== '' && snapshot() !== savedSnapshot.value)

  // ---- 状态概览 ----
  const overview = ref<UpgradeOverview | null>(null)
  const overviewLoading = ref(false)
  const scanning = ref(false)

  const loadOverview = async () => {
    overviewLoading.value = true
    try {
      overview.value = await getUpgradeOverviewApi()
    } catch (e) {
      console.error(e)
    } finally {
      overviewLoading.value = false
    }
  }

  // ---- 一致性诊断：边改边查，对照的是已保存的过滤规则 ----
  const problems = ref<string[]>([])
  let checkTimer: ReturnType<typeof setTimeout> | undefined
  const runCheck = async () => {
    try {
      problems.value = await checkPtUpgradeConfigApi(toPayload())
    } catch (e) {
      console.error(e)
    }
  }
  watch(
    () => [form.targetResolution, form.targetSources, form.targetTags, dimensionOrder.value.join(',')],
    () => {
      if (loading.value) return
      clearTimeout(checkTimer)
      checkTimer = setTimeout(runCheck, 400)
    }
  )

  const load = async () => {
    loading.value = true
    try {
      const [config, dimensions, vocab] = await Promise.all([
        getPtUpgradeConfigApi(),
        getQualityDimensionsApi(),
        getFilterVocabularyApi()
      ])
      Object.assign(form, config)
      allDimensions.value = dimensions || []
      vocabulary.value = vocab || vocabulary.value
      dimensionOrder.value = mergeOrder(config.qualityPriority, allDimensions.value)
      savedSnapshot.value = snapshot()
    } catch (e) {
      console.error(e)
    } finally {
      loading.value = false
    }
    await Promise.all([loadOverview(), runCheck()])
  }

  const save = async () => {
    if (formRef.value) {
      const result = await formRef.value.validate()
      if (result && typeof result === 'object' && 'valid' in result && !result.valid) return
    }
    // 开着总开关却没配目标质量，后端会按"不激活"处理，用户会以为开了却什么都没发生
    if (form.enabled === '1' && !hasTarget()) {
      message.error('开启洗版前必须至少配置一项目标质量，否则不会有任何集被判定为需要升级')
      return
    }
    saving.value = true
    try {
      // 与过滤规则页的优先级对不上时后端会拒绝并说明原因（request.ts 已 toast）
      await updatePtUpgradeConfigApi(toPayload())
      message.success('保存成功')
      await load()
    } catch (e) {
      console.error(e)
    } finally {
      saving.value = false
    }
  }

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

  /** 立即扫描一轮。扫描按已保存的规则跑，有未保存的修改时先提示 */
  const scanNow = async () => {
    if (isDirty.value) {
      message.warning('扫描按已保存的规则进行，请先保存当前修改')
      return
    }
    scanning.value = true
    try {
      await triggerUpgradeScanApi()
      message.success('已开始扫描，结果稍后显示在上方')
      setTimeout(loadOverview, SCAN_REFRESH_DELAY)
    } catch (e) {
      console.error(e)
    } finally {
      scanning.value = false
    }
  }

  if (getCurrentInstance()) {
    onBeforeRouteLeave(async () => {
      if (!isDirty.value) return true
      try {
        await confirm({
          title: '有未保存的修改',
          message: '洗版规则还没有保存，离开后这些修改会丢失。',
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
  const onBeforeUnload = (e: BeforeUnloadEvent) => {
    if (!isDirty.value) return
    e.preventDefault()
    e.returnValue = ''
  }
  if (getCurrentScope()) {
    if (typeof window !== 'undefined') {
      window.addEventListener('beforeunload', onBeforeUnload)
      onScopeDispose(() => window.removeEventListener('beforeunload', onBeforeUnload))
    }
    onScopeDispose(() => clearTimeout(checkTimer))
  }

  load()

  return {
    loading, saving, formRef, form, rules, dimensionOrder, allDimensions, vocabulary,
    labelOf, hasTarget, load, save, discard, isDirty,
    overview, overviewLoading, loadOverview, scanning, scanNow, problems
  }
}
