import { ref, reactive } from 'vue'
import { message } from '@/composables/useMessage'
import {
  getPtUpgradeConfigApi,
  updatePtUpgradeConfigApi,
  getQualityDimensionsApi,
  type PtUpgradeConfig
} from '@/api/openlist/ptUpgradeConfig'

/** 各洗版维度的中文说明，键必须与后端 UpgradeDimension 枚举名一致 */
const DIMENSION_LABELS: Record<string, string> = {
  RESOLUTION: '分辨率',
  SOURCE: '媒介来源（Remux/BluRay/WEB-DL…）',
  TAG: '目标质量标签的欠缺程度',
  RELEASE_GROUP: '发布组'
}

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
    scanIntervalHours: 6
  })

  /** 维度的当前顺序，用有序数组承载，提交时拼成逗号分隔串 */
  const dimensionOrder = ref<string[]>([])
  const allDimensions = ref<string[]>([])

  const labelOf = (dimension: string) => DIMENSION_LABELS[dimension] || dimension

  /** 没配任何目标质量时 cutoff 恒成立，洗版实际不会发生——开着总开关也一样 */
  const hasTarget = () =>
    !!(form.targetResolution || form.targetSources || form.targetTags)

  const load = async () => {
    loading.value = true
    try {
      const [config, dimensions] = await Promise.all([
        getPtUpgradeConfigApi(),
        getQualityDimensionsApi()
      ])
      Object.assign(form, config)
      allDimensions.value = dimensions || []
      // 已配置的在前保持原顺序，未出现在配置里的补到末尾，避免新增维度后消失
      const configured = (config.qualityPriority || '')
        .split(',')
        .map((s: string) => s.trim())
        .filter((s: string) => s && allDimensions.value.includes(s))
      const rest = allDimensions.value.filter((d) => !configured.includes(d))
      dimensionOrder.value = [...configured, ...rest]
    } catch (e) {
      console.error(e)
    } finally {
      loading.value = false
    }
  }

  const moveUp = (index: number) => {
    if (index <= 0) return
    const arr = dimensionOrder.value
    ;[arr[index - 1], arr[index]] = [arr[index], arr[index - 1]]
  }

  const moveDown = (index: number) => {
    const arr = dimensionOrder.value
    if (index >= arr.length - 1) return
    ;[arr[index], arr[index + 1]] = [arr[index + 1], arr[index]]
  }

  const save = async () => {
    // 开着总开关却没配目标质量，后端会按"不激活"处理，用户会以为开了却什么都没发生
    if (form.enabled === '1' && !hasTarget()) {
      message.error('开启洗版前必须至少配置一项目标质量，否则不会有任何集被判定为需要升级')
      return
    }
    saving.value = true
    try {
      await updatePtUpgradeConfigApi({ ...form, qualityPriority: dimensionOrder.value.join(',') })
      message.success('保存成功')
      await load()
    } catch (e) {
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  load()

  return {
    loading, saving, formRef, form, dimensionOrder, allDimensions,
    labelOf, hasTarget, moveUp, moveDown, load, save
  }
}
