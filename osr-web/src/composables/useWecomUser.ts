import { ref, computed } from 'vue'
import { useTaskList } from './useTaskList'
import {
  getWecomUserListApi,
  addWecomUserApi,
  updateWecomUserApi,
  deleteWecomUserApi,
  getSelectableUsersApi
} from '@/api/system/wecomUser'
import type { SelectableUser, WecomUserQuery } from '@/api/system/wecomUser'

/**
 * 企业微信成员绑定 composable，PC 与移动端共用。
 *
 * 绑定关系决定了「企微里发指令的这个人是 OSR 的谁」以及「订阅通知推给谁」，
 * 所以选人一律走后端下发的用户列表，不让用户手填 userId。
 */
export function useWecomUser() {
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

  base.getList()
  loadSelectableUsers()

  return {
    ...base,
    searchCollapsed,
    selectableUsers,
    userOptions,
    loadSelectableUsers,
    totalPages,
    prevPage,
    nextPage,
    handleSizeChange
  }
}
