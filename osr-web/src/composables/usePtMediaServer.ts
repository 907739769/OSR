import { ref, computed } from 'vue'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import { useTaskList } from './useTaskList'
import {
  getPtMediaServerListApi,
  addPtMediaServerApi,
  updatePtMediaServerApi,
  deletePtMediaServerApi,
  testPtMediaServerApi,
  listPtMediaServerUsersApi
} from '@/api/openlist/ptMediaServer'
import type { SearchParams } from '@/types'
import type { ListLoadOptions } from './useGridPageSize'

interface PtMediaServerQuery extends SearchParams {
  name?: string
  enabled?: string
}

interface MediaServerUser {
  id: string
  name?: string
}

/**
 * 停用/删除最后一台启用中的媒体服务器时，确认框里要多说的那段话。
 *
 * 媒体服务器是订阅「已入库」判定的唯一数据来源，停掉最后一台的后果是连锁的，而这两个操作
 * 原先的提示分别是模板文案「是否确认删除编号为…的数据项？」和一个连确认框都没有的单选按钮。
 */
const LAST_ENABLED_WARNING =
  '这是当前唯一启用中的媒体服务器。停用或删除后，所有订阅的「已入库」判定将失效：'
  + '进度不再推进，卡死在途集清扫也会整体跳过（这是有意的，没有对账依据时清扫会把每一次'
  + '成功的下载都判成卡死）。已入库的集不会丢失，重新启用后下一轮对账即可恢复。'

/**
 * PT 媒体服务器（Emby/Jellyfin）配置 composable
 */
export function usePtMediaServer(options: ListLoadOptions = {}) {
  const base = useTaskList<PtMediaServerQuery>({
    listApi: getPtMediaServerListApi,
    addApi: addPtMediaServerApi,
    updateApi: updatePtMediaServerApi,
    deleteApi: deletePtMediaServerApi,
    idField: 'id',
    initForm: () => ({
      id: undefined,
      name: undefined,
      type: 'EMBY',
      url: undefined,
      apiKey: undefined,
      userId: undefined,
      enabled: '1'
    }),
    rules: {
      name: [{ required: true, message: '名称不能为空', trigger: 'blur' }],
      url: [
        { required: true, message: '服务器地址不能为空', trigger: 'blur' },
        {
          pattern: /^https?:\/\//,
          message: '地址须以 http:// 或 https:// 开头',
          trigger: 'blur'
        }
      ],
      apiKey: [{ required: true, message: 'API Key 不能为空', trigger: 'blur' }]
    },
    defaultQuery: {
      name: undefined,
      enabled: undefined,
      pageSize: 12
    }
  })

  const testLoading = ref(false)

  const handleTest = async () => {
    // 编辑已有记录时 apiKey 被后端脱敏为空属正常现象，留空提交交给后端按 id 回填已保存的值
    if (!base.form.value.url || (!base.form.value.apiKey && !base.form.value.id)) {
      message.warning('请先填写服务器地址与 API Key')
      return
    }
    testLoading.value = true
    try {
      // 后端回的是「已连通：Emby 4.8.0.80 · 客厅」这类描述，原样显示——它能让用户确认
      // 自己连上的是不是心里想的那一台（配了反代、做了端口映射时尤其值钱）
      const detail = await testPtMediaServerApi(base.form.value)
      message.success(typeof detail === 'string' && detail ? detail : '连接成功')
    } catch (e) {
      // 失败提示已由 axios 拦截器统一弹出，见 usePtIndexer.ts 中的说明，这里不再重复弹窗。
      // 后端现在给的是具体原因（API Key 无效 / 地址可能不对 / 对端不可用），不要用通用文案盖掉
      console.error('[PT媒体服务器] 测试连接失败:', e)
    } finally {
      testLoading.value = false
    }
  }

  // ---------- 用户列表 ----------
  const users = ref<MediaServerUser[]>([])
  const usersLoading = ref(false)

  const handleLoadUsers = async () => {
    if (!base.form.value.url || (!base.form.value.apiKey && !base.form.value.id)) {
      message.warning('请先填写服务器地址与 API Key')
      return
    }
    usersLoading.value = true
    try {
      const list = await listPtMediaServerUsersApi(base.form.value)
      users.value = Array.isArray(list) ? list : []
      if (!users.value.length) {
        message.warning('该媒体服务器上没有查询到用户')
      }
    } catch (e) {
      console.error('[PT媒体服务器] 拉取用户列表失败:', e)
    } finally {
      usersLoading.value = false
    }
  }

  // ---------- 「最后一台启用中」的提醒 ----------
  //
  // 判据取当前列表里 enabled === '1' 的条数。列表只有当前页，但这张表实际只有个位数行、
  // 每页 12 条，跨页的情况不存在；真要跨页了，多问一句确认也不会有害。

  const enabledServers = computed(() => base.taskList.value.filter((i: any) => i.enabled === '1'))

  const isLastEnabled = (row: any) =>
    row?.enabled === '1' && enabledServers.value.length <= 1

  /** 删除前按「是不是最后一台启用中的」换一套确认文案 */
  const handleDelete = async (row?: any) => {
    if (row?.id) {
      const suffix = isLastEnabled(row) ? `\n\n⚠ ${LAST_ENABLED_WARNING}` : ''
      return base.handleDelete(row, `是否确认删除媒体服务器「${row.name ?? row.id}」？${suffix}`)
    }
    // 批量删除：选中的里面包含了全部启用中的服务器时同样要提醒
    const ids = base.selectedIds.value
    const removingAllEnabled = enabledServers.value.length > 0
      && enabledServers.value.every((s: any) => ids.includes(s.id))
    const suffix = removingAllEnabled ? `\n\n⚠ ${LAST_ENABLED_WARNING}` : ''
    return base.handleDelete(undefined, `是否确认删除选中的 ${ids.length} 台媒体服务器？${suffix}`)
  }

  /**
   * 提交前拦一道：把最后一台启用中的服务器改成「停用」时先确认。
   * <p>
   * 停用比删除更容易误触——它只是弹窗里的一个单选按钮，此前连确认框都没有，
   * 而后果与删除完全一样。
   */
  const submitForm = async () => {
    const id = base.form.value.id
    const current = base.taskList.value.find((i: any) => i.id === id)
    const disablingLast = id
      && base.form.value.enabled === '0'
      && current?.enabled === '1'
      && enabledServers.value.length <= 1
    if (disablingLast) {
      try {
        await confirm({
          message: `确认停用媒体服务器「${base.form.value.name ?? id}」？\n\n⚠ ${LAST_ENABLED_WARNING}`,
          title: '警告',
          type: 'warning'
        })
      } catch {
        return
      }
    }
    return base.submitForm()
  }

  /** 打开弹窗时清掉上一台服务器的用户列表，否则会把 A 的用户显示在 B 的表单里 */
  const handleAdd = (title: string) => {
    users.value = []
    return base.handleAdd(title)
  }

  const handleUpdate = (row?: any, title?: string) => {
    users.value = []
    return base.handleUpdate(row, title)
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
    ...base,
    // 覆盖必须排在展开之后，否则会被 base 的同名实现盖回去
    handleDelete, submitForm, handleAdd, handleUpdate,
    testLoading, handleTest,
    users, usersLoading, handleLoadUsers,
    totalPages, prevPage, nextPage, handleSizeChange,
    searchCollapsed
  }
}
