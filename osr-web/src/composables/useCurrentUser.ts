import { computed } from 'vue'
import { useUserStore } from '@/stores/user'

/**
 * 当前登录用户的展示名与头像首字。
 *
 * 两个 Layout 原先把「管理员」和头像里的「管」直接写死在模板里，而 userInfo.userName
 * 一直是有值的（dashboard/desktop.vue 就是这么取的）——多用户下所有人登录后都顶着
 * 「管理员」的名字，是那种一眼就看得出来的假。三处调用点收敛到这里。
 */
export function useCurrentUser() {
  const userStore = useUserStore()

  /** 昵称优先，其次登录名；都没有（信息还没拉回来）时给一个中性兜底，不要再假装是管理员 */
  const displayName = computed(
    () => userStore.userInfo?.userName || userStore.userInfo?.loginName || '未登录'
  )

  /** 头像里的首字。中文取第一个字，英文名取首字母大写 */
  const avatarText = computed(() => {
    const name = displayName.value.trim()
    if (!name) return '?'
    const first = [...name][0]
    return /[a-z]/.test(first) ? first.toUpperCase() : first
  })

  return { displayName, avatarText }
}
