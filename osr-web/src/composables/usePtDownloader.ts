import { ref, watch, computed } from 'vue'
import { message } from '@/composables/useMessage'
import { useTaskList } from './useTaskList'
import {
  getPtDownloaderListApi,
  addPtDownloaderApi,
  updatePtDownloaderApi,
  deletePtDownloaderApi,
  testPtDownloaderApi,
  validateSavePathApi
} from '@/api/openlist/ptDownloader'
import type { SearchParams } from '@/types'

interface PtDownloaderQuery extends SearchParams {
  name?: string
  enabled?: string
}

/**
 * PT 下载器配置 composable
 */
export function usePtDownloader() {
  const base = useTaskList<PtDownloaderQuery>({
    listApi: getPtDownloaderListApi,
    addApi: addPtDownloaderApi,
    updateApi: updatePtDownloaderApi,
    deleteApi: deletePtDownloaderApi,
    idField: 'id',
    initForm: () => ({
      id: undefined,
      name: undefined,
      type: 'QBITTORRENT',
      host: undefined,
      port: 8080,
      useHttps: '0',
      username: undefined,
      password: undefined,
      savePath: undefined,
      tag: 'osr-pt',
      maxConcurrent: 0,
      enabled: '1',
      smartClassifyLevel: 'NONE'
    }),
    rules: {
      name: [{ required: true, message: '名称不能为空', trigger: 'blur' }],
      host: [{ required: true, message: '主机不能为空', trigger: 'blur' }],
      port: [
        { required: true, message: '端口不能为空', trigger: 'blur' },
        { type: 'number', min: 1, max: 65535, message: '端口须在 1-65535 之间', trigger: 'blur' }
      ],
      savePath: [{ required: true, message: '保存路径不能为空', trigger: 'blur' }],
      tag: [{ required: true, message: '标签不能为空', trigger: 'blur' }],
      maxConcurrent: [
        { type: 'number', min: 0, message: '最大并发数不能为负数', trigger: 'blur' }
      ]
    },
    defaultQuery: {
      name: undefined,
      enabled: undefined,
      pageSize: 12
    }
  })

  const testLoading = ref(false)
  /** 保存路径校验警告文案，空串表示无警告 */
  const savePathWarning = ref('')

  const handleTest = async () => {
    if (!base.form.value.host || !base.form.value.port) {
      message.warning('请先填写主机与端口')
      return
    }
    testLoading.value = true
    try {
      await testPtDownloaderApi(base.form.value)
      message.success('连接成功')
    } catch (e) {
      // 失败提示已由 axios 拦截器统一弹出，见 usePtIndexer.ts 中的说明，这里不再重复弹窗
      console.error('[PT下载器] 测试连接失败:', e)
    } finally {
      testLoading.value = false
    }
  }

  /**
   * 保存路径失焦时校验。不阻断保存，仅提示——
   * 路径不在文件同步任务的监听目录下时，下载完成的文件不会被自动上传网盘。
   * 只传 savePath 字段：后端 validateSavePath 只用这一个字段，没必要把包含明文密码的整个表单发出去。
   */
  const handleSavePathBlur = async () => {
    if (!base.form.value.savePath) {
      savePathWarning.value = ''
      return
    }
    try {
      savePathWarning.value = (await validateSavePathApi({ savePath: base.form.value.savePath })) || ''
    } catch (e) {
      // 校验请求失败 ≠ 路径没问题。这条警告是本阶段唯一保障"下载完成的文件能被
      // FileMonitor 链路接管并上传网盘"的护栏，清空警告会让用户误读为路径已通过校验。
      console.error('[PT下载器] 保存路径校验失败:', e)
      savePathWarning.value = '校验失败，无法确认保存路径是否在监听目录下'
    }
  }

  /** 新增：先清空上一次残留的警告，避免复用到新表单上 */
  const handleAdd = (title: string) => {
    savePathWarning.value = ''
    base.handleAdd(title)
  }

  /**
   * 编辑：先清空警告，再委托 base.handleUpdate 异步查询并填充表单。
   * base.handleUpdate（见 useTaskList.ts）内部没有把查询用的 Promise 返回出来，
   * 因此不能直接 await base.handleUpdate() 来判断表单何时填好；改为监听
   * open 由 false → true 的那一刻（这正是 base.handleUpdate 找到记录、填完表单、
   * 打开对话框的时刻）触发一次校验，时序上与"表单已就绪"精确对齐。
   */
  const handleUpdate = (row?: any, title?: string) => {
    savePathWarning.value = ''
    base.handleUpdate(row, title)
  }

  watch(base.open, (isOpen) => {
    // 只有编辑场景（表单已带 id）才需要校验既有路径；新增场景 savePath 为空，
    // handleSavePathBlur 内部会自行短路跳过，这里加 id 判断只是避免一次无意义的调用。
    if (isOpen && base.form.value?.id) {
      handleSavePathBlur()
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
    ...base, testLoading, handleTest, savePathWarning, handleSavePathBlur, handleAdd, handleUpdate,
    toggleSelect, handleCardClick, clearSelection,
    totalPages, prevPage, nextPage, handleSizeChange,
    searchCollapsed
  }
}
