import { ref, computed } from 'vue'
import { useTaskList } from './useTaskList'
import {
  getWecomUserListApi,
  addWecomUserApi,
  updateWecomUserApi,
  deleteWecomUserApi,
  getSelectableUsersApi,
  syncWecomMenuApi
} from '@/api/system/wecomUser'
import { message } from '@/composables/useMessage'
import type { SelectableUser, WecomUserQuery } from '@/api/system/wecomUser'
import type { ListLoadOptions } from './useGridPageSize'

/**
 * 企业微信成员绑定 composable，PC 与移动端共用。
 *
 * 绑定关系决定了「企微里发指令的这个人是 OSR 的谁」以及「订阅通知推给谁」，
 * 所以选人一律走后端下发的用户列表，不让用户手填 userId。
 */
export function useWecomUser(options: ListLoadOptions = {}) {
  const base = useTaskList<WecomUserQuery>({
    listApi: getWecomUserListApi,
    addApi: addWecomUserApi,
    updateApi: updateWecomUserApi,
    deleteApi: deleteWecomUserApi,
    idField: 'id',
    initForm: () => ({
      id: undefined,
      wecomUserid: undefined,
      sysUserId: undefined,
      status: '0',
      remark: undefined
    }),
    rules: {
      wecomUserid: [{ required: true, message: '企业微信 UserId 不能为空', trigger: 'blur' }],
      sysUserId: [{ required: true, message: '请选择要绑定的 OSR 用户', trigger: 'change' }]
    },
    defaultQuery: {
      wecomUserid: undefined,
      sysUserName: undefined,
      status: undefined,
      pageSize: 12
    }
  })

  const searchCollapsed = ref(true)

  // ---------- OSR 用户下拉 ----------
  const selectableUsers = ref<SelectableUser[]>([])

  /** 下拉项：登录名 + 昵称一起显示，同名昵称时还能靠登录名分辨 */
  const userOptions = computed(() =>
    selectableUsers.value.map(u => ({
      title: u.userName ? `${u.loginName}（${u.userName}）` : u.loginName,
      value: u.userId
    }))
  )

  const loadSelectableUsers = async () => {
    // 失败不弹错：下拉拉不到时页面其余部分照常可用，弹窗里选不到人已经是足够明确的反馈
    try {
      selectableUsers.value = (await getSelectableUsersApi()) || []
    } catch (e) {
      console.error(e)
    }
  }

  // ---------- 同步应用菜单 ----------
  const syncingMenu = ref(false)

  /**
   * 把菜单写入企业微信。失败原因（未配置、可信IP、Secret 错等）由后端原样带出，
   * 这里直接展示——企微的错误码信息量很大，包装成「同步失败」反而没法排查。
   */
  const handleSyncMenu = async () => {
    syncingMenu.value = true
    try {
      await syncWecomMenuApi()
      message.success('菜单已同步，在企微中重新进入应用即可看到')
    } finally {
      syncingMenu.value = false
    }
  }

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
  loadSelectableUsers()

  return {
    ...base,
    searchCollapsed,
    selectableUsers,
    userOptions,
    loadSelectableUsers,
    syncingMenu,
    handleSyncMenu,
    totalPages,
    prevPage,
    nextPage,
    handleSizeChange
  }
}
