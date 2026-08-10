import { ref } from 'vue'
import { message } from '@/composables/useMessage'
import {
  getPtCleanRuleListApi,
  addPtCleanRuleApi,
  updatePtCleanRuleApi,
  deletePtCleanRuleApi,
  previewPtCleanApi,
  runPtCleanApi,
  type PtCleanRule,
  type PtCleanPreviewRow,
  type PtCleanSummary
} from '@/api/openlist/ptCleanRule'

/** 空规则：默认「不限体积、做满 72 小时、连文件一起删」，与后端默认值一致 */
const emptyRule = (downloaderId?: number): PtCleanRule => ({
  id: undefined,
  downloaderId,
  name: '',
  minSizeGb: 0,
  maxSizeGb: null,
  minSeedHours: 72,
  deleteFiles: '1',
  enabled: '1',
  sortOrder: 0,
  remark: ''
})

/**
 * 某个下载器的自动删种规则管理。
 *
 * 不复用 useTaskList：规则是「某个下载器下的一小撮配置」，永远开在弹窗里、不分页、
 * 不搜索，套通用列表 composable 只会带来一堆用不上的状态。
 */
export function usePtCleanRule() {
  /** 当前操作的下载器 */
  const downloaderId = ref<number | undefined>(undefined)
  const downloaderName = ref('')

  const rules = ref<PtCleanRule[]>([])
  const loading = ref(false)

  /** 规则编辑表单 */
  const editing = ref(false)
  const form = ref<PtCleanRule>(emptyRule())
  const submitLoading = ref(false)

  /** 预览与执行 */
  const previewRows = ref<PtCleanPreviewRow[]>([])
  const previewLoading = ref(false)
  const runLoading = ref(false)
  const lastSummary = ref<PtCleanSummary | null>(null)

  const load = async (id?: number) => {
    const target = id ?? downloaderId.value
    if (!target) return
    loading.value = true
    try {
      const res = await getPtCleanRuleListApi({ downloaderId: target, pageNum: 1, pageSize: 100 } as any)
      rules.value = res?.records || []
    } catch (e) {
      // 失败提示已由 axios 拦截器统一弹出
      console.error('[PT删种规则] 加载失败:', e)
    } finally {
      loading.value = false
    }
  }

  /** 打开某个下载器的规则管理：换下载器时把上一次的预览结果一并清掉，避免张冠李戴 */
  const openFor = async (id: number, name: string) => {
    downloaderId.value = id
    downloaderName.value = name
    previewRows.value = []
    lastSummary.value = null
    editing.value = false
    await load(id)
  }

  const startAdd = () => {
    form.value = emptyRule(downloaderId.value)
    editing.value = true
  }

  const startEdit = (row: PtCleanRule) => {
    form.value = { ...row }
    editing.value = true
  }

  const cancelEdit = () => {
    editing.value = false
  }

  const submitRule = async () => {
    if (!form.value.name) {
      message.warning('请填写规则名')
      return
    }
    if (!form.value.downloaderId) {
      form.value.downloaderId = downloaderId.value
    }
    // 区间是左闭右开的，上界必须严格大于下界，否则这条规则永远命中不了任何种子
    const min = Number(form.value.minSizeGb ?? 0)
    const max = form.value.maxSizeGb === null || form.value.maxSizeGb === undefined || (form.value.maxSizeGb as any) === ''
      ? null
      : Number(form.value.maxSizeGb)
    if (max !== null && max <= min) {
      message.warning('体积上界必须大于下界')
      return
    }
    form.value.maxSizeGb = max
    submitLoading.value = true
    try {
      if (form.value.id) {
        await updatePtCleanRuleApi(form.value)
      } else {
        await addPtCleanRuleApi(form.value)
      }
      message.success('保存成功')
      editing.value = false
      await load()
    } catch (e) {
      console.error('[PT删种规则] 保存失败:', e)
    } finally {
      submitLoading.value = false
    }
  }

  const removeRule = async (row: PtCleanRule) => {
    if (!row.id) return
    try {
      await deletePtCleanRuleApi(row.id)
      message.success('删除成功')
      await load()
    } catch (e) {
      console.error('[PT删种规则] 删除失败:', e)
    }
  }

  /** 预览：只判定不删除 */
  const handlePreview = async () => {
    if (!downloaderId.value) return
    previewLoading.value = true
    try {
      previewRows.value = (await previewPtCleanApi(downloaderId.value)) || []
      if (previewRows.value.length === 0) {
        message.info('没有扫描到任何种子，或该下载器还没有启用中的规则')
      }
    } catch (e) {
      console.error('[PT删种规则] 预览失败:', e)
    } finally {
      previewLoading.value = false
    }
  }

  /** 立即执行一次清理。删种不可逆，调用方必须先弹确认框 */
  const handleRun = async () => {
    if (!downloaderId.value) return
    runLoading.value = true
    try {
      lastSummary.value = await runPtCleanApi(downloaderId.value)
      const summary = lastSummary.value
      if (summary?.noRules) {
        message.warning('该下载器没有任何启用中的规则，未删除任何种子')
      } else {
        message.success(`已清理 ${summary?.deletedGroups || 0} 组，释放 ${formatSize(summary?.freedBytes || 0)}`)
      }
      // 执行后旧的预览结果已经不准了，清掉而不是留在界面上误导
      previewRows.value = []
    } catch (e) {
      console.error('[PT删种规则] 执行失败:', e)
    } finally {
      runLoading.value = false
    }
  }

  return {
    downloaderId, downloaderName, rules, loading,
    editing, form, submitLoading,
    previewRows, previewLoading, runLoading, lastSummary,
    load, openFor, startAdd, startEdit, cancelEdit, submitRule, removeRule,
    handlePreview, handleRun
  }
}

/** 字节数转人类可读，规则弹窗与预览列表共用 */
export function formatSize(bytes: number): string {
  if (!bytes || bytes < 0) return '0 B'
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}
