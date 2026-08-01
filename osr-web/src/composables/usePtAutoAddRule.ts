import { ref, computed } from 'vue'
import { message } from '@/composables/useMessage'
import { useTaskList } from './useTaskList'
import {
  getPtAutoAddRuleListApi,
  addPtAutoAddRuleApi,
  updatePtAutoAddRuleApi,
  deletePtAutoAddRuleApi,
  runPtAutoAddRuleApi,
  getPtAutoAddRuleLogsApi
} from '@/api/openlist/ptAutoAddRule'
import type { PtAutoAddRuleQuery } from '@/api/openlist/ptAutoAddRule'
import { getPtDownloaderListApi } from '@/api/openlist/ptDownloader'

/** TMDb 电影类型，id 与官方 zh-CN 译名对应，供"排除类型"下拉使用 */
export const MOVIE_GENRE_OPTIONS = [
  { id: 28, label: '动作' }, { id: 12, label: '冒险' }, { id: 16, label: '动画' },
  { id: 35, label: '喜剧' }, { id: 80, label: '犯罪' }, { id: 99, label: '纪录' },
  { id: 18, label: '剧情' }, { id: 10751, label: '家庭' }, { id: 14, label: '奇幻' },
  { id: 36, label: '历史' }, { id: 27, label: '恐怖' }, { id: 10402, label: '音乐' },
  { id: 9648, label: '悬疑' }, { id: 10749, label: '爱情' }, { id: 878, label: '科幻' },
  { id: 10770, label: '电视电影' }, { id: 53, label: '惊悚' }, { id: 10752, label: '战争' },
  { id: 37, label: '西部' }
]

/** TMDb 剧集类型，id 与官方 zh-CN 译名对应 */
export const TV_GENRE_OPTIONS = [
  { id: 10759, label: '动作冒险' }, { id: 16, label: '动画' }, { id: 35, label: '喜剧' },
  { id: 80, label: '犯罪' }, { id: 99, label: '纪录' }, { id: 18, label: '剧情' },
  { id: 10751, label: '家庭' }, { id: 10762, label: '儿童' }, { id: 9648, label: '悬疑' },
  { id: 10763, label: '新闻' }, { id: 10764, label: '真人秀' }, { id: 10765, label: '科幻奇幻' },
  { id: 10766, label: '肥皂剧' }, { id: 10767, label: '脱口秀' }, { id: 10768, label: '战争与政治' },
  { id: 37, label: '西部' }
]

/** 常用地区，ISO 3166-1 代码，供"地区"下拉使用（仅 TMDB_DISCOVER 生效） */
export const REGION_OPTIONS = [
  { code: 'CN', label: '中国大陆' }, { code: 'HK', label: '中国香港' }, { code: 'TW', label: '中国台湾' },
  { code: 'US', label: '美国' }, { code: 'JP', label: '日本' }, { code: 'KR', label: '韩国' },
  { code: 'GB', label: '英国' }, { code: 'FR', label: '法国' }, { code: 'DE', label: '德国' },
  { code: 'IN', label: '印度' }, { code: 'CA', label: '加拿大' }, { code: 'AU', label: '澳大利亚' },
  { code: 'IT', label: '意大利' }, { code: 'ES', label: '西班牙' }, { code: 'RU', label: '俄罗斯' },
  { code: 'TH', label: '泰国' }, { code: 'TR', label: '土耳其' }
]

/**
 * 热门自动订阅规则 composable。
 * 在通用列表 CRUD 之外，额外提供"立即执行一次"与"查看执行日志"两个动作。
 */
