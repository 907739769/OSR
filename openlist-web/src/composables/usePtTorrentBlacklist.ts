import { ref } from 'vue'
import { useTaskList } from './useTaskList'
import {
  getPtTorrentBlacklistListApi,
  addPtTorrentBlacklistApi,
  updatePtTorrentBlacklistApi,
  deletePtTorrentBlacklistApi
} from '@/api/openlist/ptTorrentBlacklist'
import type { PtTorrentBlacklistQuery } from '@/api/openlist/ptTorrentBlacklist'

/**
 * PT 种子/发布组黑名单 composable。
 * 管理页只暴露"新增发布组规则"与"删除"，不提供修改入口——GUID 类型的规则只能通过
 * 下载记录页的拉黑按钮产生，管理页新增一律按发布组类型处理，后端会拒绝 type=GUID 的写请求。
 */
export function usePtTorrentBlacklist() {
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
      displayValue: undefined
    }
  })

  const searchCollapsed = ref(true)

  base.getList()

  return { ...base, searchCollapsed }
}
