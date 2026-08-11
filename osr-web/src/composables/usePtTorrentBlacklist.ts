import { ref, computed } from 'vue'
import { useTaskList } from './useTaskList'
import {
  getPtTorrentBlacklistListApi,
  addPtTorrentBlacklistApi,
  updatePtTorrentBlacklistApi,
  deletePtTorrentBlacklistApi
} from '@/api/openlist/ptTorrentBlacklist'
import type { PtTorrentBlacklistQuery } from '@/api/openlist/ptTorrentBlacklist'
import type { ListLoadOptions } from './useGridPageSize'

/**
 * PT 种子/发布组黑名单 composable。
 * 管理页只暴露"新增发布组规则"与"删除"，不提供修改入口——GUID 类型的规则只能通过
 * 下载记录页的拉黑按钮产生，管理页新增一律按发布组类型处理，后端会拒绝 type=GUID 的写请求。
 */
export function usePtTorrentBlacklist(options: ListLoadOptions = {}) {
  const base = useTaskList<PtTorrentBlacklistQuery>({
    listApi: getPtTorrentBlacklistListApi,
    addApi: addPtTorrentBlacklistApi,
    updateApi: updatePtTorrentBlacklistApi,
    deleteApi: deletePtTorrentBlacklistApi,
    idField: 'id',
    initForm: () => ({
      id: undefined,
      type: 'RELEASE_GROUP',
      value: undefined,
      reason: undefined
    }),
    rules: {
      value: [{ required: true, message: '发布组名不能为空', trigger: 'blur' }]
    },
    defaultQuery: {
      type: undefined,
      displayValue: undefined,
      pageSize: 12
    }
  })

  const searchCollapsed = ref(true)

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

  // PC 端卡片网格页把首次加载交给 useGridPageSize（要先量出列数）
  if (options.autoLoad !== false) base.getList()

  return { ...base, searchCollapsed, totalPages, prevPage, nextPage, handleSizeChange }
}