export function usePtAutoAddRule() {
  const base = useTaskList<PtAutoAddRuleQuery>({
    listApi: getPtAutoAddRuleListApi,
    addApi: addPtAutoAddRuleApi,
    updateApi: updatePtAutoAddRuleApi,
    deleteApi: deletePtAutoAddRuleApi,
    idField: 'id',
    initForm: () => ({
      id: undefined,
      name: undefined,
      enabled: '1',
      mediaType: 'MOVIE',
      source: 'TMDB_TRENDING_WEEK',
      genreExclude: undefined,
      minVoteAverage: undefined,
      minVoteCount: undefined,
      region: undefined,
      maxAddPerRun: 5,
      intervalHours: 24,
      downloaderId: undefined,
      filterOverride: undefined
    }),
    rules: {
      name: [{ required: true, message: '规则名称不能为空', trigger: 'blur' }],
      mediaType: [{ required: true, message: '请选择媒体类型', trigger: 'change' }],
      source: [{ required: true, message: '请选择数据源', trigger: 'change' }]
    },
    defaultQuery: {
      name: undefined,
      mediaType: undefined,
      enabled: undefined
    }
  })

  const searchCollapsed = ref(true)

  // ---------- 排除类型：CSV 字符串(后端字段) <-> 数组(下拉多选) 互转 ----------
  const genreOptions = computed(() => (base.form.value.mediaType === 'MOVIE' ? MOVIE_GENRE_OPTIONS : TV_GENRE_OPTIONS))

  const genreExcludeArr = computed<number[]>({
    get() {
      const csv = base.form.value.genreExclude
      if (!csv) return []
      return String(csv).split(',').map((s: string) => Number(s.trim())).filter((n: number) => !Number.isNaN(n))
    },
    set(val: number[]) {
      base.form.value.genreExclude = val.length ? val.join(',') : undefined
    }
  })

  // ---------- 下载器下拉 ----------
  const downloaderOptions = ref<any[]>([])

  const loadDownloaderOptions = async () => {
    try {
      const res = await getPtDownloaderListApi({ pageNum: 1, pageSize: 100 })
      downloaderOptions.value = res.records || []
    } catch (e) {
      console.error(e)
    }
  }
  loadDownloaderOptions()

  // ---------- 移动端 - 分页辅助 ----------
  const totalPages = computed(() => Math.ceil(base.total.value / base.queryParams.pageSize!) || 1)

  const prevPage = () => {
    if (base.queryParams.pageNum! > 1) {
      base.queryParams.pageNum!--
      base.getList()
    }
  }

  const nextPage = () => {
    if (base.queryParams.pageNum! < totalPages.value) {
      base.queryParams.pageNum!++
      base.getList()
    }
  }

  const handleSizeChange = () => {
    base.queryParams.pageNum = 1
    base.getList()
  }

  // ---------- 立即执行 ----------
  const runningIds = ref<Set<number>>(new Set())

  const handleRun = async (row: any) => {
    if (runningIds.value.has(row.id)) return
    runningIds.value.add(row.id)
    try {
      const result = await runPtAutoAddRuleApi(row.id)
      message.success(`执行完成：新增${result.addedCount} 跳过${result.skippedCount} 失败${result.failedCount}`)
      base.getList()
    } catch (e) {
      console.error(e)
    } finally {
      runningIds.value.delete(row.id)
    }
  }

  // ---------- 执行日志 ----------
  const logDialogVisible = ref(false)
  const logLoading = ref(false)
  const logList = ref<any[]>([])

  const handleShowLogs = async (row: any) => {
    logDialogVisible.value = true
    logLoading.value = true
    try {
      logList.value = await getPtAutoAddRuleLogsApi(row.id)
    } finally {
      logLoading.value = false
    }
  }

  /** 规则的过滤条件摘要（PC 表格列与移动端卡片共用，避免两端文案漂移） */
  const filterText = (row: any): string => {
    const parts: string[] = []
    if (row?.minVoteAverage) parts.push(`评分≥${row.minVoteAverage}`)
    if (row?.minVoteCount) parts.push(`评分人数≥${row.minVoteCount}`)
    if (row?.genreExclude) parts.push(`排除类型:${row.genreExclude}`)
    return parts.join(' ')
  }

  base.getList()

  return {
    ...base, searchCollapsed, totalPages, prevPage, nextPage, handleSizeChange,
    runningIds, handleRun,
    logDialogVisible, logLoading, logList, handleShowLogs,
    genreOptions, genreExcludeArr, downloaderOptions,
    filterText
  }
}
