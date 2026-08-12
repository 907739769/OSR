import { ref, computed, reactive, watch } from 'vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { useTaskList } from './useTaskList'
import {
  getStrmTaskListApi,
  addStrmTaskApi,
  updateStrmTaskApi,
  deleteStrmTaskApi,
  batchDeleteStrmTaskApi,
  executeStrmTaskApi
} from '@/api/openlist/strmTask'
import type { SearchParams } from '@/types'

interface StrmTaskQuery extends SearchParams {
  strmTaskPath?: string
  strmTaskStatus?: string
}

/**
 * STRM 任务共享 composable
 * PC 端和移动端共享列表、CRUD、搜索逻辑
 */
export function useStrmTask() {
  const base = useTaskList<StrmTaskQuery>({
    listApi: getStrmTaskListApi,
    addApi: addStrmTaskApi,
    updateApi: updateStrmTaskApi,
    deleteApi: deleteStrmTaskApi,
    batchDeleteApi: batchDeleteStrmTaskApi,
    executeApi: executeStrmTaskApi,
    idField: 'strmTaskId',
    initForm: () => ({
      strmTaskId: undefined,
      strmTaskPath: undefined,
      strmTaskStatus: '1',
      strmOverride: undefined
    }),
    rules: {
      strmTaskPath: [{ required: true, message: 'STRM目录不能为空', trigger: 'blur' }]
    },
    defaultQuery: {
      strmTaskStatus: undefined
    }
  })

  // 移动端 - 分页辅助
  const totalPages = computed(() => Math.ceil(base.total.value / base.queryParams.pageSize) || 1)

  const prevPage = () => {
    if (base.queryParams.pageNum > 1) {
      base.queryParams.pageNum--
      base.getList()
    }
  }

  const nextPage = () => {
    if (base.queryParams.pageNum < totalPages.value) {
      base.queryParams.pageNum++
      base.getList()
    }
  }

  const handleSizeChange = () => {
    base.queryParams.pageNum = 1
    base.getList()
  }

  // 移动端 - 搜索面板折叠
  const searchCollapsed = ref(true)

  // 批量执行
  const handleBatchExecute = async () => {
    try {
      await confirm({
        message: `是否确认批量执行选中的 ${base.selectedIds.value.length} 个STRM任务？`,
        title: '提示', type: 'warning'
      })
      await executeStrmTaskApi(base.selectedIds.value)
      message.success('批量执行成功')
      base.getList()
    } catch (e) { if (e !== 'cancel') console.error(e) }
  }

  // --- 任务级配置覆盖 ---
  // 不勾选的项沿用全局配置：后端 StrmSettingsFactory 只认「出现在 JSON 里的键」，
  // 与 pt_subscription.filter_override 同一套约定
  const overrideForm = reactive({
    outputDir: { enabled: false, value: '' as string },
    downloadSub: { enabled: false, value: '0' as string },
    minFileSize: { enabled: false, value: 0 as number }
  })

  type OverrideKey = keyof typeof overrideForm
  const OVERRIDE_KEYS = Object.keys(overrideForm) as OverrideKey[]
  const OVERRIDE_DEFAULTS: Record<OverrideKey, string | number> = {
    outputDir: '',
    downloadSub: '0',
    minFileSize: 0
  }

  /**
   * 开关值归一成单选框认识的 '0'/'1'。
   * 后端存的是字符串，但覆盖 JSON 是可以手改的，历史数据里出现原生布尔值时
   * 直接塞给 v-radio-group 会两个选项都不选中，看起来像「没配过」。
   */
  const normalizeSwitch = (value: any): string => (value === true || value === 'true' || value === '1') ? '1' : '0'

  /**
   * 弹窗每次打开时把 form 里的 JSON 摊回勾选表单。
   * 监听 form 本身而不是 open：handleUpdate 是异步的（先拉一次列表再回填），
   * open 置 true 的时刻 form 未必已经是目标行；而 handleAdd/handleUpdate 都会给
   * form.value 赋一个新对象，盯住它的身份变化恰好每次打开触发一次。
   */
  watch(() => base.form.value, (current: any) => {
    OVERRIDE_KEYS.forEach((key) => {
      overrideForm[key].enabled = false
      ;(overrideForm[key].value as any) = OVERRIDE_DEFAULTS[key]
    })
    let parsed: Record<string, any> = {}
    if (current?.strmOverride) {
      try {
        parsed = JSON.parse(current.strmOverride) || {}
      } catch (e) {
        console.error('解析STRM任务覆盖失败，按未设置覆盖处理', e)
      }
    }
    OVERRIDE_KEYS.forEach((key) => {
      if (!Object.prototype.hasOwnProperty.call(parsed, key)) return
      overrideForm[key].enabled = true
      ;(overrideForm[key].value as any) = key === 'downloadSub'
        ? normalizeSwitch(parsed[key])
        : parsed[key]
    })
  })

  /** 某行是否配了任务级覆盖，列表里据此显示标记 */
  const hasOverride = (row: any): boolean => {
    if (!row?.strmOverride) return false
    try {
      const parsed = JSON.parse(row.strmOverride)
      return !!parsed && Object.keys(parsed).length > 0
    } catch {
      return false
    }
  }

  /** 提交前把勾选表单序列化回 form.strmOverride */
  const submitFormWithOverride = () => {
    const override: Record<string, any> = {}
    OVERRIDE_KEYS.forEach((key) => {
      if (overrideForm[key].enabled) {
        override[key] = overrideForm[key].value
      }
    })
    // 空字符串而非 undefined/null：updateById 默认「非空字段才更新」，
    // 传空值清不掉已有覆盖，用户取消勾选后会发现覆盖还在
    base.form.value.strmOverride = Object.keys(override).length ? JSON.stringify(override) : ''
    return base.submitForm()
  }

  // 初始化加载
  base.getList()

  return {
    ...base,
    // 移动端分页
    totalPages, prevPage, nextPage, handleSizeChange,
    // 搜索面板
    searchCollapsed,
    // 批量执行
    handleBatchExecute,
    // 任务级覆盖
    overrideForm, hasOverride, submitFormWithOverride
  }
}
