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
export function usePtIndexer() {
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
      enabled: '1'
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
      enabled: undefined
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
  const categoriesLoading = ref(false)
  const categoryOptions = ref<CategoryOption[]>([])

  const fetchCategories = async () => {
    // 编辑已有记录时 apikey 被后端脱敏为空属正常现象，留空提交交给后端按 id 回填已保存的值
    if (!base.form.value.url || (!base.form.value.apiKey && !base.form.value.id)) {
      message.warning('请先填写接口地址与 apikey')
      return
    }
    categoriesLoading.value = true
    try {
      categoryOptions.value = await getPtIndexerCategoriesApi(base.form.value) as unknown as CategoryOption[]
      message.success('分类获取成功')
    } catch (e) {
      // 失败提示已由 axios 拦截器统一弹出，见 handleTest 同类注释
      console.error('[PT索引器] 获取分类失败:', e)
    } finally {
      categoriesLoading.value = false
    }
  }

  // 分类字段落库/提交仍是逗号分隔字符串，仅在下拉展示层转换为数组
  const categoriesSelected = computed<string[]>({
    get: () => (base.form.value.categories ? String(base.form.value.categories).split(',').filter(Boolean) : []),
    set: (val: string[]) => {
      base.form.value.categories = val.length ? val.join(',') : undefined
    }
  })

  // ---------- 卡片勾选（PC 卡片网格 / 移动端卡片列表共用） ----------
  const toggleSelect = (id: number) => {
    const idx = base.selectedIds.value.indexOf(id)
    if (idx > -1) {
      base.selectedIds.value.splice(idx, 1)
    } else {
      base.selectedIds.value.push(id)
    }
    base.single.value = base.selectedIds.value.length !== 1
    base.multiple.value = !base.selectedIds.value.length
  }

  const handleCardClick = (event: Event, id: number) => {
    const target = event.target as HTMLElement
    if (target.closest('.card-checkbox') || target.closest('.card-footer')) return
    toggleSelect(id)
  }

  const clearSelection = () => {
    base.selectedIds.value = []
    base.single.value = true
    base.multiple.value = true
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

  base.getList()

  return {
    ...base, testLoading, handleTest,
    categoriesLoading, categoryOptions, fetchCategories, categoriesSelected,
    toggleSelect, handleCardClick, clearSelection,
    totalPages, prevPage, nextPage, handleSizeChange,
    searchCollapsed
  }
}
