import { ref, computed } from 'vue'
import { message } from '@/composables/useMessage'
import { useTaskList } from './useTaskList'
import {
  getPtIndexerListApi,
  addPtIndexerApi,
  updatePtIndexerApi,
  deletePtIndexerApi,
  testPtIndexerApi,
  getPtIndexerCategoriesApi
} from '@/api/openlist/ptIndexer'
import type { SearchParams } from '@/types'
import type { ListLoadOptions } from './useGridPageSize'

interface PtIndexerQuery extends SearchParams {
  name?: string
  enabled?: string
}

interface CategoryOption {
  id: number
  name: string
  children: CategoryOption[]
}

/**
 * PT Torznab 索引器配置 composable
 */
export function usePtIndexer(options: ListLoadOptions = {}) {
  const base = useTaskList<PtIndexerQuery>({
    listApi: getPtIndexerListApi,
    addApi: addPtIndexerApi,
    updateApi: updatePtIndexerApi,
    deleteApi: deletePtIndexerApi,
    idField: 'id',
    initForm: () => ({
      id: undefined,
      name: undefined,
      url: undefined,
      apiKey: undefined,
      categories: undefined,
      pollInterval: 600,
      enabled: '1',
      // H&R 默认关闭：开了却不填阈值等于没配，后端会按未启用处理
      hrEnabled: '0',
      hrSeedHours: 0,
      hrRatio: 0
    }),
    rules: {
      name: [{ required: true, message: '名称不能为空', trigger: 'blur' }],
      url: [
        { required: true, message: '接口地址不能为空', trigger: 'blur' },
        {
          pattern: /^https?:\/\//,
          message: '地址须以 http:// 或 https:// 开头',
          trigger: 'blur'
        }
      ],
      apiKey: [{ required: true, message: 'apikey 不能为空', trigger: 'blur' }],
      pollInterval: [
        { required: true, message: '轮询周期不能为空', trigger: 'blur' },
        { type: 'number', min: 60, message: '轮询周期不得小于 60 秒', trigger: 'blur' }
      ]
    },
    defaultQuery: {
      name: undefined,
      enabled: undefined,
      pageSize: 12
    }
  })

  const testLoading = ref(false)

  const handleTest = async () => {
    // 编辑已有记录时 apikey 被后端脱敏为空属正常现象，留空提交交给后端按 id 回填已保存的值
    if (!base.form.value.url || (!base.form.value.apiKey && !base.form.value.id)) {
      message.warning('请先填写接口地址与 apikey')
      return
    }
    testLoading.value = true
    try {
      await testPtIndexerApi(base.form.value)
      message.success('连接成功')
    } catch (e) {
      // 失败提示已由 axios 拦截器统一弹出（request.ts 的响应拦截器无论业务错误
      // 还是网络错误都会 ElMessage.error 具体原因后再 reject），这里不再重复弹窗，
      // 否则同一条错误信息会被展示两次
      console.error('[PT索引器] 测试连接失败:', e)
    } finally {
      testLoading.value = false
    }
  }

  // ---------- 分类获取（caps 接口） ----------
  // 分类树属于「某个索引器的某个地址」，按 id + url 分别记下。原先是整页共用一份 ref、
  // 打开别的索引器也不清空，于是编辑 B 时下拉里摆着 A 的分类，选进去的是 A 站的分类 ID。
  // 按键存而不是「打开弹窗时清空」，还顺带解决两件事：改了接口地址后旧分类自动失效；
  // 请求没回来就切到别的索引器，结果落在原来的键上，不会串到新弹窗里
  const categorySourceKey = computed(() =>
    `${base.form.value.id ?? ''}|${String(base.form.value.url ?? '').trim()}`)
  const categoryCache = ref<Record<string, CategoryOption[]>>({})
  const categoryLoadingKeys = ref<string[]>([])
  const categoryOptions = computed<CategoryOption[]>(() => categoryCache.value[categorySourceKey.value] ?? [])
  const categoriesLoading = computed(() => categoryLoadingKeys.value.includes(categorySourceKey.value))

  const fetchCategories = async () => {
    // 编辑已有记录时 apikey 被后端脱敏为空属正常现象，留空提交交给后端按 id 回填已保存的值
    if (!base.form.value.url || (!base.form.value.apiKey && !base.form.value.id)) {
      message.warning('请先填写接口地址与 apikey')
      return
    }
    const key = categorySourceKey.value
    categoryLoadingKeys.value = [...categoryLoadingKeys.value, key]
    try {
      const options = (await getPtIndexerCategoriesApi({ ...base.form.value }) as unknown as CategoryOption[]) ?? []
      categoryCache.value = { ...categoryCache.value, [key]: options }
      // 用户已经切到别的索引器时不再弹提示，免得对着另一个弹窗说「获取成功」
      if (key !== categorySourceKey.value) return
      if (options.length) message.success(`已获取 ${options.length} 个分类`)
      else message.warning('该索引器没有返回任何分类，可直接输入分类 ID')
    } catch (e) {
      // 失败提示已由 axios 拦截器统一弹出，见 handleTest 同类注释
      console.error('[PT索引器] 获取分类失败:', e)
    } finally {
      categoryLoadingKeys.value = categoryLoadingKeys.value.filter(k => k !== key)
    }
  }

  // 分类字段落库/提交仍是逗号分隔字符串，仅在下拉展示层转换为数组。
  // 下拉允许手输：值可能是选项对象，也可能是一串「2000,5000」，统一拆开、只留数字 ID——
  // Torznab 的分类 ID 全是数字，非数字原样落库会拼成 &cat=abc，索引器多半直接报错
  const categoriesSelected = computed<string[]>({
    get: () => (base.form.value.categories ? String(base.form.value.categories).split(',').filter(Boolean) : []),
    set: (val: unknown[]) => {
      const tokens = (val ?? [])
        .map(v => (typeof v === 'object' && v !== null ? String((v as any).value ?? '') : String(v)))
        .flatMap(s => s.split(/[,，\s]+/))
        .map(s => s.trim())
        .filter(Boolean)
      const ids = [...new Set(tokens.filter(s => /^\d+$/.test(s)))]
      if (ids.length < new Set(tokens).size) message.warning('分类 ID 只能是数字，已忽略非数字的输入')
      base.form.value.categories = ids.length ? ids.join(',') : undefined
    }
  })

  /** 列表卡片上的 H&R 要求摘要。两项是「或」的关系，只填了一项就只显示那一项；两端卡片共用 */
  const hrLabel = (item: any) => {
    const parts: string[] = []
    if (item.hrSeedHours > 0) parts.push(`做满 ${item.hrSeedHours}h`)
    if (item.hrRatio > 0) parts.push(`分享率 ${item.hrRatio}`)
    return parts.length ? parts.join(' 或 ') : '未配置阈值'
  }

  // ---------- 移动端 - 分页辅助 ----------
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

  // ---------- 移动端 - 搜索面板折叠 ----------
  const searchCollapsed = ref(true)

  // PC 端卡片网格页把首次加载交给 useGridPageSize（要先量出列数）
  if (options.autoLoad !== false) base.getList()

  return {
    ...base, testLoading, handleTest,
    categoriesLoading, categoryOptions, fetchCategories, categoriesSelected,
    hrLabel,
    totalPages, prevPage, nextPage, handleSizeChange,
    searchCollapsed
  }
}
