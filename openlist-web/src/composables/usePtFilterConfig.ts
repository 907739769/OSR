import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getPtFilterConfigApi,
  updatePtFilterConfigApi,
  getSortDimensionsApi,
  type PtFilterConfig
} from '@/api/openlist/ptFilterConfig'

/** 各排序维度的中文说明，键必须与后端 SortDimension 枚举名一致 */
const DIMENSION_LABELS: Record<string, string> = {
  RESOLUTION: '分辨率优先级',
  FREE: '促销优先（计量系数越低越优）',
  SEEDERS: '做种数（多者优先）',
  SIZE: '体积接近偏好值'
}

/**
 * PT 全局过滤与排序规则 composable
 */
export function usePtFilterConfig() {
  const loading = ref(false)
  const saving = ref(false)
  const formRef = ref<any>()

  /** 1 GB = 1073741824 字节 */
  const GB = 1073741824

  const sizeFields = new Set(['minSize', 'maxSize', 'preferredSize'] as const)

  const form = reactive<PtFilterConfig>({
    minSeeders: 1,
    minSize: 0,
    maxSize: 0,
    freeOnly: '0',
    includeKeywords: '',
    excludeKeywords: '',
    resolutionPriority: '',
    resolutionWhitelist: '',
    sortPriority: '',
    preferredSize: 0,
    requireChineseSubtitle: '0'
  })

  /** 排序维度的当前顺序，用有序数组承载，提交时拼成逗号分隔串 */
  const sortOrder = ref<string[]>([])
  const allDimensions = ref<string[]>([])

  const rules = {
    minSeeders: [{ required: true, message: '最低做种数不能为空', trigger: 'blur' }]
  }

  const labelOf = (dimension: string) => DIMENSION_LABELS[dimension] || dimension

  const load = async () => {
    loading.value = true
    try {
      const [config, dimensions] = await Promise.all([
        getPtFilterConfigApi(),
        getSortDimensionsApi()
      ])
      // 体积字段后端存字节，前端显示 GB
      const gbConfig = { ...config }
      for (const field of sizeFields) {
        gbConfig[field] = Math.round((config[field] || 0) / GB)
      }
      Object.assign(form, gbConfig)
      allDimensions.value = dimensions || []
      // 已配置的在前保持原顺序，未出现在配置里的补到末尾，避免新增维度后消失
      const configured = (config.sortPriority || '')
        .split(',')
        .map((s: string) => s.trim())
        .filter((s: string) => s && allDimensions.value.includes(s))
      const rest = allDimensions.value.filter((d) => !configured.includes(d))
      sortOrder.value = [...configured, ...rest]
    } catch (e) {
      console.error(e)
    } finally {
      loading.value = false
    }
  }

  /** 把某个维度上移一位 */
  const moveUp = (index: number) => {
    if (index <= 0) return
    const arr = sortOrder.value
    ;[arr[index - 1], arr[index]] = [arr[index], arr[index - 1]]
  }

  /** 把某个维度下移一位 */
  const moveDown = (index: number) => {
    const arr = sortOrder.value
    if (index >= arr.length - 1) return
    ;[arr[index], arr[index + 1]] = [arr[index + 1], arr[index]]
  }

  const save = async () => {
    if (formRef.value) {
      await formRef.value.validate()
    }
    saving.value = true
    try {
      // 体积字段前端显示 GB，后端存字节
      const bytesForm: any = { ...form, sortPriority: sortOrder.value.join(',') }
      for (const field of sizeFields) {
        bytesForm[field] = (form[field] || 0) * GB
      }
      await updatePtFilterConfigApi(bytesForm)
      ElMessage.success('保存成功')
      await load()
    } catch (e) {
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  load()

  return { loading, saving, formRef, form, rules, sortOrder, allDimensions, labelOf, moveUp, moveDown, load, save }
}
